"""
tests/test_realtime_labels.py
매크로 카드의 지연 배지(note)가 출처가 밝힌 실제 지연과 같은지(QA-137).

2026-10-19 20:22 KST 화면에서 '실시간' 배지를 단 달러 인덱스 카드의 시각이 20:11이었습니다. Yahoo의 DX-Y.NYB는
ICE 선물 시세라 10분 늦게 오는데, 배지는 카탈로그에 손으로 적은 '실시간'이었습니다. 같은 식으로 선물(실제 10분)은
'15분 지연', 코스피·닛케이(실제 20분)도 '15분 지연', S&P 500·나스닥 100 지수(실제 지연 없음)도 '15분 지연'이었습니다.

외부 네트워크를 쓰지 않습니다. Yahoo가 밝힌 지연(exchangeDataDelayedBy)은 2026-10-19에 한 번 조회해
fixtures/yahoo_declared_delays.json에 고정했고, TradingView 응답은 그날 받은 모양을 그대로 흉내 냅니다.
"""
from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path

import pytest

from app import catalog, indicators, macro_cards, store, tasks
from app.services import market, scraper

FIXTURES = Path(__file__).parent / "fixtures"
DECLARED = {
    ticker: entry["minutes"]
    for ticker, entry in json.loads((FIXTURES / "yahoo_declared_delays.json").read_text())["delays"].items()
}
MIXED = "종목마다 다름 — 카드 배지 참고"


def _label(minutes: int) -> str:
    """기대 문구는 테스트가 따로 적습니다 — 구현의 문구 함수를 그대로 쓰면 둘이 함께 틀려도 통과합니다."""
    return "실시간" if minutes == 0 else f"{minutes}분 지연"


def _daily(symbol: str, period: str) -> dict:
    """일봉 두 개 — 바로 앞 봉이 전일 종가라 추가 조회(네트워크) 없이 카드가 완성됩니다."""
    return {
        "symbol": symbol, "period": period, "isIntraday": False, "isProxy": False,
        "points": [{"date": "2026-10-16", "close": 100.0}, {"date": "2026-10-19", "close": 101.0}],
    }


def _snapshot(name: str, payload: dict) -> store.Snapshot:
    return store.Snapshot(name, payload, "json", "ok", None, datetime.now(timezone.utc))


@pytest.fixture()
def fake_store(monkeypatch):
    """DB 대신 사전 — 읽을 저장본을 넣어 두고, 태스크가 저장한 값을 꺼내 봅니다."""
    snapshots: dict[str, store.Snapshot] = {}
    saved: dict[str, dict] = {}
    monkeypatch.setattr(store, "read_snapshot", lambda name: snapshots.get(name))
    monkeypatch.setattr(store, "put_snapshot", lambda name, payload, **_kw: saved.__setitem__(name, payload))
    return snapshots, saved


class _Response:
    def __init__(self, payload: dict):
        self._payload = payload

    def raise_for_status(self) -> None:
        return None

    def json(self) -> dict:
        return self._payload


class _Session:
    def __init__(self, payload: dict):
        self.payload = payload

    def get(self, _url, params=None, timeout=None):
        return _Response(self.payload)


def _scraped(*items: dict) -> dict:
    return {"updatedAt": "2026-10-19 20:35 KST", "items": list(items)}


def _tv(key: str, price: float, previous: float, **extra) -> dict:
    return {"key": key, "status": "ok", "price": price, "previousClose": previous,
            "provider": "TradingView Scanner", "collectedAt": "2026-10-19 20:35 KST", **extra}


# ==============================================================================
# QA-137 — 배지가 출처의 실제 지연과 같아야 한다
# ==============================================================================
def test_QA137_Yahoo_카드의_지연_배지는_Yahoo가_밝힌_지연과_같다():
    wrong = {}
    for category in indicators.MACRO_CATEGORIES:
        for spec in category["items"]:
            card = market._build_card(spec, _daily(spec["ticker"], "5d"))
            expected = _label(DECLARED[spec["ticker"]])
            if card.get("note") != expected:
                wrong[spec["key"]] = f"{card.get('note')} → {expected}"
    assert wrong == {}


