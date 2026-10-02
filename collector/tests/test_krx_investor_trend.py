"""
tests/test_krx_investor_trend.py
Daum 투자주체별 선물 수급 — 모르는 값을 0계약으로 적지 않는지.
"""
from __future__ import annotations

from types import SimpleNamespace

from app.services import krx


def _trend(rows: list[dict]) -> dict:
    session = SimpleNamespace(get=lambda *a, **k: SimpleNamespace(status_code=200, json=lambda: {"data": rows}))
    krx.get_session = lambda: session   # noqa: E731 — 네트워크 대신 고정 응답
    return krx.collect_daum_futures_trend(lookback_days=5)


def _row(date: str, foreign, institution) -> dict:
    return {"date": date, "foreignSettlement": foreign, "institutionalSettlement": institution}


def test_모르는_값은_0이_아니라_None이고_창_합계도_모른다(monkeypatch):
    monkeypatch.setattr(krx, "get_session", lambda: None)   # 테스트 끝나면 원래대로
    rows = [
        _row("2026-09-30", None, 1200),      # 오늘 외국인 값 없음
        _row("2026-09-29", 500, -300),
        _row("2026-09-26", 400, 100),
        _row("2026-09-25", -100, 50),
        _row("2026-09-24", 200, 10),
    ]
    by_investor = {r["investor"]: r for r in _trend(rows)["rows"]}

    foreign = by_investor["외국인 (스마트머니)"]
    assert foreign["netToday"] is None
    assert foreign["net5d"] is None, "창 안에 모르는 날이 있으면 합계도 모른다"
    assert foreign["stance"] == "⚪ 판정 불가"

    inst = by_investor["기관계"]
    assert inst["netToday"] == 1200
    assert inst["net5d"] == 1200 - 300 + 100 + 50 + 10
    assert inst["stance"].startswith("🟢")


def test_값이_다_있으면_예전과_같다(monkeypatch):
    monkeypatch.setattr(krx, "get_session", lambda: None)
    rows = [_row("2026-09-30", -10, 5), _row("2026-09-29", -20, 5)]
    by_investor = {r["investor"]: r for r in _trend(rows)["rows"]}
    assert by_investor["외국인 (스마트머니)"]["net20d"] == -30
    assert by_investor["외국인 (스마트머니)"]["stance"].startswith("🔴")
