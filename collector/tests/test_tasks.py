"""
tests/test_tasks.py
수집 태스크 규칙 회귀 테스트 (PostgreSQL 필요).

고정하는 규칙
  1. 빈 결과로 기존 저장본을 덮어쓰지 않는다 (EmptyResult → 실패 집계).
  2. 추정치는 누적 테이블에 넣지 않는다.
  3. 태스크 결과가 collector_task_runs에 남는다 (무엇이 왜 실패했는지).
"""
from __future__ import annotations

import pytest

from app import catalog, macro_cards, tasks


def test_empty_result_is_counted_as_failure(store, monkeypatch):
    def failing():
        raise tasks.EmptyResult("0/10 소스 — 기존 저장본 유지")

    task = tasks.Task("demo_task", "fast", failing, "테스트용")
    ok, detail = tasks.run_task(task)

    assert ok is False
    assert "수집 결과 없음" in detail

    summary = {row["task"]: row for row in store.read_task_summary()}
    assert summary["demo_task"]["status"] == "empty"


def test_exception_is_recorded_with_type(store):
    def broken():
        raise ValueError("파싱 실패")

    ok, detail = tasks.run_task(tasks.Task("broken_task", "slow", broken, ""))

    assert ok is False
    assert detail.startswith("ValueError:")
    summary = {row["task"]: row for row in store.read_task_summary()}
    assert summary["broken_task"]["status"] == "error"


def test_empty_collection_keeps_previous_snapshot(store, monkeypatch):
    """네트워크 일시 장애로 어제 받아 둔 데이터를 날리면 안 됩니다."""
    store.put_snapshot(catalog.SNAP_SCRAPER_MARKETS, {"items": [{"key": "us10y"}]})

    monkeypatch.setattr(
        tasks.scraper_service, "collect_scraped_markets",
        lambda: {"updatedAt": "now", "items": [{"key": "us10y", "status": "fail"}]},
    )

    with pytest.raises(tasks.EmptyResult):
        tasks.task_scraper_markets()

    kept = store.read_snapshot(catalog.SNAP_SCRAPER_MARKETS)
    assert kept.payload == {"items": [{"key": "us10y"}]}


def test_estimated_krx_futures_are_not_accumulated(store, monkeypatch):
    monkeypatch.setattr(
        tasks.krx_service, "collect_futures_history",
        lambda days: {
            "isEstimated": True,
            "rows": [{
                "date": "2026-09-11", "futuresClose": 1088.3, "changePct": -2.13,
                "changePctReported": None, "volume": None, "openInterest": None,
                "oiChange": None, "theoryPrice": None, "marketBasis": None,
                "contractName": "추정", "marketPhase": "판정 불가 (등락률 미제공)",
                "cotOiIndex": None,
            }],
        },
    )

    detail = tasks.task_krx_futures()

    assert "추정치" in detail
    assert store.read_timeseries(catalog.TS_KRX_FUTURES, "futuresClose") == []
    snapshot = store.read_snapshot(catalog.SNAP_KRX_FUTURES)
    assert snapshot.status == "estimated"


def test_confirmed_krx_futures_are_accumulated(store, monkeypatch):
    monkeypatch.setattr(
        tasks.krx_service, "collect_futures_history",
        lambda days: {
            "isEstimated": False,
            "rows": [{
                "date": "2026-09-11", "futuresClose": 1088.3, "changePct": -2.13,
                "changePctReported": -2.13, "volume": 120000.0,
                "openInterest": 310000.0, "oiChange": 10000.0,
                "theoryPrice": 1085.0, "marketBasis": 3.3,
                "contractName": "코스피200 F 202609",
                "marketPhase": "신규 숏 (Short Accumulation)", "cotOiIndex": 88.0,
            }],
        },
    )

    tasks.task_krx_futures()

    accumulated = store.read_timeseries(catalog.TS_KRX_FUTURES, "futuresClose")
    assert accumulated == [{"date": "2026-09-11", "value": 1088.3}]
    assert store.read_snapshot(catalog.SNAP_KRX_FUTURES).status == "ok"


