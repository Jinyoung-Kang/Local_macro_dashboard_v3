"""
tests/test_bond_override.py
국채 카드 보정(macro_cards.apply_bond_override) 회귀 테스트.

고정하는 규칙
  1. 보정한 수익률은 화면 카드와 스프레드 계산용 rates에 **함께** 반영된다.
  2. 그 대상은 2년·10년·30년 전부다. 하나라도 빠지면 화면의 해당
     스크래핑 패널이 "수집 실패"로 뜬다 — 수집은 성공했는데도.

DB를 쓰지 않습니다(저장본 읽기와 스크래핑을 모두 대체합니다).
"""
from __future__ import annotations

import pytest

from app import indicators, macro_cards


@pytest.fixture()
def no_snapshot(monkeypatch):
    """저장본이 없다고 보게 만들어, 보정이 스크래핑 결과만 쓰게 합니다."""
    monkeypatch.setattr(macro_cards.store, "read_snapshot", lambda *_args, **_kw: None)


def _payload() -> dict:
    return {
        "categories": [{
            "id": "rates",
            "title": "금리",
            "items": [
                {"key": "us02y", "name": "미국채 2년물", "ticker": "ZT=F",
                 "status": "ok", "price": 101.5},
                {"key": "us10y", "name": "미국채 10년물", "ticker": "^TNX",
                 "status": "ok", "price": 4.10},
                {"key": "us30y", "name": "미국채 30년물", "ticker": "^TYX",
                 "status": "ok", "price": 4.70},
            ],
        }],
    }


def _scraped(**overrides) -> dict:
    items = [
        {"key": "us02y", "status": "ok", "price": 3.55,
         "previousClose": 3.50, "provider": "TradingView Scanner"},
        {"key": "us10y", "status": "ok", "price": 4.11,
         "previousClose": 4.05, "provider": "TradingView Scanner"},
        {"key": "us30y", "status": "ok", "price": 4.73,
         "previousClose": 4.68, "provider": "TradingView Scanner"},
    ]
    for item in items:
        item.update(overrides.get(item["key"], {}))
    return {"updatedAt": "now", "items": items}


def test_every_bond_key_reaches_rates(no_snapshot, monkeypatch):
    monkeypatch.setattr(
        macro_cards.scraper_service, "collect_scraped_markets", lambda: _scraped()
    )

    result = macro_cards.apply_bond_override(_payload())

    # 카드 값이 선물 가격(101.5)이 아니라 실제 수익률로 바뀌어야 합니다.
    cards = {item["key"]: item for item in result["categories"][0]["items"]}
    assert cards["us02y"]["price"] == pytest.approx(3.55)

    # 그리고 같은 값이 스프레드 계산용 rates에도 들어가야 합니다.
    assert set(result["rates"]) == set(indicators.BOND_SCANNER_KEYS)
    assert result["rates"]["us30y"]["current"] == pytest.approx(4.73)
    assert result["rates"]["us30y"]["previous"] == pytest.approx(4.68)


def test_failed_scrape_leaves_key_out_of_rates(no_snapshot, monkeypatch):
    """보정에 실패한 만기는 rates에 넣지 않습니다(옛 값으로 위장 금지)."""
    monkeypatch.setattr(
        macro_cards.scraper_service,
        "collect_scraped_markets",
        lambda: _scraped(us30y={"status": "fail", "price": None}),
    )

    result = macro_cards.apply_bond_override(_payload())

    assert "us30y" not in result.get("rates", {})
    assert set(result["rates"]) == {"us02y", "us10y"}


def test_scrape_unavailable_does_not_pass_futures_price_off_as_a_yield(no_snapshot, monkeypatch):
    """
    2년물 카드의 yfinance 원본은 ZT=F(국채 **선물 가격**, ~100pt)입니다. 보정이 통째로
    실패하면 그 가격이 "미국채 2년물 수익률(%)" 라벨 아래 ok로 남아 101.5%처럼 읽혔고,
    AI 텍스트에도 그대로 들어갔습니다. 수익률을 못 구했으면 '수집 실패'입니다.
    10년·30년은 원본(^TNX·^TYX)이 이미 수익률이라 그대로 둡니다.
    """
    def boom():
        raise RuntimeError("TradingView 차단")

    monkeypatch.setattr(macro_cards.scraper_service, "collect_scraped_markets", boom)

    result = macro_cards.apply_bond_override(_payload())

    cards = {item["key"]: item for item in result["categories"][0]["items"]}
    assert cards["us02y"]["status"] == "fail"
    assert cards["us02y"]["price"] is None
    assert "선물 가격" in cards["us02y"]["note"]
    assert cards["us10y"]["status"] == "ok" and cards["us10y"]["price"] == pytest.approx(4.10)
    assert "rates" not in result


