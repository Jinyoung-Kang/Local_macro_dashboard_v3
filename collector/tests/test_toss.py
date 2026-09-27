"""
tests/test_toss.py
토스증권 Open API — 투자자별 매매 정규화와 호출 규칙.

고정하는 규칙
  1. 문자열 정수를 int로 옮기고, **null은 None으로 둔다**(0으로 바꾸지 않음).
  2. 401이면 토큰을 한 번만 재발급해 다시 부른다(client당 토큰 1개 — 다른 곳에서
     재발급하면 우리 토큰이 무효가 됨).
  3. 429는 Retry-After만큼 기다려 다시, 403은 즉시 TossForbidden.
  4. 키가 없으면 HTTP를 부르지 않는다.
  5. 태스크: 레이더 화면 종목부터 받고, 날짜별로 observations에 쌓고, 403이면 멈춘다.

입력(fixtures/toss)은 실제 호출 결과가 아니라 공식 스펙 1.2.17로 만든 응답입니다.
"""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from app import catalog, tasks
from app.services import toss

FIXTURES = Path(__file__).parent / "fixtures" / "toss"


def _fixture(name: str) -> dict:
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


class FakeResponse:
    def __init__(self, status: int, body: dict | None = None, headers: dict | None = None):
        self.status_code = status
        self._body = body
        self.headers = headers or {}
        self.text = json.dumps(body or {})

    def json(self):
        if self._body is None:
            raise ValueError("no json")
        return self._body


@pytest.fixture
def creds(monkeypatch):
    monkeypatch.setattr(toss.settings, "toss_credentials", lambda: ("cid", "secret-value"))
    toss._invalidate_token()
    issued: list[int] = []

    def fake_post(url, data=None, headers=None, timeout=None):
        issued.append(1)
        return type("R", (), {
            "raise_for_status": lambda self: None,
            "json": lambda self: {"access_token": f"tok{len(issued)}", "expires_in": 3600},
        })()

    monkeypatch.setattr(toss._http(), "post", fake_post)
    yield issued
    toss._invalidate_token()


# ------------------------------------------------------------------ 정규화
def test_market_record_amounts_and_breakdown():
    record = toss.normalize_market_record(
        _fixture("market_investor_trading_kospi.json")["result"]["records"][0]
    )
    eok = 10**8
    assert record["date"] == "2026-09-25"
    assert record["investors"]["foreigner"] == {"buy": 52000 * eok, "sell": 50000 * eok, "net": 2000 * eok}
    assert record["investors"]["individual"]["net"] == -3000 * eok
    # 기관 합계 = 세부 7개 합 (공식 설명) — 순매수도 같아야 합니다
    breakdown_net = sum(v["net"] for v in record["breakdown"].values())
    assert breakdown_net == record["investors"]["institution"]["net"]
    assert record["breakdown"]["pensionFund"]["net"] == 200 * eok


def test_provisional_stock_record_keeps_nulls():
    """당일 잠정 기록의 개인·기관 세부·기타법인·외국인 보유는 '모름'이지 0이 아닙니다."""
    record = toss.normalize_stock_record(
        _fixture("stock_investor_trading_005930.json")["result"]["records"][0]
    )
    assert record["investors"]["foreigner"]["net"] == 291850
    assert record["investors"]["institution"]["net"] == 37900
    assert record["investors"]["individual"] is None
    assert record["investors"]["otherCorporation"] is None
    assert record["breakdown"] is None
    assert record["foreignerHoldingRate"] is None


def test_confirmed_stock_record_negative_net_and_holding():
    record = toss.normalize_stock_record(
        _fixture("stock_investor_trading_005930.json")["result"]["records"][1]
    )
    assert record["investors"]["individual"]["net"] == -600000
    assert record["breakdown"]["pensionFund"]["net"] == 2000
    assert record["foreignerHoldingRate"] == pytest.approx(0.5089)


def test_non_numeric_amount_is_none():
    assert toss._int("-") is None
    assert toss._int(None) is None
    assert toss._int("-291850") == -291850


# ------------------------------------------------------------------ 호출 규칙
def test_missing_key_does_not_call(monkeypatch):
    monkeypatch.setattr(toss.settings, "toss_credentials", lambda: ("", ""))
    monkeypatch.setattr(toss._http(), "get", lambda *a, **k: pytest.fail("호출하면 안 됩니다"))
    with pytest.raises(toss.TossMissingKey):
        toss.fetch_stock_investor_trading("005930")