def test_fred_task_writes_snapshot_and_history(store, monkeypatch):
    monkeypatch.setattr(
        tasks.indicators, "FRED_ALL_SERIES", ("T10Y3M",), raising=False
    )
    monkeypatch.setattr(
        tasks.fred_service, "collect_series_with_reason",
        lambda series_id, period_years=10: (
            [
                {"date": "2026-09-10", "value": 0.42},
                {"date": "2026-09-11", "value": 0.38},
            ],
            None,
        ),
    )

    detail = tasks.task_fred_series()

    assert "1/1 시리즈" in detail
    snapshot = store.read_snapshot(catalog.snap_fred_series("T10Y3M"))
    assert snapshot.payload["points"][-1]["value"] == 0.38
    assert len(store.read_timeseries(catalog.TS_FRED, "T10Y3M")) == 2


def test_fred_task_failure_detail_carries_reason(store, monkeypatch):
    """
    전부 실패했을 때 detail이 "0/1 시리즈"에서 끝나면 운영자가 무엇을
    고쳐야 할지 알 수 없습니다. 사유가 실패 메시지에 실려야 합니다.
    """
    monkeypatch.setattr(
        tasks.indicators, "FRED_ALL_SERIES", ("T10Y3M",), raising=False
    )
    monkeypatch.setattr(
        tasks.fred_service, "collect_series_with_reason",
        lambda series_id, period_years=10: (
            [], "CSV HTTP 403 — FRED_API_KEY를 설정하면 공식 API 경로로 우회됩니다"
        ),
    )

    with pytest.raises(tasks.EmptyResult) as exc:
        tasks.task_fred_series()

    assert "403" in str(exc.value)
    assert "FRED_API_KEY" in str(exc.value)


def test_run_group_summarizes_results(store, monkeypatch):
    monkeypatch.setattr(
        tasks, "ALL_TASKS",
        (
            tasks.Task("ok_task", "fast", lambda: "정상", ""),
            tasks.Task("bad_task", "fast", lambda: (_ for _ in ()).throw(RuntimeError("x")), ""),
        ),
    )

    result = tasks.run_group("fast")

    assert result["okCount"] == 1
    assert result["failCount"] == 1
    last_run = store.read_last_run()
    assert last_run["status"] == "partial"


def test_sec_13f_q1_is_derived_from_q8(store, monkeypatch):
    """q1은 q8의 앞부분입니다. 같은 데이터를 두 번 받지 않습니다."""
    monkeypatch.setenv("SEC_USER_AGENT", "test@example.com")
    calls = {"count": 0}

    def fake_collect(cik, quarters):
        calls["count"] += 1
        return {
            "cik": cik,
            "error": None,
            "quarters": [
                {"filingDate": f"2026-0{q}-15", "reportDate": f"2026-0{q}-31",
                 "totalValue": 1000.0, "holdings": []}
                for q in range(1, 4)
            ],
        }

    monkeypatch.setattr(tasks.sec_service, "collect_13f", fake_collect)
    monkeypatch.setattr(
        tasks.indicators, "INSTITUTIONS",
        [{"key": "demo", "name": "데모 기관", "cik": "0001067983", "desc": ""}],
        raising=False,
    )

    tasks.task_sec_13f()

    assert calls["count"] == 1, "기관당 1회만 수집해야 합니다"
    q8 = store.read_snapshot(catalog.snap_sec_13f("0001067983", 8))
    q1 = store.read_snapshot(catalog.snap_sec_13f("0001067983", 1))
    assert len(q8.payload["quarters"]) == 3
    assert len(q1.payload["quarters"]) == 1
    assert q1.payload["quarters"][0] == q8.payload["quarters"][0]


# ==============================================================================
# 아시아 선물 카드 주입 — 수집 시각·실제 선물·지수 대체 표기
# ==============================================================================
def _asia_payload() -> dict:
    return {"categories": [{"id": "asia_equity", "items": []}]}


def _ok(price: float, previous: float) -> dict:
    return {"status": "ok", "price": price, "previousClose": previous,
            "provider": "TradingView Scanner"}


