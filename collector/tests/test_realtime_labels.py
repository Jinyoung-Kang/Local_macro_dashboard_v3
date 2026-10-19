"""
tests/test_realtime_labels.py
매크로 카드의 지연 배지(note)가 출처가 밝힌 실제 지연과 같은지(QA-137)와, 같은 종목의 실시간 시세가 있으면
그것으로 바꾸는 규칙(QA-138).

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


# ==============================================================================
# QA-138 — 같은 종목의 실시간 시세가 있으면 그것을 쓴다
# ==============================================================================
# 2026-10-19 20:34 KST TradingView 공개 응답: TVC:DXY·FX_IDC:JPYKRW·KRX:KOSPI·TVC:NI225·TVC:HSI가 모두
# update_mode "streaming"(실시간)이었습니다. Yahoo의 같은 종목은 DXY 10분, 코스피·닛케이 20분, 항셍 15분 지연이고,
# 엔/원(JPYKRW=X)은 지연은 없지만 호가가 드문드문 와 마지막 분봉이 3분 넘게 지난 일이 잦았습니다.
def _yahoo_cards(*keys: str) -> dict:
    """카탈로그 그대로의 Yahoo 카드(일봉 두 개)."""
    specs = [spec for category in indicators.MACRO_CATEGORIES for spec in category["items"] if spec["key"] in keys]
    return {"categories": [{"id": "test", "items": [market._build_card(spec, _daily(spec["ticker"], "5d")) for spec in specs]}]}


def _by_key(payload: dict) -> dict:
    return {item["key"]: item for category in payload["categories"] for item in category["items"]}


def _streaming(key: str, symbol: str, price: float, previous: float, session: str, trade_date: str,
               pricescale: int = 100, **extra) -> dict:
    return _tv(key, price, previous, symbol=symbol, delayMinutes=0, session=session, tradeDate=trade_date,
               pricescale=pricescale, **extra)


def test_QA138_실시간_시세가_있으면_TradingView_값으로_바꾼다(fake_store):
    snapshots, _saved = fake_store
    snapshots[catalog.SNAP_SCRAPER_MARKETS] = _snapshot(catalog.SNAP_SCRAPER_MARKETS, _scraped(
        _streaming("dxy", "TVC:DXY", 101.818, 101.779, "market", "2026-10-19", pricescale=1000),
        _streaming("jpykrw", "FX_IDC:JPYKRW", 8.387, 8.426, "market", "2026-10-19", pricescale=1000),
        _streaming("kospi", "KRX:KOSPI", 5768.01, 5668.05, "out_of_session", "2026-10-19"),
        _streaming("hang_seng", "TVC:HSI", 24186.84, 24062.73, "out_of_session", "2026-10-16"),
    ))

    cards = _by_key(macro_cards.apply_realtime_quotes(_yahoo_cards("dxy", "jpykrw", "kospi", "hsi")))

    dxy = cards["dxy"]
    assert (dxy["price"], dxy["priceStr"], dxy["prevValue"]) == (pytest.approx(101.818), "101.82", pytest.approx(101.779))
    assert dxy["note"] == "실시간"
    assert dxy["source"] == "TradingView (TVC:DXY)"
    assert dxy["lastTs"] == "2026-10-19 20:35 KST (TradingView 수집 시각)"
    assert dxy["deltaStr"] == "+0.04 (+0.04%)"
    # 엔/원은 1엔당(8.387)으로 옵니다 — 카드는 100엔당. 원본이 소수 셋째 자리라 100배 하면 첫째 자리까지만 의미가 있습니다.
    assert (cards["jpykrw"]["price"], cards["jpykrw"]["priceStr"]) == (pytest.approx(838.7), "838.7")
    # 장이 닫힌 시장은 수집 시각 대신 그 값의 거래일을 적습니다 — 홍콩 휴장일(10-19)에 금요일 종가를 오늘 값처럼 보이지 않게.
    assert cards["kospi"]["lastTs"] == "2026-10-19 거래일 기준 (TradingView · 장 밖)"
    assert cards["hsi"]["lastTs"] == "2026-10-16 거래일 기준 (TradingView · 장 밖)"
    assert {cards[key]["note"] for key in ("jpykrw", "kospi", "hsi")} == {"실시간"}


def _blocked(*_args, **_kw):
    raise RuntimeError("TradingView 차단")


def _old(name: str, payload: dict) -> store.Snapshot:
    from datetime import timedelta

    return store.Snapshot(name, payload, "json", "ok", None, datetime.now(timezone.utc) - timedelta(minutes=20))


@pytest.mark.parametrize("case", ["저장본 없음", "20분 지난 저장본", "조회 실패", "이어받은 옛 값", "TradingView도 지연"])
def test_QA138_실시간_값을_못_받으면_Yahoo_카드와_그_지연_배지를_둔다(fake_store, monkeypatch, case):
    snapshots, _saved = fake_store
    dxy = _streaming("dxy", "TVC:DXY", 101.818, 101.779, "market", "2026-10-19", pricescale=1000)
    if case == "20분 지난 저장본":
        snapshots[catalog.SNAP_SCRAPER_MARKETS] = _old(catalog.SNAP_SCRAPER_MARKETS, _scraped(dxy))
        # 오래된 저장본이면 실시간 대상만 바로 받아 봅니다 — 그것도 실패한 경우입니다.
        monkeypatch.setattr(macro_cards.scraper_service, "collect_quotes", _blocked)
    elif case != "저장본 없음":
        dxy.update({
            "조회 실패": {"status": "fail", "price": None},
            "이어받은 옛 값": {"isStale": True, "staleReason": "HTTP 429"},
            "TradingView도 지연": {"delayMinutes": 10},
        }[case])
        snapshots[catalog.SNAP_SCRAPER_MARKETS] = _snapshot(catalog.SNAP_SCRAPER_MARKETS, _scraped(dxy))

    card = _by_key(macro_cards.apply_realtime_quotes(_yahoo_cards("dxy")))["dxy"]

    assert card["source"] == "Yahoo Finance (DX-Y.NYB)"
    assert card["price"] == pytest.approx(101.0)
    assert card["note"] == _label(10)


@pytest.mark.parametrize("key, bar_time, session, trade_date", [
    # 일봉이 열린 시각(UTC) → 그 시장 시간대의 거래일. 2026-10-16 01:30 UTC = 홍콩 금요일 09:30 (10-19 월요일은 중양절 대체 휴장)
    ("hang_seng", 1792114200, "out_of_session", "2026-10-16"),
    # 2026-10-18 21:00 UTC = 뉴욕 일요일 17:00에 연 월요일 세션 — 저녁에 여는 세션은 거래일을 정하지 않습니다
    ("dxy", 1792357200, "market", None),
    ("kospi", 1792368000, "out_of_session", "2026-10-19"),
])
def test_QA138_TradingView_거래일과_장_상태를_남긴다(monkeypatch, key, bar_time, session, trade_date):
    monkeypatch.setattr(scraper, "get_session", lambda: _Session({
        "close": 24186.84, "change": 0.52, "change_abs": 124.11, "update_mode": "streaming",
        "current_session": session, "time": bar_time, "pricescale": 100,
    }))
    config = next(c for c in scraper.SCRAPER_MARKETS if c["key"] == key)

    item = scraper._collect_one(config)

    assert (item["session"], item["tradeDate"], item["symbol"]) == (session, trade_date, config["symbol"])


def test_QA138_실시간_대체는_같은_종목의_TradingView_시세만_가리킨다():
    scraped = {config["key"]: config for config in scraper.SCRAPER_MARKETS}
    macro = {spec["key"]: spec for category in indicators.MACRO_CATEGORIES for spec in category["items"]}
    assert set(indicators.REALTIME_QUOTES) == {"dxy", "jpykrw", "kospi", "nikkei", "hsi"}
    for card_key, source_key in indicators.REALTIME_QUOTES.items():
        assert card_key in macro
        assert scraped[source_key]["kind"] == "tradingview_symbol"
        assert scraped[source_key].get("tz"), f"{source_key}: 거래일을 계산할 시간대가 없음"


def test_QA138_TradingView_값을_쓰는_다른_카드도_장_밖이면_거래일을_적는다(fake_store, monkeypatch):
    # 2026-10-19 20:56 KST — 홍콩 휴장일이라 항셍 선물은 장 밖(값은 10-16 금요일), 코스피200 야간선물은 장중
    snapshots, _saved = fake_store
    snapshots[catalog.SNAP_SCRAPER_MARKETS] = _snapshot(catalog.SNAP_SCRAPER_MARKETS, _scraped(
        _tv("kospi200_night", 893.7, 893.25, delayMinutes=20, session="market", tradeDate="2026-10-20"),
        _tv("nikkei_fut", 64700.0, 64980.0, delayMinutes=15, session="market", tradeDate="2026-10-20"),
        _tv("hsi_fut", 24112.0, 24149.0, delayMinutes=15, session="out_of_session", tradeDate="2026-10-16"),
    ))
    futures = _by_key(macro_cards.inject_scraped_indices({"categories": [{"id": "asia_equity", "items": []}]}))
    assert futures["kospi200_night_scraped"]["lastTs"] == "2026-10-19 20:35 KST (TradingView 수집 시각)"
    assert futures["hsi_fut_scraped"]["lastTs"] == "2026-10-16 거래일 기준 (TradingView · 장 밖)"

    # 미국채 — 주말에 받은 금요일 수익률
    monkeypatch.setattr(market, "collect_ticker", _daily)
    monkeypatch.setattr(macro_cards.scraper_service, "collect_scraped_markets", lambda: _scraped(
        *(_tv(key, 4.7, 4.7, delayMinutes=0, session="out_of_session", tradeDate="2026-10-16")
          for key in ("us02y", "us10y", "us30y")),
    ))
    snapshots.pop(catalog.SNAP_SCRAPER_MARKETS)
    ust = next(c for c in indicators.MACRO_CATEGORIES if c["id"] == "ust")
    bonds = _by_key(macro_cards.apply_bond_override(market.collect_macro_cards([ust])))
    assert {card["lastTs"] for card in bonds.values()} == {"2026-10-16 거래일 기준 (TradingView · 장 밖)"}


def test_QA138_카드에_쓰는_TradingView_항목은_모두_시간대가_있다():
    used = set(indicators.REALTIME_QUOTES.values()) | set(indicators.BOND_SCANNER_KEYS) | {
        spec["key"] for spec in indicators.SCRAPED_FUTURES
    }
    missing = sorted(
        config["key"] for config in scraper.SCRAPER_MARKETS
        if config["key"] in used and config["kind"] == "tradingview_symbol" and not config.get("tz")
    )
    assert missing == []


def test_QA138_저녁에_여는_세션은_달력_날짜로_거래일을_지어내지_않는다():
    # 2026-10-19 20:59 KST 실측 — HKEX:HSI1!의 마지막 일봉은 금요일 17:15 HKT(야간 세션)에 열렸습니다.
    # 그 세션의 거래일은 다음 영업일이고 휴장일까지 겹쳐 달력만으로는 정할 수 없습니다. 모르면 비웁니다.
    assert scraper.trade_date(1792142100, "Asia/Hong_Kong") is None
    # 아침에 여는 일봉(지수)은 그날이 거래일입니다.
    assert scraper.trade_date(1792114200, "Asia/Hong_Kong") == "2026-10-16"   # 항셍 지수 금 09:30 HKT
    assert scraper.trade_date(1792368000, "Asia/Seoul") == "2026-10-19"       # 코스피 월 09:00 KST


def test_QA138_거래일을_모르는_장_밖_값은_수집_시각에_장_밖을_붙인다(fake_store):
    snapshots, _saved = fake_store
    snapshots[catalog.SNAP_SCRAPER_MARKETS] = _snapshot(catalog.SNAP_SCRAPER_MARKETS, _scraped(
        _tv("hsi_fut", 24112.0, 24149.0, delayMinutes=15, session="out_of_session", tradeDate=None),
    ))
    futures = _by_key(macro_cards.inject_scraped_indices({"categories": [{"id": "asia_equity", "items": []}]}))
    assert futures["hsi_fut_scraped"]["lastTs"] == "2026-10-19 20:35 KST (TradingView 수집 시각 · 장 밖)"


def test_QA138_라이브_갱신에서는_묵은_참고_시세_대신_실시간_대상만_바로_받는다(fake_store, monkeypatch):
    """
    화면이 1분 이하 자동 갱신을 고르면 백엔드는 매크로 카드(macro_collected)만 1분마다 맡기고(MAX_AGE_LIVE), 참고 시세는
    5분 스케줄 그대로입니다. 4분 전 참고 시세를 '실시간'으로 쓰면 Yahoo 카드는 1분마다 움직이는데 달러 인덱스만 4분 전
    값에 머뭅니다.
    """
    from datetime import timedelta

    snapshots, _saved = fake_store
    stale_dxy = _streaming("dxy", "TVC:DXY", 101.70, 101.779, "market", None, pricescale=1000)
    snapshots[catalog.SNAP_SCRAPER_MARKETS] = store.Snapshot(
        catalog.SNAP_SCRAPER_MARKETS, _scraped(stale_dxy), "json", "ok", None,
        datetime.now(timezone.utc) - timedelta(minutes=4))
    asked: list[set] = []

    def quotes(keys):
        asked.append(set(keys))
        return {"dxy": _streaming("dxy", "TVC:DXY", 101.83, 101.779, "market", None, pricescale=1000,
                                  collectedAt="2026-10-19 21:04 KST")}

    monkeypatch.setattr(macro_cards.scraper_service, "collect_quotes", quotes)

    card = _by_key(macro_cards.apply_realtime_quotes(_yahoo_cards("dxy")))["dxy"]

    assert asked == [set(indicators.REALTIME_QUOTES.values())]
    assert card["price"] == pytest.approx(101.83)
    assert card["lastTs"] == "2026-10-19 21:04 KST (TradingView 수집 시각)"