def test_single_key_override_failure_also_fails_the_futures_card(no_snapshot, monkeypatch):
    monkeypatch.setattr(
        macro_cards.scraper_service, "collect_scraped_markets",
        lambda: _scraped(us02y={"status": "fail", "price": None}),
    )

    result = macro_cards.apply_bond_override(_payload())

    cards = {item["key"]: item for item in result["categories"][0]["items"]}
    assert cards["us02y"]["status"] == "fail"
    assert set(result["rates"]) == {"us10y", "us30y"}


def _payload_with_rates() -> dict:
    """운영 모양 — collect_macro_cards는 카드 가격으로 rates도 채웁니다(us02y는 ZT=F 선물 가격)."""
    payload = _payload()
    payload["rates"] = {
        "us02y": {"current": 101.5, "previous": 101.4},
        "us10y": {"current": 4.10, "previous": 4.08},
        "us30y": {"current": 4.70, "previous": 4.66},
    }
    return payload


def _boom():
    raise RuntimeError("TradingView 차단")


@pytest.mark.parametrize("scrape", [
    _boom,
    lambda: _scraped(us02y={"status": "fail", "price": None}),
], ids=["scrape-exception", "us02y-fail"])
def test_QA075_보정하지_못한_선물_가격은_스프레드용_rates에서도_뺀다(no_snapshot, monkeypatch, scrape):
    """
    카드만 '수집 실패'로 바꾸고 rates.us02y에 ZT=F 가격(~101.5)을 남기면, 백엔드가 그것을 2년물 수익률로 읽어
    '실시간' 10Y−2Y를 4.10 − 101.5 = −97.4%p로 냅니다(QA-075). 10년·30년은 원본이 이미 수익률이라 남깁니다 —
    빼면 30Y−2Y 스크래핑 패널이 '수집 실패'로 뜨던 회귀(1e4d3be)가 되살아납니다.
    """
    monkeypatch.setattr(macro_cards.scraper_service, "collect_scraped_markets", scrape)

    result = macro_cards.apply_bond_override(_payload_with_rates())

    assert "us02y" not in result["rates"]
    assert result["rates"]["us10y"]["current"] is not None
    assert result["rates"]["us30y"]["current"] is not None


_STALE = {"isStale": True, "staleReason": "HTTP 429", "note": "⚠️ 이전 값", "collectedAt": "2026-10-11 09:00:00 KST"}


def test_QA030_이어받은_수익률은_이전_값으로_표시하고_그때_시각을_쓴다(no_snapshot, monkeypatch):
    """
    TradingView가 끊겨 scraper_markets가 직전 값을 이어받으면(isStale), 국채 카드는 그 값을 '현재 시각' 수집값으로
    적고 isStale을 잃었습니다 — 몇 시간 지난 금리가 지금 값처럼 화면·AI 텍스트·스프레드에 들어갔습니다.
    """
    monkeypatch.setattr(
        macro_cards.scraper_service, "collect_scraped_markets", lambda: _scraped(us10y=dict(_STALE))
    )

    result = macro_cards.apply_bond_override(_payload())

    card = {item["key"]: item for item in result["categories"][0]["items"]}["us10y"]
    assert card["isStale"] is True
    assert card["staleReason"] == "HTTP 429"
    assert "2026-10-11 09:00" in card["lastTs"]
    assert result["rates"]["us10y"]["isStale"] is True


class _Snap:
    collected_at = None

    def __init__(self, payload):
        self.payload = payload


def test_QA030_이어받은_지수_선물도_이전_값으로_표시한다(monkeypatch):
    items = [{"key": "kospi200_night", "status": "ok", "price": 350.0, "previousClose": 348.0,
              "provider": "TradingView Scanner", **_STALE}]
    monkeypatch.setattr(macro_cards.store, "read_snapshot", lambda *_a, **_k: _Snap({"items": items}))
    payload = {"categories": [{"id": indicators.SCRAPED_INJECT_CATEGORY, "title": "아시아", "items": []}]}

    result = macro_cards.inject_scraped_indices(payload)

    card = {item["key"]: item for item in result["categories"][0]["items"]}["kospi200_night_scraped"]
    assert card["isStale"] is True
    assert "2026-10-11 09:00" in card["lastTs"]
