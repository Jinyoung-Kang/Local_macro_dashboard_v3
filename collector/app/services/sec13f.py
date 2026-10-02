"""
app/services/sec13f.py
SEC EDGAR 13F-HR 공시 수집.

[지켜야 할 것]
- SEC는 연락처가 포함된 User-Agent를 요구하고(미준수 시 403), 초당 요청
  한도가 있습니다. 한도는 app/http.sec_rate_limit() 토큰 버킷이 전역으로
  지키므로 기관을 병렬 처리해도 합계 한도를 넘지 않습니다.
- 2023년 이전 공시는 금액 단위가 천 달러입니다. 합계가 $100M 미만이면
  천 달러 단위로 보고 1000을 곱합니다(구버전과 동일한 보정).
- CUSIP은 숫자로만 이루어진 경우가 많습니다(예: 037833100). 반드시
  **문자열**로 다룹니다. 숫자로 바꾸면 앞자리 0이 사라집니다.
"""
from __future__ import annotations

import logging
import xml.etree.ElementTree as ET
from datetime import datetime

from bs4 import BeautifulSoup

from ..http import (
    SecUserAgentInvalid,
    brief_error,
    get_sec_session,
    sec_rate_limit,
)

logger = logging.getLogger(__name__)

EDGAR_BROWSE = "https://www.sec.gov/cgi-bin/browse-edgar"


def collect_13f(cik: str, max_quarters: int = 8) -> dict:
    """
    최근 max_quarters개 분기의 13F를 수집합니다.

    반환 계약(JSON):
    {
      "cik": "0001067983",
      "error": null | "사람이 읽는 실패 사유",
      "quarters": [
        {"filingDate","reportDate","totalValue",
         "holdings":[{"name","cusip","class","value","shares","weight"}]}
      ]
    }
    quarters는 최신 분기가 앞입니다(q1은 q8의 앞부분과 같습니다).
    """
    try:
        session = get_sec_session()
    except SecUserAgentInvalid as exc:
        # 설정 누락은 '연결 실패'가 아닙니다. 12개 기관마다 같은 망 오류가 뜬 것처럼
        # 보이면 진짜 원인(.env 한 줄)을 찾기 어려워집니다.
        return _fail(cik, str(exc))

    cik_padded = str(cik).lstrip("0").zfill(10)

    params = {
        "action": "getcompany",
        "CIK": cik_padded,
        "type": "13F-HR",
        "dateb": "",
        "owner": "include",
        "count": 40,
    }

    try:
        sec_rate_limit()
        res = session.get(EDGAR_BROWSE, params=params, timeout=30)
        res.raise_for_status()
    except Exception as exc:  # noqa: BLE001
        # 403은 대개 "연락처 없는 User-Agent"입니다. SEC가 정책으로 막습니다.
        hint = (
            " — SEC_USER_AGENT에 본인 이메일을 넣으세요"
            if "403" in str(exc) else ""
        )
        return _fail(cik, f"SEC EDGAR 연결 실패: {brief_error(exc)}{hint}")

    links = _parse_filing_links(res.text, max_quarters)
    if not links:
        return _fail(cik, "조회된 13F-HR 공시 문서가 없습니다.")

    quarters = []
    for filing_date, doc_url in links:
        holdings = _collect_one_filing(session, filing_date, doc_url)
        if holdings is None:
            continue
        quarters.append(holdings)

    if not quarters:
        return _fail(
            cik,
            "성공적으로 추출된 분기 데이터가 없습니다 "
            "(공시 문서가 비었거나 파싱 가능한 13F XML이 없습니다).",
        )

    return {"cik": str(cik), "error": None, "quarters": quarters}


def _parse_filing_links(html: str, max_quarters: int) -> list[tuple[str, str]]:
    """
    공시 목록에서 (제출일, 문서 목록 URL)을 최신순으로 max_quarters개 고릅니다.

    원본 13F-HR만 분기로 셉니다. EDGAR의 type=13F-HR 목록에는 정정(13F-HR/A)도
    섞여 오는데, 그걸 세면 같은 분기가 두 번 들어가고(q8이 실제로는 7개 분기),
    가장 최근 공시가 '신규 보유분만' 담은 부분 정정이면 q1이 부분 포트폴리오가
    되어 비중이 그 부분 합계로 정규화됩니다.
    """
    soup = BeautifulSoup(html, "html.parser")
    tables = soup.find_all("table", class_="tableFile2")
    if not tables:
        return []

    links: list[tuple[str, str]] = []
    for row in tables[0].find_all("tr")[1:]:
        cols = row.find_all("td")
        if len(cols) < 4:
            continue
        if cols[0].text.strip().upper() != "13F-HR":
            continue
        anchor = cols[1].find("a", href=True)
        if not anchor:
            continue
        href = anchor["href"]
        links.append((
            cols[3].text.strip(),
            href if href.startswith("http") else f"https://www.sec.gov{href}",
        ))
        if len(links) >= max_quarters:
            break
    return links


