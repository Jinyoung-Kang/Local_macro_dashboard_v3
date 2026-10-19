"""
app/macro_cards.py
매크로 카드 보정 — 수집한 카드(market.collect_macro_cards)를 저장하기 전에 한 번 고칩니다.

[무엇을 하나]
- apply_bond_override: 미국채 카드를 TradingView Scanner의 실제 수익률로 바꿉니다.
  전일 종가가 없으면 FRED 공식 확정치로 보완합니다.
- apply_realtime_quotes: 같은 종목의 TradingView 실시간 시세가 있으면 Yahoo 지연 카드를 그 값으로
  바꿉니다(QA-138).
- inject_scraped_indices: yfinance가 비운 아시아 선물 카드를 스크래핑한 지수 값으로
  채웁니다(이름에 대체 사실을 적습니다).
- summarize_notes: 카테고리 부제(데이터 지연)를 종목 배지로 요약합니다(QA-137).

[왜 수집 시점인가] 화면만 따로 고치면 AI 텍스트·스프레드 계산은 고치기 전 값을
씁니다. 저장 직전에 한 번 고쳐 두면 모든 소비자가 같은 값을 봅니다.

tasks.py에 있던 코드를 그대로 옮겼습니다(CODE-05). 동작은 같습니다.
"""
from __future__ import annotations

import logging
import math

from . import catalog, indicators, kst, store
from .services import (
    fred as fred_service,
    market as market_service,
    scraper as scraper_service,
)

logger = logging.getLogger(__name__)


def apply_bond_override(payload: dict) -> dict:
    """
    미국채 카드를 TradingView Scanner의 실제 수익률로 보정합니다.

    [왜 필요한가] config의 2년물 티커는 ZT=F(2년 국채 **선물 가격**, ~100pt)라
    "수익률(%)" 라벨과 단위가 맞지 않습니다. 화면만 따로 보정하면 AI 텍스트와
    스프레드 계산은 보정되지 않은 값을 쓰게 되므로, **수집 시점에 한 번**
    보정해 이후 모든 소비자가 같은 값을 보게 합니다.

    전일 종가를 Scanner가 못 주면 FRED 공식 일별 확정치(DGS2/10/30)로
    보완하고, 출처가 다르다는 사실을 prevSource에 남깁니다. 어느 쪽도 없으면
    0.00%로 위장하지 않고 N/A로 둡니다.
    """
    snapshot = store.read_snapshot(catalog.SNAP_SCRAPER_MARKETS)
    scraped: dict[str, dict] = {}

    # 같은 수집 라운드에서 방금 저장된 값이 있으면 재사용합니다
    # (같은 실행 안에서 외부 스크래핑이 두 번 일어나는 낭비를 막습니다).
    if snapshot and snapshot.is_fresh(300) and snapshot.payload:
        scraped = {
            item["key"]: item
            for item in (snapshot.payload.get("items") or [])
            if isinstance(item, dict)
        }
    else:
        try:
            scraped = {
                item["key"]: item
                for item in scraper_service.collect_scraped_markets()["items"]
            }
        except Exception as exc:  # noqa: BLE001
            logger.warning("국채 보정용 스크래핑 실패: %s", exc)
            return _fail_unconverted_price_cards(payload, overridden=set())

    now_text = market_service.now_kst_text()
    overridden: set[str] = set()

    for category in payload["categories"]:
        for item in category["items"]:
            key = item.get("key")
            if key not in indicators.BOND_SCANNER_KEYS:
                continue

            source = scraped.get(key)
            if not source or source.get("status") != "ok":
                continue

            price = source.get("price")
            if price is None:
                continue

            price = float(price)
            overridden.add(key)
            item.update({
                "price": price,
                "priceStr": f"{price:,.3f}",
                "status": "ok",
                "source": source.get("provider", "TradingView Scanner"),
                # Scanner 응답에는 체결 시각이 없으므로 "수집 시각"임을 밝힙니다. 이어받은 값이면 그 값을 받은 시각,
                # 장이 닫혔으면 그 값의 거래일입니다(QA-138).
                "lastTs": _tradingview_last_ts(source, source.get("collectedAt") or now_text),
                # 배지는 실제로 쓴 출처(TradingView)의 지연입니다. 보정이 안 된 카드는 Yahoo 지연 배지 그대로입니다(QA-137).
                "note": _with_delay(source, "TradingView 참고"),
                **_stale_fields(source),
            })

            previous = source.get("previousClose")
            prev_source = "TradingView"

            if previous is None or float(previous) == 0:
                fred_prev = _bond_previous_from_fred(key)
                if fred_prev is not None:
                    previous = fred_prev
                    prev_source = "FRED 공식 확정치"

            if previous and float(previous) != 0:
                previous = float(previous)
                delta = price - previous
                pct = delta / previous * 100.0
                item.update({
                    "delta": delta,
                    "pct": pct,
                    "prevStr": f"{previous:,.3f}",
                    "prevValue": previous,
                    "deltaStr": f"{delta:+,.3f} ({pct:+.2f}%)",
                    "prevSource": prev_source,
                })
            else:
                item.update({
                    "delta": None, "pct": None,
                    "prevStr": "N/A", "prevValue": None, "deltaStr": "N/A",
                })

            # 보정된 값을 스프레드 계산용 rates에도 그대로 반영합니다.
            # 위 루프가 이미 BOND_SCANNER_KEYS만 통과시키므로, 여기서 키를
            # 다시 추리면 목록이 두 곳으로 갈라져 한쪽만 30년물을 빠뜨리게
            # 됩니다(그러면 30Y−2Y 스크래핑 패널이 "수집 실패"로 뜹니다).
            payload.setdefault("rates", {})[key] = {
                "current": item["price"],
                "previous": item.get("prevValue"),
                "isStale": bool(source.get("isStale")),
            }

    return _fail_unconverted_price_cards(payload, overridden)