def test_QA137_카테고리_부제는_종목_배지를_요약한다(fake_store, monkeypatch):
    """
    한 카테고리 안에서도 지연이 다릅니다(S&P 500 지수는 지연 없음, ES 선물은 10분). 부제를 고정 문구로 두면
    '데이터 지연: 15분 지연'처럼 어느 카드에도 맞지 않는 말이 됩니다.
    """
    _snapshots, saved = fake_store
    monkeypatch.setattr(market, "collect_ticker", _daily)

    def blocked():
        raise RuntimeError("TradingView 차단")

    # 국채 보정이 실패한 라운드 — 10·30년 카드는 Yahoo 값(15분 지연)으로 남고 2년물은 '수집 실패'입니다.
    monkeypatch.setattr(macro_cards.scraper_service, "collect_scraped_markets", blocked)

    tasks.task_macro_collected()

    subtitles = {category["id"]: category.get("note") for category in saved[catalog.SNAP_MACRO_COLLECTED]["categories"]}
    assert subtitles == {
        "fx": MIXED,                 # DXY 10분, 환율 셋은 지연 없음
        "ust": _label(15),           # 2년물은 실패(요약에서 뺌), ^TNX·^TYX 15분
        "commodity": _label(10),
        "us_equity": MIXED,          # 지수 0분, 선물 10분
        "asia_equity": MIXED,        # 코스피·닛케이 20분, 상하이·항셍 15분
    }


def test_QA137_TradingView가_알려_준_지연_분을_남긴다(monkeypatch):
    # 2026-10-19 20:34 KST 응답 그대로 — 코스피200 야간선물은 20분 지연 시세입니다.
    monkeypatch.setattr(scraper, "get_session", lambda: _Session({
        "close": 897.8, "change": 0.51, "change_abs": 4.55,
        "update_mode": "delayed_streaming_1200", "current_session": "market",
    }))
    config = next(c for c in scraper.SCRAPER_MARKETS if c["key"] == "kospi200_night")

    item = scraper._collect_one(config)

    assert item["status"] == "ok"
    assert item["delayMinutes"] == 20


def test_QA137_선물_카드_배지에_지연을_적는다(fake_store):
    snapshots, _saved = fake_store
    snapshots[catalog.SNAP_SCRAPER_MARKETS] = _snapshot(catalog.SNAP_SCRAPER_MARKETS, _scraped(
        _tv("kospi200_night", 897.8, 893.25, delayMinutes=20),
        _tv("nikkei_fut", 64750.0, 64980.0, delayMinutes=15),
        _tv("hsi_fut", 24112.0, 24149.0, delayMinutes=15),
    ))

    payload = macro_cards.inject_scraped_indices({"categories": [{"id": "asia_equity", "items": []}]})

    notes = {item["key"]: item["note"] for item in payload["categories"][0]["items"]}
    assert notes == {
        "kospi200_night_scraped": "20분 지연 · 참고 시세",
        "nikkei_fut_scraped": "15분 지연 · 참고 시세",
        "hsi_fut_scraped": "15분 지연 · 참고 시세",
    }


def test_QA137_국채_카드_배지는_실제로_쓴_출처의_지연을_적는다(monkeypatch):
    monkeypatch.setattr(market, "collect_ticker", _daily)
    monkeypatch.setattr(macro_cards.store, "read_snapshot", lambda name: None)
    monkeypatch.setattr(macro_cards.scraper_service, "collect_scraped_markets", lambda: _scraped(
        _tv("us02y", 4.715, 4.715, delayMinutes=0),
        {"key": "us10y", "status": "fail", "price": None, "provider": "TradingView Scanner"},
        _tv("us30y", 5.42, 5.426, delayMinutes=0),
    ))
    ust = next(c for c in indicators.MACRO_CATEGORIES if c["id"] == "ust")

    payload = macro_cards.apply_bond_override(market.collect_macro_cards([ust]))

    notes = {item["key"]: item["note"] for item in payload["categories"][0]["items"]}
    assert notes == {
        "us02y": "실시간 · TradingView 참고",
        "us10y": _label(15),          # Yahoo ^TNX 그대로 — TradingView라고 적으면 안 됩니다
        "us30y": "실시간 · TradingView 참고",
    }