def test_futures_cards_carry_collection_time_and_market(store):
    store.put_snapshot(catalog.SNAP_SCRAPER_MARKETS, {"items": [
        {"key": "kospi200_night", **_ok(1127.75, 1112.0)},
        {"key": "nikkei_fut", **_ok(66370.0, 65500.0)},
        {"key": "hsi_fut", **_ok(24520.0, 24500.0)},
    ]})

    items = macro_cards.inject_scraped_indices(_asia_payload())["categories"][0]["items"]
    by_key = {item["key"]: item for item in items}

    assert set(by_key) == {"kospi200_night_scraped", "nikkei_fut_scraped", "hsi_fut_scraped"}
    for item in items:
        # 예전 카드에는 시각이 없었습니다. 체결 시각이 아니라 수집 시각임을 밝힙니다.
        assert item["lastTs"].endswith("KST (TradingView 수집 시각)")
    assert by_key["nikkei_fut_scraped"]["name"] == "닛케이225 선물"
    assert by_key["nikkei_fut_scraped"]["market"] == "ose_futures"
    assert by_key["hsi_fut_scraped"]["market"] == "hkex_futures"
    assert by_key["kospi200_night_scraped"]["market"] == "krx_futures"


def test_failed_futures_fall_back_to_index_with_honest_name(store):
    """지수 값을 '선물'이라는 이름으로 보여 주면 안 됩니다."""
    store.put_snapshot(catalog.SNAP_SCRAPER_MARKETS, {"items": [
        {"key": "nikkei_fut", "status": "fail", "price": None, "provider": "TradingView Scanner"},
        {"key": "nikkei", **_ok(66366.0, 65514.0)},
        {"key": "hsi_fut", "status": "fail", "price": None, "provider": "TradingView Scanner"},
    ]})

    items = macro_cards.inject_scraped_indices(_asia_payload())["categories"][0]["items"]
    by_key = {item["key"]: item for item in items}

    nikkei = by_key["nikkei_fut_scraped"]
    assert nikkei["status"] == "ok"
    assert nikkei["name"] == "닛케이225 지수 (선물 대체)"
    assert nikkei["note"] == "선물 조회 실패 · 지수 값"
    assert nikkei["market"] == "tse"
    # 대체할 지수도 없으면 실패 카드 — 선물 이름 그대로, 값 없음
    assert by_key["hsi_fut_scraped"]["status"] == "fail"
    assert by_key["hsi_fut_scraped"]["name"] == "항셍 선물"


def test_run_log_retention_task_uses_configured_days(monkeypatch):
    """실행 기록 정리는 주 단위 그룹에서 돌고, 보존 기간 설정(최소 7일)을 따릅니다 (ARC-03)."""
    assert tasks.TASKS_BY_NAME["run_log_retention"].speed == "weekly"

    calls = []
    monkeypatch.setattr(tasks.store, "purge_run_logs",
                        lambda days: calls.append(days) or {"taskRuns": 1234, "collectorRuns": 5})

    monkeypatch.delenv("COLLECTOR_RUN_LOG_RETENTION_DAYS", raising=False)
    assert tasks.task_run_log_retention() == "90일 지난 실행 기록 정리 — 태스크 1,234건 · 실행 5건"

    for raw, expected in (("30", 30), ("1", 7), ("abc", 90)):
        monkeypatch.setenv("COLLECTOR_RUN_LOG_RETENTION_DAYS", raw)
        tasks.task_run_log_retention()
        assert calls[-1] == expected, raw


def test_fred_task_keeps_a_long_snapshot_when_the_response_shrinks(store, monkeypatch):
    """
    백엔드는 FRED를 timeseries가 아니라 스냅샷에서 읽습니다. 응답이 2개 점만 와도
    (FRED 부분 장애·CSV 잘림) 그걸로 10년치 스냅샷을 덮으면 다음 성공까지 모든 FRED
    차트가 두 점으로 무너집니다. 저장본보다 훨씬 짧은 응답은 스냅샷을 덮지 않습니다.
    """
    monkeypatch.setattr(tasks.indicators, "FRED_ALL_SERIES", ("T10Y3M",), raising=False)
    stored = [{"date": f"2016-{m:02d}-01", "value": float(m)} for m in range(1, 13)] * 20
    store.put_snapshot(catalog.snap_fred_series("T10Y3M"), {"seriesId": "T10Y3M", "points": stored})
    monkeypatch.setattr(
        tasks.fred_service, "collect_series_with_reason",
        lambda series_id, period_years=10: (
            [{"date": "2026-09-10", "value": 0.42}, {"date": "2026-09-11", "value": 0.38}], None,
        ),
    )

    with pytest.raises(tasks.EmptyResult) as exc:
        tasks.task_fred_series()

    assert "2점" in str(exc.value) and "240점" in str(exc.value)
    assert len(store.read_snapshot(catalog.snap_fred_series("T10Y3M")).payload["points"]) == 240
    # 받은 점 자체는 진짜 데이터이므로 누적 시계열에는 들어갑니다.
    assert len(store.read_timeseries(catalog.TS_FRED, "T10Y3M")) == 2