# 실시간 대체에 쓸 참고 시세 저장본의 나이 상한. 같은 fast 라운드(5분)에서 방금 받은 것만 씁니다 — 그보다 오래된 값을
# '실시간'으로 적느니 Yahoo 지연 값과 그 배지를 둡니다.
REALTIME_MAX_AGE_SECONDS = 300


def apply_realtime_quotes(payload: dict) -> dict:
    """
    같은 종목의 TradingView 실시간 시세로 Yahoo 지연 카드를 바꿉니다(QA-138, 대상은 indicators.REALTIME_QUOTES).

    [왜] 2026-10-19 20:22 KST 화면의 달러 인덱스 카드가 '실시간' 배지에 20:11 값이었습니다 — Yahoo DX-Y.NYB는 ICE 선물
    시세라 10분 늦고, 코스피·닛케이는 20분, 항셍은 15분 늦습니다. TradingView 공개 시세는 같은 종목을 실시간으로 줍니다.

    방금 받은 참고 시세 저장본(services/scraper.py)만 읽고 외부를 새로 부르지 않습니다. 실시간 값이 아니면(저장본이 없거나
    오래됨, 조회 실패, 이어받은 옛 값, TradingView도 지연) 카드를 건드리지 않습니다 — Yahoo 값과 그 지연 배지가 남습니다.

    카드 시각: 장이 열려 있으면 수집 시각(값이 그 시각의 시세), 닫혀 있으면 그 값의 거래일입니다(_tradingview_last_ts). 장 밖에 수집 시각을 적으면
    홍콩 휴장일(10-19)에 금요일 종가가 오늘 20:35 값처럼 보입니다.
    """
    snapshot = store.read_snapshot(catalog.SNAP_SCRAPER_MARKETS)
    if not snapshot or not snapshot.payload or not snapshot.is_fresh(REALTIME_MAX_AGE_SECONDS):
        return payload
    scraped = {
        item["key"]: item
        for item in (snapshot.payload.get("items") or [])
        if isinstance(item, dict) and item.get("key")
    }

    for category in payload["categories"]:
        for item in category["items"]:
            source = scraped.get(indicators.REALTIME_QUOTES.get(item.get("key")))
            if not _usable(source) or source.get("isStale") or source.get("delayMinutes") != 0:
                continue
            item.update(_realtime_card(item["key"], source))
    return payload