def _collect_one_filing(session, filing_date: str, doc_url: str) -> dict | None:
    try:
        sec_rate_limit()
        res = session.get(doc_url, timeout=30)
        res.raise_for_status()
    except Exception as exc:  # noqa: BLE001
        logger.warning("13F 문서 목록 조회 실패 (%s): %s", filing_date, exc)
        return None

    xml_url = _find_information_table_url(res.text)
    if not xml_url:
        return None

    try:
        sec_rate_limit()
        xml_res = session.get(xml_url, timeout=30)
        xml_res.raise_for_status()
    except Exception as exc:  # noqa: BLE001
        logger.warning("13F XML 다운로드 실패 (%s): %s", filing_date, exc)
        return None

    holdings = _parse_information_table(xml_res.content, filing_date)
    if not holdings:
        return None

    total = sum(h["value"] for h in holdings)

    # 2023년 이전 공시는 천 달러 단위입니다.
    if 0 < total < 100_000_000:
        for holding in holdings:
            holding["value"] *= 1000.0
        total *= 1000.0

    for holding in holdings:
        holding["weight"] = (holding["value"] / total * 100.0) if total > 0 else 0.0

    holdings.sort(key=lambda h: h["value"], reverse=True)

    return {
        "filingDate": filing_date,
        "reportDate": _estimate_report_date(filing_date),
        "totalValue": total,
        "holdings": holdings,
    }


def _find_information_table_url(html: str) -> str | None:
    """
    공시 문서 목록에서 종목 테이블(information table) XML을 고릅니다.
    primary_doc.xml은 표지 문서라 제외해야 합니다.
    """
    soup = BeautifulSoup(html, "html.parser")
    table = soup.find("table", class_="tableFile")
    fallback = None

    if table:
        for row in table.find_all("tr")[1:]:
            cols = row.find_all("td")
            if len(cols) < 4:
                continue
            anchor = cols[2].find("a", href=True)
            if not anchor or not anchor.text.strip().lower().endswith(".xml"):
                continue

            href = anchor["href"]
            full = href if href.startswith("http") else f"https://www.sec.gov{href}"
            doc_type = cols[3].text.strip().lower()
            file_name = anchor.text.strip().lower()

            if (
                "information table" in doc_type
                or "infotable" in doc_type
                or "infotable" in file_name
                or "information" in file_name
            ):
                return full
            if fallback is None and "primary_doc" not in file_name:
                fallback = full

    if fallback:
        return fallback

    for anchor in soup.find_all("a", href=True):
        href = anchor["href"]
        lowered = href.lower()
        if lowered.endswith(".xml") and "primary_doc" not in lowered:
            return href if href.startswith("http") else f"https://www.sec.gov{href}"
    return None


def _parse_information_table(content: bytes, filing_date: str) -> list[dict]:
    """
    네임스페이스를 무시하고 infoTable 노드를 훑습니다.
    (제출자마다 네임스페이스 접두사가 달라 고정할 수 없습니다.)
    """
    try:
        root = ET.fromstring(content)
    except ET.ParseError as exc:
        logger.warning("13F XML 파싱 에러 (%s): %s", filing_date, exc)
        return []

    merged: dict[tuple[str, str, str], dict] = {}

    for node in root.iter():
        if not node.tag.lower().endswith("infotable"):
            continue

        name = cusip = title_class = ""
        value = shares = 0.0

        for child in node.iter():
            tag = child.tag.lower()
            text = (child.text or "").strip()
            if tag.endswith("nameofissuer"):
                name = text
            elif tag.endswith("titleofclass"):
                title_class = text
            elif tag.endswith("cusip"):
                cusip = text
            elif tag.endswith("value"):
                value = _to_float(text)
            elif tag.endswith("sshprnamt"):
                shares = _to_float(text)

        if not name or value <= 0:
            continue

        key = (name.strip().upper(), cusip.strip(), title_class.strip())
        entry = merged.setdefault(key, {
            "name": key[0],
            "cusip": key[1],     # 문자열 유지 — 숫자로 바꾸면 앞자리 0이 사라집니다
            "class": key[2],
            "value": 0.0,
            "shares": 0.0,
            "weight": 0.0,
        })
        entry["value"] += value
        entry["shares"] += shares

    return list(merged.values())


def _estimate_report_date(filing_date: str) -> str:
    """제출일에서 가장 가까운 직전 분기말을 추정합니다."""
    try:
        filed = datetime.strptime(filing_date, "%Y-%m-%d")
    except ValueError:
        return filing_date

    year, month = filed.year, filed.month
    if month <= 2:
        return f"{year - 1}-12-31"
    if month <= 5:
        return f"{year}-03-31"
    if month <= 8:
        return f"{year}-06-30"
    if month <= 11:
        return f"{year}-09-30"
    return f"{year}-12-31"


def _to_float(text: str) -> float:
    try:
        return float(str(text).replace(",", "").strip()) if text else 0.0
    except (TypeError, ValueError):
        return 0.0


def _fail(cik: str, message: str) -> dict:
    return {"cik": str(cik), "error": message, "quarters": []}