def test_run_status_는_태스크_상태에서_유도된다():
    """실패가 전부 '수집 결과 없음'이면 fail이 아니라 empty — 저장본은 그대로이고 소스가 새 데이터를 안 준 것뿐."""
    empty = f"fsc_prices: {tasks.EMPTY_PREFIX}새 기준일 데이터가 없습니다"
    error = "fred_series: HTTPError: 503"
    assert tasks._run_status(3, []) == "ok"
    assert tasks._run_status(1, [error]) == "partial"
    assert tasks._run_status(0, [empty]) == "empty"
    assert tasks._run_status(0, [empty, empty]) == "empty"
    assert tasks._run_status(0, [empty, error]) == "fail"
    assert tasks._run_status(0, [error]) == "fail"


def test_fred_task_sends_only_points_near_the_stored_tail(store, monkeypatch):
    """매시간 10년치 2,600점을 전부 보내지 않고, 저장된 마지막 날짜 − 7일부터만 보냅니다."""
    monkeypatch.setattr(tasks.indicators, "FRED_ALL_SERIES", ("T10Y3M",), raising=False)
    store.put_timeseries(catalog.TS_FRED, "T10Y3M", [("2026-09-01", 1.0), ("2026-09-20", 1.1)])
    points = [{"date": f"2026-0{m}-{d:02d}", "value": 0.5} for m in (7, 8, 9) for d in range(1, 29)]
    monkeypatch.setattr(tasks.fred_service, "collect_series_with_reason",
                        lambda series_id, period_years=10: (points, None))

    detail = tasks.task_fred_series()

    # 2026-09-13(= 09-20 − 7일)부터 09-28까지 16점만 보냅니다.
    assert "누적 16행" in detail
    assert len(store.read_timeseries(catalog.TS_FRED, "T10Y3M")) == 2 + 16 - 1   # 09-20은 겹침(값 갱신)


def test_recent_points_keeps_everything_on_first_run():
    points = [("2026-01-01", 1.0), ("2026-06-01", 2.0)]
    assert tasks._recent_points(points, None) == points
    assert tasks._recent_points(points, "2026-06-05") == [("2026-06-01", 2.0)]


# ---- QA-001: 일부 소스만 실패해도 나머지 카드의 마지막 정상 값이 사라지던 문제 ---------------------
def _card(key: str, price: float | None, *, status: str = "ok", last_ts: str = "2026-10-02 17:58 KST") -> dict:
    item = {"key": key, "name": key, "note": "실시간", "market": "fx", "source": "Yahoo", "status": status}
    if price is not None:
        item.update({"price": price, "priceStr": f"{price:,.2f}", "lastTs": last_ts})
    return item