def _realtime_card(key: str, source: dict) -> dict:
    """참고 시세 한 항목 → 카드에 덮어쓸 값. 엔/원 배율(1엔당 → 100엔당)은 Yahoo 카드와 같은 함수로 정합니다."""
    raw = float(source["price"])
    scale = market_service.quote_scale(key, raw)
    price = raw * scale
    digits = _display_digits(source.get("pricescale"), scale)

    card = {
        "status": "ok",
        "price": price,
        "priceStr": f"{price:,.{digits}f}",
        "note": indicators.delay_label(0),
        "source": f"TradingView ({source.get('symbol')})",
        "lastTs": _tradingview_last_ts(source, source.get("collectedAt")),
        "delta": None, "pct": None, "deltaStr": "N/A", "prevStr": "N/A", "prevValue": None,
        "prevSource": None,
    }
    previous = source.get("previousClose")
    if previous:
        previous = float(previous) * scale
        delta = price - previous
        pct = delta / previous * 100.0
        card.update({
            "delta": delta, "pct": pct,
            "deltaStr": f"{delta:+,.{digits}f} ({pct:+.2f}%)",
            "prevStr": f"{previous:,.{digits}f}", "prevValue": previous,
            "prevSource": "TradingView 직전 거래일 종가",
        })
    return card


def _tradingview_last_ts(source: dict, taken: str | None) -> str | None:
    """
    TradingView 값의 카드 시각(QA-138). 응답에 체결 시각이 없어, 장이 열려 있으면 수집 시각(그 시각의 시세)을 적고
    장 밖이면 그 값의 거래일(모르면 '수집 시각 · 장 밖')을 적습니다 — 장 밖에 수집 시각만 적으면 홍콩 휴장일의 항셍
    선물·주말의 미국채처럼 지난 거래일 값이 지금 값처럼 보입니다. 장 상태를 모르면 예전처럼 수집 시각입니다.
    """
    if source.get("session") not in (None, "market"):
        if source.get("tradeDate"):
            return f"{source['tradeDate']} 거래일 기준 (TradingView · 장 밖)"
        # 저녁에 여는 세션(선물 야간장·외환)은 거래일을 정할 수 없어 수집 시각에 장 밖임을 붙입니다.
        return f"{taken} (TradingView 수집 시각 · 장 밖)" if taken else None
    return f"{taken} (TradingView 수집 시각)" if taken else None


def _display_digits(pricescale: object, scale: float) -> int:
    """
    표시 소수 자리 — 카드는 둘째 자리까지지만, 원본이 그보다 거칠면 원본 자리까지만 적습니다. 엔/원은 1엔당 소수 셋째
    자리(pricescale 1000)로 와서 100엔당으로 바꾸면 첫째 자리까지만 의미가 있습니다 — 838.70처럼 적으면 없는 자리를 만듭니다.
    """
    if not isinstance(pricescale, (int, float)) or pricescale <= 0:
        return 2
    source_digits = round(math.log10(pricescale)) - round(math.log10(scale))
    return max(0, min(2, source_digits))


