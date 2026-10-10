"""
tests/test_partial_carry_over.py
일부만 실패한 수집이 직전 저장본의 정상 항목을 버리지 않는다(QA-032).

전부 실패하면 EmptyResult로 저장본을 지키지만, 일부만 실패하면 받은 것만으로 저장본을 덮어 빠진 항목이
다음 실행까지 사라졌습니다 — yfinance 429로 한 종목이 빠지면 1시간 동안 그 종목의 위험·스코어카드가 '가격 없음'.
빠진 항목은 직전 값으로 잇고 isStale을 붙입니다(fast 태스크의 QA-001 규칙과 같음).
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone


from app import catalog, equities, indicators, tasks
from app.tasks import toss as toss_tasks

_FRAME = {"dates": ["2026-09-10", "2026-09-11"], "close": [100.0, 101.0]}
_OLD = {"dates": ["2026-09-09", "2026-09-10"], "close": [90.0, 91.0]}



def test_QA032_업종_ETF(store, monkeypatch):
    a, b = indicators.all_rotation_tickers()[:2]
    store.put_snapshot(catalog.SNAP_SECTOR_HISTORY, {"tickers": {a: _OLD, b: _OLD}})
    monkeypatch.setattr(tasks.sector_service, "collect_etf_history",
                        lambda tickers, period="2y": {"tickers": {a: _FRAME}, "error": f"{b}: 429"})

    detail = tasks.task_sector_history()

    saved = store.read_snapshot(catalog.SNAP_SECTOR_HISTORY).payload["tickers"]
    assert saved[a]["close"][-1] == 101.0
    assert saved[b]["close"] == _OLD["close"] and saved[b]["isStale"] is True
    assert "이전 값" in detail


def test_QA032_종목_가격(store, monkeypatch):
    a, b = equities.all_tickers()[1:3]
    store.put_snapshot(catalog.SNAP_EQUITY_HISTORY, {"tickers": {a: _OLD, b: _OLD}})
    monkeypatch.setattr(tasks.sector_service, "collect_daily_closes",
                        lambda tickers, period="5y": {"tickers": {a: _FRAME}, "error": f"{b}: 429"})

    tasks.task_equity_history()

    saved = store.read_snapshot(catalog.SNAP_EQUITY_HISTORY).payload["tickers"]
    assert saved[b]["close"] == _OLD["close"] and saved[b]["isStale"] is True


def test_QA032_환율(store, monkeypatch):
    specs = indicators.fx_history_specs()
    first, second = specs[0], specs[1]
    old_series = {"key": second["key"], "dates": _OLD["dates"], "close": _OLD["close"]}
    store.put_snapshot(catalog.SNAP_FX_HISTORY, {"period": "5y", "series": {second["key"]: old_series}})
    monkeypatch.setattr(tasks.sector_service, "collect_daily_closes",
                        lambda tickers, period="5y": {"tickers": {first["ticker"]: _FRAME}, "error": "429"})

    tasks.task_fx_history()

    saved = store.read_snapshot(catalog.SNAP_FX_HISTORY).payload["series"]
    assert first["key"] in saved
    assert saved[second["key"]]["close"] == _OLD["close"] and saved[second["key"]]["isStale"] is True


def test_QA032_COT(store, monkeypatch):
    names = list(indicators.COT_ASSETS)[:2]
    row = {"date": "2026-09-08", "net": 1}
    store.put_snapshot(catalog.SNAP_COT_HISTORY, {"assets": {
        names[0]: {"code": "A", "rows": [row]}, names[1]: {"code": "B", "rows": [row]}}})
    monkeypatch.setattr(tasks.cot_service, "collect_multi_asset", lambda assets, weeks: {"assets": {
        names[0]: {"code": "A", "rows": [{"date": "2026-09-15", "net": 2}]},
        names[1]: {"code": "B", "rows": [], "error": "Socrata 503"}}})

    tasks.task_cot_history()

    saved = store.read_snapshot(catalog.SNAP_COT_HISTORY).payload["assets"]
    assert saved[names[1]]["rows"] == [row] and saved[names[1]]["isStale"] is True


def test_QA032_토스_레이더_대상(store, monkeypatch):
    store.put_snapshot(catalog.SNAP_TOSS_RADAR_UNIVERSE, {"universe": 2, "stocks": [
        {"code": "000660", "market": "KOSPI", "records": [{"date": "2026-09-10"}]}]})
    monkeypatch.setattr(toss_tasks, "TOSS_STOCK_SPACING", 0)
    monkeypatch.setattr(toss_tasks.toss_service, "fetch_trading_amount_leaders",
                        lambda n: [{"code": "005930"}, {"code": "000660"}])
    monkeypatch.setattr(toss_tasks.toss_service, "fetch_stock_infos", lambda codes: {})

    def trading(code, days):
        if code == "000660":
            raise toss_tasks.toss_service.TossForbidden("403")
        return [{"date": "2026-09-11"}]

    monkeypatch.setattr(toss_tasks.toss_service, "fetch_stock_investor_trading", trading)

    toss_tasks.task_toss_radar_universe()

    stocks = {s["code"]: s for s in store.read_snapshot(catalog.SNAP_TOSS_RADAR_UNIVERSE).payload["stocks"]}
    assert stocks["000660"]["isStale"] is True
    assert stocks["000660"]["records"] == [{"date": "2026-09-10"}]


def test_QA056_오래_이어받은_항목은_더_잇지_않는다(store, monkeypatch):
    """
    목록에 남아 있는데 계속 실패하는 티커(폐지 종목)는 끝없이 이어받아졌습니다. 기관 위험 분석은 보유 종목 날짜의
    교집합으로 계산하므로, 멈춘 티커 하나가 포트폴리오 전체의 계산 구간을 그 티커의 마지막 날짜에 묶었습니다.
    """
    a, b, c = equities.all_tickers()[1:4]
    long_ago = (datetime.now(timezone.utc) - timedelta(days=10)).isoformat()
    store.put_snapshot(catalog.SNAP_EQUITY_HISTORY, {"tickers": {
        a: _OLD,
        b: {**_OLD, "isStale": True, "staleReason": "429", "staleSince": long_ago},
        c: _OLD,
    }})
    monkeypatch.setattr(tasks.sector_service, "collect_daily_closes",
                        lambda tickers, period="5y": {"tickers": {a: _FRAME}, "error": "429"})

    tasks.task_equity_history()

    saved = store.read_snapshot(catalog.SNAP_EQUITY_HISTORY).payload["tickers"]
    assert b not in saved, "이어받은 지 오래된 항목은 빠집니다"
    assert saved[c]["isStale"] is True and saved[c]["staleSince"], "처음 이어받을 때 그 시각을 적습니다"