def test_QA001_partial_failure_keeps_previous_card_values(store, monkeypatch):
    """
    QA-001 (S2). 외부 시세 소스가 끊겼을 때 카드 21개 중 3개(다른 소스)만 살아남으면
    task_macro_collected가 저장본 전체를 새 결과로 덮어, 실패한 18개 카드의 마지막 정상 값이
    화면에서 사라졌습니다("데이터 수집 실패"). 전부 실패하면 저장본을 유지하면서(EmptyResult)
    일부만 실패하면 버리는 것은 일관되지 않습니다. 실패한 카드는 직전 저장본의 값을 그 시각
    그대로 이어받고 '이전 값'으로 표시해야 합니다.
    """
    store.put_snapshot(catalog.SNAP_MACRO_COLLECTED, {
        "updatedAt": "2026-10-02 17:58 KST",
        "categories": [{"id": "fx", "items": [_card("dxy", 98.1), _card("usdkrw", 1401.5)]},
                       {"id": "ust", "items": [_card("us10y", 4.12)]}],
        "rates": {"us10y": {"current": 4.12, "previous": 4.10}},
    })
    monkeypatch.setattr(tasks.market_service, "collect_macro_cards", lambda categories: {
        "categories": [{"id": "fx", "items": [_card("dxy", 98.4, last_ts="2026-10-02 20:14 KST"),
                                              _card("usdkrw", None, status="fail")]},
                       {"id": "ust", "items": [_card("us10y", None, status="fail")]}],
        "rates": {"us10y": {"current": None, "previous": None}},
    })
    monkeypatch.setattr(tasks.market_service, "now_kst_text", lambda: "2026-10-02 20:14 KST")
    monkeypatch.setattr(tasks.macro_cards, "apply_bond_override", lambda payload: payload)
    monkeypatch.setattr(tasks.macro_cards, "inject_scraped_indices", lambda payload: payload)

    detail = tasks.task_macro_collected()

    saved = store.read_snapshot(catalog.SNAP_MACRO_COLLECTED).payload
    items = {item["key"]: item for category in saved["categories"] for item in category["items"]}
    assert items["dxy"]["price"] == 98.4                         # 새로 받은 값은 새 값
    assert items["usdkrw"]["price"] == 1401.5                    # 실패한 카드는 마지막 정상 값
    assert items["usdkrw"]["lastTs"] == "2026-10-02 17:58 KST"   # 그 값의 시각 그대로(새 시각으로 꾸미지 않음)
    assert items["usdkrw"]["status"] == "ok"
    assert items["usdkrw"]["isStale"] is True
    assert "이전 값" in items["usdkrw"]["note"]
    assert saved["rates"]["us10y"] == {"current": 4.12, "previous": 4.10}   # 스프레드용 금리도 이어받음
    assert "이전 값" in detail


def test_QA001_failed_card_without_previous_value_stays_failed(store, monkeypatch):
    """직전 저장본에도 없던 카드는 그대로 실패입니다 — 없는 숫자를 만들지 않습니다."""
    store.put_snapshot(catalog.SNAP_MACRO_COLLECTED, {"categories": [{"id": "fx", "items": [_card("dxy", 98.1)]}], "rates": {}})
    monkeypatch.setattr(tasks.market_service, "collect_macro_cards", lambda categories: {
        "categories": [{"id": "fx", "items": [_card("dxy", 98.4), _card("usdkrw", None, status="fail")]}], "rates": {}})
    monkeypatch.setattr(tasks.market_service, "now_kst_text", lambda: "now")
    monkeypatch.setattr(tasks.macro_cards, "apply_bond_override", lambda payload: payload)
    monkeypatch.setattr(tasks.macro_cards, "inject_scraped_indices", lambda payload: payload)

    tasks.task_macro_collected()

    saved = store.read_snapshot(catalog.SNAP_MACRO_COLLECTED).payload
    usdkrw = saved["categories"][0]["items"][1]
    assert usdkrw["status"] == "fail" and "price" not in usdkrw


def test_QA001_scraped_markets_partial_failure_keeps_previous_items(store, monkeypatch):
    """같은 문제가 scraper_markets(지수·선물 폴백 소스)에도 있었습니다."""
    store.put_snapshot(catalog.SNAP_SCRAPER_MARKETS, {"updatedAt": "t0", "items": [
        {"key": "us10y", "status": "ok", "price": 4.12, "provider": "TradingView Scanner"},
        {"key": "nikkei_fut", "status": "ok", "price": 66370.0, "provider": "TradingView Scanner"}]})
    monkeypatch.setattr(tasks.scraper_service, "collect_scraped_markets", lambda: {"updatedAt": "t1", "items": [
        {"key": "us10y", "status": "ok", "price": 4.15, "provider": "TradingView Scanner"},
        {"key": "nikkei_fut", "status": "fail", "price": None, "error": "timeout", "provider": "TradingView Scanner"}]})

    tasks.task_scraper_markets()

    items = {item["key"]: item for item in store.read_snapshot(catalog.SNAP_SCRAPER_MARKETS).payload["items"]}
    assert items["us10y"]["price"] == 4.15
    assert items["nikkei_fut"]["price"] == 66370.0 and items["nikkei_fut"]["isStale"] is True