def _fail_unconverted_price_cards(payload: dict, overridden: set[str]) -> dict:
    """
    보정을 못 받은 선물 가격 카드(indicators.BOND_PRICE_ONLY_KEYS)를 '수집 실패'로 둡니다.

    ZT=F는 2년 국채 선물 **가격**(~100pt)입니다. 보정이 실패했을 때 그 값을 그대로 두면
    "미국채 2년물 수익률(%)" 라벨 아래 101.5가 ok로 남고, AI 텍스트와 스프레드 계산까지
    가격을 수익률로 읽습니다. 모르는 값은 모른다고 적습니다.

    스프레드 계산용 rates에서도 그 키를 뺍니다. collect_macro_cards가 카드 가격으로 rates를 채우므로,
    카드만 고치면 rates.us02y에 선물 가격이 남아 백엔드가 '실시간' 10Y−2Y를 −98%p로 냈습니다(QA-075).
    빠진 키는 task_macro_collected가 직전 저장본 값으로 이어받거나(isStale), 없으면 백엔드가 null('수집 실패')로 둡니다.
    10년·30년(^TNX·^TYX)은 원본이 이미 수익률이라 남깁니다.
    """
    rates = payload.get("rates")
    for category in payload.get("categories") or []:
        for item in category.get("items") or []:
            key = item.get("key")
            if key in indicators.BOND_PRICE_ONLY_KEYS and key not in overridden:
                item.update({
                    "status": "fail",
                    "price": None, "priceStr": "N/A",
                    "delta": None, "pct": None, "prevStr": "N/A", "prevValue": None, "deltaStr": "N/A",
                    "note": "수익률 보정 실패 — 원본(ZT=F)은 선물 가격이라 수익률로 쓰지 않습니다",
                })
    if isinstance(rates, dict):
        for key in indicators.BOND_PRICE_ONLY_KEYS:
            if key not in overridden:
                rates.pop(key, None)
    return payload


def _bond_previous_from_fred(key: str) -> float | None:
    """
    FRED 일별 확정치에서 직전 영업일 수익률을 읽습니다.

    FRED는 하루 지연 발표이므로 시리즈의 마지막 값이 곧 직전 거래일
    확정치입니다. 수집기가 이미 이 시리즈를 적재해 두므로 추가 네트워크
    비용도 없습니다.
    """
    series_id = indicators.BOND_FRED_FALLBACK.get(key)
    if not series_id:
        return None

    snapshot = store.read_snapshot(catalog.snap_fred_series(series_id))
    if snapshot and snapshot.payload:
        points = snapshot.payload.get("points") or []
        if points:
            value = points[-1].get("value")
            return float(value) if value and value > 0 else None

    points = fred_service.collect_series(series_id, period_years=1)
    value = fred_service.latest_value(points)
    return value if value and value > 0 else None


