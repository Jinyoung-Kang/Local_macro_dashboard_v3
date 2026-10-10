"""
tests/test_sec13f.py
SEC EDGAR 13F 목록·문서 파싱.

네트워크를 쓰지 않습니다. 목록 HTML은 EDGAR 'browse-edgar?action=getcompany&type=13F-HR'
페이지의 tableFile2 구조를 그대로 흉내 냅니다.
"""
from __future__ import annotations

import pytest

from app.services import sec13f


def _row(filing_type: str, href: str, filed: str) -> str:
    return (
        f"<tr><td>{filing_type}</td>"
        f"<td><a href=\"{href}\">Documents</a></td>"
        f"<td>desc</td><td>{filed}</td><td>file</td></tr>"
    )


def _listing(*rows: str) -> str:
    return (
        "<html><body><table class=\"tableFile2\">"
        "<tr><th>Filings</th><th>Format</th><th>Description</th><th>Filing Date</th><th>File</th></tr>"
        + "".join(rows)
        + "</table></body></html>"
    )


def test_정정_공시는_분기로_세지_않는다():
    """
    EDGAR의 type=13F-HR 목록에는 정정(13F-HR/A)도 섞여 옵니다. 그걸 분기로 세면
    (1) 같은 reportDate가 두 번 들어가 q8이 실제로는 7개 분기가 되고, (2) 가장
    최근 공시가 '신규 보유분만' 담은 부분 정정이면 q1이 부분 포트폴리오가 되어
    비중이 그 부분 합계로 정규화됩니다. 원본 13F-HR만 분기로 씁니다.
    """
    html = _listing(
        _row("13F-HR/A", "/Archives/a1-index.htm", "2026-08-20"),
        _row("13F-HR", "/Archives/q2-index.htm", "2026-08-14"),
        _row("13F-HR/A", "/Archives/a2-index.htm", "2026-06-02"),
        _row("13F-HR", "/Archives/q1-index.htm", "2026-05-15"),
        _row("SC 13G", "/Archives/other.htm", "2026-04-01"),
    )

    links = sec13f._parse_filing_links(html, 8)

    assert links == [
        ("2026-08-14", "https://www.sec.gov/Archives/q2-index.htm"),
        ("2026-05-15", "https://www.sec.gov/Archives/q1-index.htm"),
    ]


def test_max_quarters만큼만_고른다():
    html = _listing(*[
        _row("13F-HR", f"/Archives/q{i}-index.htm", f"2026-0{i}-15") for i in range(1, 6)
    ])
    assert len(sec13f._parse_filing_links(html, 2)) == 2


def test_같은_종목의_여러_클래스는_따로_남는다():
    """CUSIP·종류가 다르면 같은 이름이라도 다른 보유분입니다(백엔드가 CUSIP으로 비교)."""
    xml = b"""<?xml version="1.0"?>
    <informationTable xmlns="http://www.sec.gov/edgar/document/thirteenf/informationtable">
      <infoTable><nameOfIssuer>ALPHABET INC</nameOfIssuer><titleOfClass>CL A</titleOfClass>
        <cusip>02079K305</cusip><value>1000</value><shrsOrPrnAmt><sshPrnamt>10</sshPrnamt></shrsOrPrnAmt></infoTable>
      <infoTable><nameOfIssuer>ALPHABET INC</nameOfIssuer><titleOfClass>CL C</titleOfClass>
        <cusip>02079K107</cusip><value>2000</value><shrsOrPrnAmt><sshPrnamt>20</sshPrnamt></shrsOrPrnAmt></infoTable>
      <infoTable><nameOfIssuer>ALPHABET INC</nameOfIssuer><titleOfClass>CL A</titleOfClass>
        <cusip>02079K305</cusip><value>500</value><shrsOrPrnAmt><sshPrnamt>5</sshPrnamt></shrsOrPrnAmt></infoTable>
    </informationTable>"""

    holdings = sec13f._parse_information_table(xml, "2026-08-14")

    by_cusip = {h["cusip"]: h for h in holdings}
    assert set(by_cusip) == {"02079K305", "02079K107"}
    assert by_cusip["02079K305"]["value"] == 1500.0 and by_cusip["02079K305"]["shares"] == 15.0


# ---------------------------------------------------------------- QA-014
# 보고 분기를 제출 '월'로 추정해, 3·6·9·12월에 낸 공시가 아직 끝나지 않은 분기로 붙었습니다
# (예: 12월 2일 제출 → 같은 해 12-31). 늦게 낸 공시가 다음 분기 이름을 달면 백엔드의
# 분기별 중복 제거(dedupeByReportDate)가 진짜 다음 분기 공시와 겹쳐 한 분기를 버릴 수 있습니다.
# EDGAR 공시 색인 페이지에는 'Period of Report'가 있으므로 그 값을 씁니다.

_INDEX_HTML = (
    "<div class=\"formGrouping\">"
    "<div class=\"infoHead\">Filing Date</div>\n<div class=\"info\">2026-12-02</div>"
    "<div class=\"infoHead\">Accepted</div>\n<div class=\"info\">2026-12-02 16:05:04</div>"
    "</div><div class=\"formGrouping\">"
    "<div class=\"infoHead\">Period of Report</div>\n<div class=\"info\">2026-09-30</div>"
    "</div>"
)


