"""
app/services/fsc.py
금융위원회 주식시세정보 — 국내 상장 종목 일별 공식 시세(종가·시가총액).

왜 필요한가 — 수급 레이더의 가격은 포털·증권사 API에서 장중에 받은 값입니다.
금융위 시세는 거래소 확정치를 기준일 단위로 주므로 시가총액의 기준이 되고,
DART 재무와 합쳐 PER·PBR을 계산할 수 있습니다.

갱신 — 기준일 다음 영업일 오후 1시 이후 (금요일 데이터는 월요일에).
그래서 "오늘"을 조회하지 않고 어제부터 거슬러 올라가며 데이터가 있는 날을 찾습니다.

응답 필드 (실제로 동작하는 코드가 읽는 필드와 공개된 필드 설명 기준)
  basDt 기준일(YYYYMMDD) · srtnCd 단축코드 · isinCd · itmsNm 종목명 · mrktCtg 시장구분
  clpr 종가 · vs 대비 · fltRt 등락률 · trqu 거래량 · trPrc 거래대금
  lstgStCnt 상장주식수 · mrktTotAmt 시가총액
  srtnCd가 "A005930"처럼 A 접두어로 오는 경우가 있어 떼어 냅니다.

주소·필드 — 금융위원회_주식시세정보 오픈API 활용가이드(GetStockSecuritiesInfoService_V2)의
"주식시세" 상세기능 `getStockPriceInfo_V2`. 응답 예시는 tests/fixtures/public에 있습니다.
(처음에는 오퍼레이션 이름을 확인하지 못해 `getStockPriceInfo`로 불렀고, 실제 호출에서
NO_OPENAPI_SERVICE_ERROR(코드 12)가 나와 활용가이드로 바로잡았습니다.)
FSC_STOCK_PRICE_URL로 주소를 직접 지정할 수 있습니다.
"""
from __future__ import annotations

import os
import re

from .. import publicapi, settings

URL = "https://apis.data.go.kr/1160100/GetStockSecuritiesInfoService_V2/getStockPriceInfo_V2"

# 한 페이지 행 수와 최대 페이지. 상장 종목은 KOSPI·KOSDAQ·KONEX 합쳐 3천 개 안팎입니다.
ROWS_PER_PAGE = 1000
MAX_PAGES = 5

_CODE = re.compile(r"^A?(\d{6})$")


def endpoint() -> str:
    """호출할 주소. FSC_STOCK_PRICE_URL이 있으면 그것을 씁니다."""
    return os.environ.get("FSC_STOCK_PRICE_URL", "").strip() or URL


def fetch_day(bas_dt: str, budget: publicapi.CallBudget) -> tuple[list[dict], str]:
    """
    그 기준일의 전 종목 시세.

    :param bas_dt: 기준일 YYYYMMDD
    :param budget: 호출 예산. 페이지마다 하나씩 씁니다
    :returns: (정규화한 행 목록, 호출한 주소). 그날 데이터가 없으면 ([], 주소)
    :raises publicapi.MissingKey: DATA_GO_KR_SERVICE_KEY 미설정
    :raises publicapi.PublicApiError: 인증·서비스 오류, 예산 부족
    """
    url = endpoint()
    return _fetch_pages(url, bas_dt, settings.data_go_kr_key(), budget), url


def _fetch_pages(url: str, bas_dt: str, key: str, budget: publicapi.CallBudget) -> list[dict]:
    rows: list[dict] = []
    for page in range(1, MAX_PAGES + 1):
        if not budget.take():
            raise publicapi.PublicApiError("호출 예산 소진")
        response = publicapi.get(
            url, {"basDt": bas_dt, "numOfRows": ROWS_PER_PAGE, "pageNo": page}, key=key, timeout=30,
        )
        root = publicapi.parse_xml(response, key=key)
        items = publicapi.xml_items(root)
        rows.extend(normalize(item) for item in items)
        total = publicapi.total_count(root) or 0
        if not items or len(rows) >= total:
            break
    else:
        raise publicapi.PublicApiError(f"{MAX_PAGES}페이지를 넘었습니다 — 행 수가 예상보다 많습니다")
    return [row for row in rows if row["code"]]


def _number(text: str | None) -> float | None:
    if text is None:
        return None
    cleaned = text.replace(",", "").strip()
    try:
        return float(cleaned) if cleaned not in ("", "-") else None
    except ValueError:
        return None


def normalize(item: dict) -> dict:
    """
    응답 한 행을 저장 형태로 옮깁니다. 숫자가 아니면 None (0으로 채우지 않음).

    :returns: ``{"code", "name", "market", "close", "change", "changePct", "volume",
              "tradingValue", "listedShares", "marketCap", "basDt"}``
    """
    match = _CODE.match((item.get("srtnCd") or "").strip())
    return {
        "code": match.group(1) if match else None,
        "name": (item.get("itmsNm") or "").strip() or None,
        "market": (item.get("mrktCtg") or "").strip() or None,
        "close": _number(item.get("clpr")),
        "change": _number(item.get("vs")),
        "changePct": _number(item.get("fltRt")),
        "volume": _number(item.get("trqu")),
        "tradingValue": _number(item.get("trPrc")),
        "listedShares": _number(item.get("lstgStCnt")),
        "marketCap": _number(item.get("mrktTotAmt")),
        "basDt": (item.get("basDt") or "").strip() or None,
    }


def market_totals(rows: list[dict]) -> dict[str, dict]:
    """
    시장별 합계: 시가총액·거래대금·종목 수.

    시장구분(mrktCtg)이 없는 행은 "기타"로 모읍니다. 합계에 None을 0으로 섞지
    않도록, 값이 있는 행만 더하고 몇 행이 빠졌는지 함께 남깁니다. 한 행도 값이
    없으면 합계는 0.0이 아니라 None입니다 — 0.0은 timeseries에 "시가총액 0원"으로
    쌓여 화면 차트가 0으로 떨어집니다.
    """
    out: dict[str, dict] = {}
    for row in rows:
        market = row.get("market") or "기타"
        agg = out.setdefault(market, {"count": 0, "marketCap": None, "tradingValue": None, "missingCap": 0})
        agg["count"] += 1
        if row.get("marketCap") is None:
            agg["missingCap"] += 1
        else:
            agg["marketCap"] = (agg["marketCap"] or 0.0) + row["marketCap"]
        if row.get("tradingValue") is not None:
            agg["tradingValue"] = (agg["tradingValue"] or 0.0) + row["tradingValue"]
    return out