def test_401_reissues_token_once(monkeypatch, creds):
    calls: list[str] = []

    def fake_get(url, headers=None, params=None, timeout=None):
        calls.append(headers["Authorization"])
        if len(calls) == 1:
            return FakeResponse(401, {"error": {"code": "invalid-token", "message": "", "requestId": "x"}})
        return FakeResponse(200, _fixture("stock_investor_trading_005930.json"))

    monkeypatch.setattr(toss._http(), "get", fake_get)
    records = toss.fetch_stock_investor_trading("005930", count=3)
    assert len(records) == 3
    assert calls == ["Bearer tok1", "Bearer tok2"]  # 재발급한 새 토큰으로 다시
    assert len(creds) == 2


def test_concurrent_callers_issue_one_token(monkeypatch):
    """client당 토큰 1개 — 동시에 두 곳이 발급하면 먼저 받은 토큰이 무효가 됩니다."""
    import threading
    import time as real_time

    monkeypatch.setattr(toss.settings, "toss_credentials", lambda: ("cid", "secret-value"))
    toss._invalidate_token()
    issued: list[int] = []

    def slow_post(url, data=None, headers=None, timeout=None):
        issued.append(1)
        real_time.sleep(0.2)  # 발급 중에 다른 호출자가 들어오게
        return type("R", (), {
            "raise_for_status": lambda self: None,
            "json": lambda self: {"access_token": f"tok{len(issued)}", "expires_in": 3600},
        })()

    monkeypatch.setattr(toss._http(), "post", slow_post)
    tokens: list[str | None] = []
    threads = [threading.Thread(target=lambda: tokens.append(toss.get_access_token()[0])) for _ in range(4)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    toss._invalidate_token()

    assert len(issued) == 1
    assert tokens == ["tok1"] * 4


def test_401_does_not_drop_a_newer_token():
    """다른 호출자가 이미 재발급한 새 토큰은, 옛 토큰의 401 때문에 버리지 않습니다."""
    toss._token_cache = ("new-token", toss.time.time() + 600)
    toss._invalidate_token("old-token")
    assert toss._token_cache[0] == "new-token"
    toss._invalidate_token("new-token")
    assert toss._token_cache is None


def test_429_waits_retry_after(monkeypatch, creds):
    slept: list[float] = []
    responses = iter([
        FakeResponse(429, None, {"Retry-After": "2"}),
        FakeResponse(200, _fixture("market_investor_trading_kospi.json")),
    ])
    monkeypatch.setattr(toss.time, "sleep", slept.append)
    monkeypatch.setattr(toss._http(), "get", lambda *a, **k: next(responses))
    assert len(toss.fetch_market_investor_trading("KOSPI")) == 2
    assert slept == [2.0]


def test_403_is_forbidden_without_secret_in_message(monkeypatch, creds):
    monkeypatch.setattr(toss._http(), "get", lambda *a, **k: FakeResponse(
        403, {"error": {"code": "forbidden-ip", "message": "허용되지 않은 IP", "requestId": "r"}}))
    with pytest.raises(toss.TossForbidden) as info:
        toss.fetch_stock_investor_trading("005930")
    assert "forbidden-ip" in str(info.value)
    assert "secret-value" not in str(info.value) and "tok1" not in str(info.value)


def test_unsupported_market_symbol_rejected_before_call():
    with pytest.raises(ValueError):
        toss.fetch_market_investor_trading("KOSPI200")


# ------------------------------------------------------------------ 태스크 (DB)
def _radar_snapshot(store, codes):
    market, investor, trade_type, interval = tasks.RADAR_COMBINATIONS[0]
    store.put_snapshot(
        catalog.snap_radar_scanner(market, investor, trade_type, interval),
        {"rows": [{"code": c, "name": c} for c in codes]},
    )


def test_stock_flows_task_saves_by_date(store, monkeypatch):
    _radar_snapshot(store, ["005930", "000660", "bad!"])
    monkeypatch.setattr(tasks, "TOSS_STOCK_SPACING", 0)
    fixture = _fixture("stock_investor_trading_005930.json")["result"]["records"]
    monkeypatch.setattr(
        tasks.toss_service, "fetch_stock_investor_trading",
        lambda code, count: [toss.normalize_stock_record(r) for r in fixture],
    )

    detail = tasks.task_toss_stock_flows()

    assert detail.startswith("2/2 종목")  # 형식이 틀린 코드는 대상에서 빠집니다
    from app import store as store_module
    with store_module.connection() as conn:
        rows = conn.execute(
            "SELECT obs_date::text AS d, entity, payload FROM observations WHERE dataset = %s ORDER BY 1, 2",
            (catalog.OBS_TOSS_STOCK_FLOW,),
        ).fetchall()
    assert [(r["d"], r["entity"]) for r in rows] == [
        ("2026-09-23", "000660"), ("2026-09-23", "005930"),
        ("2026-09-24", "000660"), ("2026-09-24", "005930"),
        ("2026-09-25", "000660"), ("2026-09-25", "005930"),
    ]
    provisional = next(r["payload"] for r in rows if r["d"] == "2026-09-25")
    assert provisional["investors"]["individual"] is None


def test_stock_flows_task_stops_on_forbidden(store, monkeypatch):
    _radar_snapshot(store, ["005930", "000660", "035420"])
    monkeypatch.setattr(tasks, "TOSS_STOCK_SPACING", 0)
    calls: list[str] = []

    def forbidden(code, count):
        calls.append(code)
        raise toss.TossForbidden("HTTP 403 forbidden-ip — 허용 IP 확인")

    monkeypatch.setattr(tasks.toss_service, "fetch_stock_investor_trading", forbidden)
    with pytest.raises(tasks.EmptyResult) as info:
        tasks.task_toss_stock_flows()
    assert calls == ["005930"]  # 같은 답을 받을 나머지는 부르지 않습니다
    assert "허용 IP" in str(info.value)


def test_market_flows_keeps_previous_market_on_partial_failure(store, monkeypatch):
    store.put_snapshot(catalog.SNAP_TOSS_MARKET_FLOWS, {"markets": {"KOSDAQ": {"records": [{"date": "old"}]}}})
    fixture = _fixture("market_investor_trading_kospi.json")["result"]["records"]

    def fetch(symbol, count):
        if symbol == "KOSDAQ":
            raise toss.TossError("HTTP 500")
        return [toss.normalize_market_record(r) for r in fixture]

    monkeypatch.setattr(tasks.toss_service, "fetch_market_investor_trading", fetch)
    detail = tasks.task_toss_market_flows()

    assert detail.startswith("1/2 시장")
    payload = store.read_snapshot(catalog.SNAP_TOSS_MARKET_FLOWS).payload
    assert payload["markets"]["KOSDAQ"] == {"records": [{"date": "old"}]}
    assert payload["markets"]["KOSPI"]["records"][0]["date"] == "2026-09-25"


def test_market_flows_without_key_is_empty_result(store, monkeypatch):
    monkeypatch.setattr(toss.settings, "toss_credentials", lambda: ("", ""))
    with pytest.raises(tasks.EmptyResult) as info:
        tasks.task_toss_market_flows()
    assert "TOSS_CLIENT_ID" in str(info.value)


# ------------------------------------------------------------------ 레이더 폴백 (DB)
def _universe_record(date: str, foreigner, individual, pension):
    return {
        "date": date,
        "investors": {
            "foreigner": {"net": foreigner} if foreigner is not None else None,
            "institution": {"net": 0},
            "individual": {"net": individual} if individual is not None else None,
            "otherCorporation": None,
        },
        "breakdown": {"pensionFund": {"net": pension}} if pension is not None else None,
    }


def _seed_universe(store):
    store.put_snapshot(catalog.SNAP_TOSS_RADAR_UNIVERSE, {"universe": 100, "stocks": [
        {"code": "005930", "name": "삼성전자", "market": "KOSPI", "lastPrice": 70000.0, "changePct": 1.0,
         "records": [_universe_record("2026-09-25", 300000, None, None),       # 당일 잠정: 개인·연기금 없음
                     _universe_record("2026-09-24", 100000, -50000, 20000)]},
        {"code": "000660", "name": "SK하이닉스", "market": "KOSPI", "lastPrice": 200000.0, "changePct": -0.5,
         "records": [_universe_record("2026-09-25", -10000, None, None),
                     _universe_record("2026-09-24", 5000, 80000, -3000)]},
        {"code": "247540", "name": "에코프로비엠", "market": "KOSDAQ", "lastPrice": 150000.0, "changePct": 2.0,
         "records": [_universe_record("2026-09-25", 999999, None, None)]},
    ]})


def test_toss_ranking_uses_latest_date_with_values(store):
    _seed_universe(store)
    from app.services import radar

    foreign = radar.fetch_toss_ranking("KOSPI", "외국인", "순매수", 30)
    assert [row["code"] for row in foreign] == ["005930"]          # 코스닥 종목은 빠짐
    assert foreign[0]["netAmountEok"] == pytest.approx(210.0)      # 300,000주 × 70,000원
    assert "2026-09-25 기준" in foreign[0]["source"]
    assert "시장 전체 순위 아님" in foreign[0]["source"]

    # 개인·연기금은 당일 잠정치가 없어 전 거래일(09-24) 확정치로 — 날짜를 섞지 않습니다
    individual = radar.fetch_toss_ranking("KOSPI", "개인", "순매수", 30)
    assert [row["code"] for row in individual] == ["000660"]
    assert "2026-09-24 기준" in individual[0]["source"]
    pension_sell = radar.fetch_toss_ranking("KOSPI", "연기금", "순매도", 30)
    assert [row["code"] for row in pension_sell] == ["000660"]
    assert pension_sell[0]["netAmountEok"] < 0


def test_toss_ranking_without_snapshot_explains_why(store):
    from app.services import radar
    assert radar.fetch_toss_ranking("KOSPI", "외국인", "순매수", 30) == []
    assert radar._TOSS_LAST_REASON["value"]


# ------------------------------------------------------------------ 교차 검증용 읽기
def test_index_daily_close_uses_newest_candle_and_date(monkeypatch):
    monkeypatch.setattr(toss, "api_get", lambda path, params=None, timeout=10.0: {
        "candles": [
            {"timestamp": "2026-09-23T09:00:00+09:00", "closePrice": "3456.78"},
            {"timestamp": "2026-09-22T09:00:00+09:00", "closePrice": "3400.00"},
        ],
    })
    reading = toss.fetch_index_daily_close("KOSPI")
    assert reading["ok"] is True
    assert reading["value"] == 3456.78
    assert reading["asOf"] == "2026-09-23"


def test_usdkrw_uses_mid_rate_not_buy_rate(monkeypatch):
    monkeypatch.setattr(toss, "api_get", lambda path, params=None, timeout=10.0: {
        "rate": "1380.5", "midRate": "1375", "validFrom": "2026-09-23T15:30:00+09:00",
    })
    reading = toss.fetch_usdkrw_mid()
    assert reading["ok"] is True
    assert reading["value"] == 1375.0  # 스프레드가 붙은 rate(1380.5)가 아님


def test_verification_reading_never_raises(monkeypatch):
    monkeypatch.setattr(toss, "has_credentials", lambda: True)

    def boom():
        raise toss.TossForbidden("403")

    reading = toss.verification_reading(boom, "환율")
    assert reading["ok"] is False
    assert "403" in reading["detail"]


def test_verification_reading_without_keys(monkeypatch):
    monkeypatch.setattr(toss, "has_credentials", lambda: False)
    reading = toss.verification_reading(lambda: {"ok": True, "value": 1.0}, "환율")
    assert reading["ok"] is False
    assert "TOSS_CLIENT_ID" in reading["detail"]


def test_market_fixture_honors_spec_identities():
    """스펙: 4개 분류 매수 합계 = 매도 합계, 기관 합계 = 세부 7개 합. 테스트 입력도 이 등식을 지킵니다."""
    for rec in _fixture("market_investor_trading_kospi.json")["result"]["records"]:
        record = toss.normalize_market_record(rec)
        sides = [record["investors"][key] for key in toss.INVESTORS]
        if any(side is None or side["buy"] is None or side["sell"] is None for side in sides):
            continue  # 잠정치
        assert sum(s["buy"] for s in sides) == sum(s["sell"] for s in sides)
        if record["breakdown"]:
            for field in ("buy", "sell"):
                assert record["investors"]["institution"][field] == sum(
                    part[field] for part in record["breakdown"].values()
                )