def inject_scraped_indices(payload: dict) -> dict:
    """
    아시아 지수 카테고리에 스크래핑 기반 참고 시세(선물)를 덧붙입니다.

    구버전은 코스피200 야간선물·닛케이225 선물·항셍 선물을 별도 스크래퍼로
    주입했습니다. 그 스크래퍼들은 TradingView HTML 정규식에 의존해 조용히
    깨지는 경로였으므로, 같은 값을 JSON으로 주는 Symbol Scanner 결과
    (services/scraper.py)로 대체합니다. 카드에는 출처와 추정 여부를 남깁니다.

    주의사항
      - ``lastTs``는 **수집 시각**입니다. Scanner 응답에는 체결 시각이 없습니다.
        시각이 없으면 카드만 보고는 몇 시 값인지 알 수 없어서, 미국채 카드와
        같은 방식으로 "(TradingView 수집 시각)"을 붙입니다. 장이 닫혔으면 그 값의
        거래일입니다(QA-138). 배지에는 TradingView가 알려 준 지연을 적습니다(QA-137).
      - 선물 조회가 실패하면 같은 거래소의 지수 값으로 내려가되, 이름과
        ``market``을 지수로 바꿉니다. 지수를 선물이라고 부르면 안 됩니다.
    """
    snapshot = store.read_snapshot(catalog.SNAP_SCRAPER_MARKETS)
    if not snapshot or not snapshot.payload:
        return payload

    scraped = {
        item["key"]: item
        for item in (snapshot.payload.get("items") or [])
        if isinstance(item, dict)
    }

    target = next(
        (c for c in payload["categories"] if c["id"] == indicators.SCRAPED_INJECT_CATEGORY),
        None,
    )
    if target is None:
        return payload

    existing = {item.get("key") for item in target["items"]}
    collected = kst.stamp(snapshot.collected_at) if snapshot.collected_at else None

    for spec in indicators.SCRAPED_FUTURES:
        card_key = f"{spec['key']}_scraped"
        if card_key in existing:
            continue

        source, label, market = scraped.get(spec["key"]), spec["name"], spec["market"]
        note = None
        fallback = spec.get("fallback")
        if fallback and not _usable(source) and _usable(scraped.get(fallback["key"])):
            source, label, market = scraped[fallback["key"]], fallback["name"], fallback["market"]
            note = "선물 조회 실패 · 지수 값"
        if not source:
            continue

        if not _usable(source):
            target["items"].append({
                "key": card_key, "name": label, "status": "fail",
                "source": source.get("provider"), "market": market,
            })
            continue

        price = float(source["price"])
        previous = source.get("previousClose")
        taken = source.get("collectedAt") or collected
        card = {
            "key": card_key,
            "name": label,
            # 선물은 TradingView 공개 시세가 15~20분 늦습니다. '수집 시각' 옆에 지연을 적지 않으면 그 시각의 값으로 읽힙니다(QA-137).
            "note": note or source.get("note") or _with_delay(source, "참고 시세"),
            "market": market,
            "status": "ok",
            "price": price,
            "priceStr": f"{price:,.2f}",
            "source": source.get("provider"),
            "lastTs": _tradingview_last_ts(source, taken),
            "isReference": True,
            # 추정치를 확정치처럼 보여 주면 교차 검증이 무의미해집니다.
            "isEstimated": bool(source.get("isEstimated")),
            **_stale_fields(source),
        }
        if previous:
            previous = float(previous)
            delta = price - previous
            pct = delta / previous * 100.0 if previous else 0.0
            card.update({
                "delta": delta, "pct": pct,
                "deltaStr": f"{delta:+,.2f} ({pct:+.2f}%)",
                "prevStr": f"{previous:,.2f}", "prevValue": previous,
            })
        else:
            card.update({
                "delta": None, "pct": None,
                "deltaStr": "N/A", "prevStr": "N/A", "prevValue": None,
            })
        target["items"].append(card)

    return payload


def summarize_notes(payload: dict) -> dict:
    """
    카테고리 부제(화면의 "데이터 지연: …")를 종목 배지로 요약합니다(QA-137).

    종목 배지가 모두 같으면 그 배지, 다르면 "종목마다 다름"입니다. 예전에는 카테고리마다 문구를 손으로 적어,
    지연 없는 S&P 500 지수와 10분 늦은 ES 선물이 함께 있는 카테고리가 '15분 지연'이었습니다.
    수집 실패·이전 값(isStale) 카드는 지금 받은 값의 지연이 아니므로 요약에서 뺍니다.
    """
    for category in payload.get("categories") or []:
        notes = {
            item.get("note")
            for item in category.get("items") or []
            if item.get("status") in ("ok", "single") and not item.get("isStale")
        }
        if len(notes) > 1:
            category["note"] = indicators.MIXED_DELAY_NOTE
        else:
            category["note"] = notes.pop() if notes else None
    return payload


def _with_delay(source: dict, nature: str) -> str:
    """'실시간 · TradingView 참고'처럼 출처가 알려 준 지연을 앞에 붙입니다. 지연을 모르면 성격만 적습니다."""
    label = indicators.delay_label(source.get("delayMinutes"))
    return f"{label} · {nature}" if label else nature


def _stale_fields(source: dict) -> dict:
    """
    수집 실패로 직전 값을 이어받은 원본(isStale)이면 카드에도 그 사실을 옮깁니다(QA-030). 옮기지 않으면 몇 시간 지난
    값이 지금 받은 값처럼 화면·AI 텍스트·스프레드에 들어갑니다.
    """
    if not source.get("isStale"):
        return {}
    return {
        "isStale": True,
        "staleReason": source.get("staleReason") or "수집 실패",
        "note": source.get("note") or "⚠️ 이전 값",
    }


def _usable(item: dict | None) -> bool:
    return bool(item) and item.get("status") == "ok" and item.get("price") is not None
