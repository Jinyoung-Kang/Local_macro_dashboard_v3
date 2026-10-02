"""
tests/test_radar_parsers.py
Daum·Naver 응답 파서 — 지금까지 fetch와 붙어 있어 테스트가 없던 부분.
"""
from __future__ import annotations

from app.services import radar


def _daum_payload(rows: list[dict], **extra) -> dict:
    return {"data": {"BUY": rows, "SELL": []}, "toDate": "2026-09-11", **extra}


def test_daum_파서는_코드_접두어와_비율_단위를_정규화한다():
    rows = [
        {"symbolCode": "A005930", "name": "삼성전자", "tradePrice": 71000, "changeRate": 0.0238,
         "straightPurchasePrice": 123_450_000_000},
        {"symbolCode": "A000660", "name": "SK하이닉스", "tradePrice": 180000, "changeRate": -0.011,
         "straightPurchasePrice": 98_700_000_000},
        "not a dict",
        {"symbolCode": "", "name": "코드없음", "straightPurchasePrice": 1},
    ]
    records = radar.parse_daum_ranking(_daum_payload(rows), investor="외국인", trade_type="순매수",
                                       top_n=30, target_date="20260911")

    assert [r["code"] for r in records] == ["005930", "000660"]          # 금액 내림차순, 접두어 A 제거
    assert records[0]["rank"] == 1
    assert records[0]["changePct"] == 2.38                                # 0.0238 → %
    assert records[0]["netAmountEok"] == 1234.5                           # 원 → 억
    assert records[0]["dataDate"] == "2026-09-11"                         # 소스가 밝힌 기준일
    assert "Daum API (외국인, 당일, 2026-09-11)" == records[0]["source"]


def test_daum_순매도는_부호를_방향에_맞춘다():
    payload = {"data": {"BUY": [], "SELL": [
        {"symbolCode": "A005930", "name": "삼성전자", "straightPurchasePrice": 50_000_000_000}]}}
    records = radar.parse_daum_ranking(payload, investor="외국인", trade_type="순매도",
                                       top_n=30, target_date="20260911")
    assert records[0]["netAmountEok"] == -500.0
    assert records[0]["dataDate"] == "2026-09-11"       # toDate가 없으면 요청한 거래일을 기준일로


def test_daum_기간별_조회는_dataDate를_달지_않는다():
    payload = _daum_payload([{"symbolCode": "A005930", "name": "삼성전자", "straightPurchasePrice": 1e9}],
                            fromDate="2026-09-05")
    records = radar.parse_daum_ranking(payload, investor="외국인", trade_type="순매수",
                                       top_n=30, target_date="20260911", interval_type="DAYS_5")
    assert records[0]["dataDate"] is None
    assert "5거래일, 2026-09-05~2026-09-11" in records[0]["source"]


def test_daum_빈_응답과_이상한_응답은_빈_목록():
    assert radar.parse_daum_ranking({}, investor="외국인", trade_type="순매수", top_n=30, target_date="x") == []
    assert radar.parse_daum_ranking(None, investor="외국인", trade_type="순매수", top_n=30, target_date="x") == []  # type: ignore[arg-type]


def _naver_html(rows: list[tuple[str, str, str, str, str]]) -> str:
    body = "".join(
        f"<tr><td>{i}</td><td><a href='/item/main.naver?code={code}'>{name}</a></td>"
        f"<td>{price}</td><td>{amount}</td><td>{change}</td><td>x</td><td>y</td><td>{amount}</td></tr>"
        for i, (code, name, price, change, amount) in enumerate(rows, start=1)
    )
    return f"<table class='type_1'><tr><th>h</th></tr>{body}</table>"


def test_naver_파서는_표에서_코드_가격_등락률_금액을_읽는다():
    html = _naver_html([("005930", "삼성전자", "71,000", "+2.38%", "123,450"),
                        ("000660", "SK하이닉스", "180,000", "-1.10%", "98,700")])
    records, reason = radar.parse_naver_ranking(html, trade_type="순매수", top_n=30, target_date="20260911")

    assert reason is None
    assert [r["code"] for r in records] == ["005930", "000660"]
    assert records[0]["price"] == 71000.0 and records[0]["changePct"] == 2.38
    assert records[0]["netAmountEok"] == 1234.5          # 백만 원 단위 표 → 억


def test_naver_표가_없거나_행이_없으면_사유를_돌려준다():
    records, reason = radar.parse_naver_ranking("<html><body>blocked</body></html>", trade_type="순매수",
                                                 top_n=30, target_date="20260911")
    assert records == [] and "표가 없습니다" in reason

    records, reason = radar.parse_naver_ranking("<table class='type_1'><tr><th>h</th></tr></table>",
                                                 trade_type="순매수", top_n=30, target_date="20260911")
    assert records == [] and "종목 행이 없습니다" in reason