def test_QA014_보고_분기는_공시_색인의_Period_of_Report를_쓴다():
    assert sec13f._parse_period_of_report(_INDEX_HTML) == "2026-09-30"


def test_QA014_추정은_제출일_전에_끝난_가장_최근_분기말이다():
    assert sec13f._estimate_report_date("2026-12-02") == "2026-09-30"
    assert sec13f._estimate_report_date("2026-03-20") == "2025-12-31"
    assert sec13f._estimate_report_date("2026-08-14") == "2026-06-30"


class _FakeResponse:
    text = "<html></html>"

    def raise_for_status(self):
        return None


class _FakeSession:
    def get(self, *_a, **_k):
        return _FakeResponse()


def _quarter(filing_date: str, report_date: str) -> dict:
    return {"filingDate": filing_date, "reportDate": report_date, "totalValue": 100.0,
            "holdings": [{"name": "APPLE INC", "cusip": "037833100", "class": "COM",
                          "value": 100.0, "shares": 1.0, "weight": 100.0}]}


@pytest.fixture()
def three_filings_middle_fails(monkeypatch):
    monkeypatch.setattr(sec13f, "get_sec_session", lambda: _FakeSession())
    monkeypatch.setattr(sec13f, "sec_rate_limit", lambda: None)
    monkeypatch.setattr(sec13f, "_parse_filing_links", lambda html, n: [
        ("2026-08-14", "u3"), ("2026-05-15", "u2"), ("2026-02-14", "u1")])
    done = {"2026-08-14": _quarter("2026-08-14", "2026-06-30"),
            "2026-05-15": None,                                   # 이 공시만 받지 못함
            "2026-02-14": _quarter("2026-02-14", "2025-12-31")}
    monkeypatch.setattr(sec13f, "_collect_one_filing", lambda session, filing_date, url: done[filing_date])


def test_QA031_공시_하나를_받지_못하면_분기가_밀린_결과를_내지_않는다(three_filings_middle_fails):
    """
    가운데 분기를 건너뛰면 0번(2026Q2)과 1번(2025Q4)이 '최신 대 직전'으로 비교됩니다. 직전 저장본에도 그 공시가 없으면
    실패로 보고(저장본 유지), 분기가 밀린 결과로 덮지 않습니다.
    """
    result = sec13f.collect_13f("0001067983", 3)

    assert result["quarters"] == []
    assert "받지 못" in result["error"]


def test_QA031_받지_못한_공시는_직전_저장본의_같은_공시를_이어받는다(three_filings_middle_fails):
    previous = [_quarter("2026-05-15", "2026-03-31")]

    result = sec13f.collect_13f("0001067983", 3, previous_quarters=previous)

    assert [q["reportDate"] for q in result["quarters"]] == ["2026-06-30", "2026-03-31", "2025-12-31"]
    assert result["quarters"][1]["isStale"] is True
    assert result["error"] is None


_INFO_TABLE = b"""<?xml version="1.0" encoding="UTF-8"?>
<informationTable xmlns="http://www.sec.gov/edgar/document/thirteenf/informationtable">
  <infoTable><nameOfIssuer>APPLE INC</nameOfIssuer><titleOfClass>COM</titleOfClass><cusip>037833100</cusip>
    <value>1000000</value><shrsOrPrnAmt><sshPrnamt>5000</sshPrnamt><sshPrnamtType>SH</sshPrnamtType></shrsOrPrnAmt></infoTable>
  <infoTable><nameOfIssuer>APPLE INC</nameOfIssuer><titleOfClass>COM</titleOfClass><cusip>037833100</cusip>
    <value>4000000</value><shrsOrPrnAmt><sshPrnamt>20000</sshPrnamt><sshPrnamtType>SH</sshPrnamtType></shrsOrPrnAmt>
    <putCall>Put</putCall></infoTable>
  <infoTable><nameOfIssuer>TESLA INC</nameOfIssuer><titleOfClass>NOTE 2.000% 5/1</titleOfClass><cusip>88160RAG6</cusip>
    <value>2000000</value><shrsOrPrnAmt><sshPrnamt>1500000</sshPrnamt><sshPrnamtType>PRN</sshPrnamtType></shrsOrPrnAmt></infoTable>
</informationTable>"""


@pytest.mark.xfail(strict=True, reason="QA-033: 옵션 행을 보유에 합치고 채권 원금을 주식 수로 셈")
def test_QA033_옵션_행은_보유가_아니고_채권_원금은_주식_수가_아니다():
    """
    13F 정보표의 putCall 행은 옵션(기초자산 평가액)이라 롱 보유가 아닙니다. 합치면 풋이 보유로 보이고 비중 분모가
    부풉니다(추적 기관 중 사이언·브리지워터 등이 보고). PRN 행의 sshPrnamt는 채권 원금이라 주식 수로 쓰지 않습니다.
    """
    holdings = {h["name"]: h for h in sec13f._parse_information_table(_INFO_TABLE, "2026-08-14")}

    assert holdings["APPLE INC"]["value"] == 1_000_000
    assert holdings["APPLE INC"]["shares"] == 5000
    assert holdings["TESLA INC"]["value"] == 2_000_000
    assert holdings["TESLA INC"]["shares"] is None
