"""
tests/test_sec13f.py
SEC EDGAR 13F 목록·문서 파싱.

네트워크를 쓰지 않습니다. 목록 HTML은 EDGAR 'browse-edgar?action=getcompany&type=13F-HR'
페이지의 tableFile2 구조를 그대로 흉내 냅니다.
"""
from __future__ import annotations

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
