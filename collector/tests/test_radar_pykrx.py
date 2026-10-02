"""
tests/test_radar_pykrx.py
PyKrx 폴백 단계가 라이브러리를 맞게 부르고, 모르는 값을 0으로 적지 않는지.
"""
from __future__ import annotations

import pandas as pd
import pytest

from app.services import radar


class _FakePykrx:
    """순매수 1종목, 가격은 호출 인자를 기록하고 by_ticker 모양으로 돌려줍니다."""

    def __init__(self, prices: pd.DataFrame | None):
        self.ohlcv_calls: list[tuple] = []
        self._prices = prices

    def get_market_net_purchases_of_equities_by_ticker(self, fromdate, todate, market, investor):
        return pd.DataFrame(
            {"종목명": ["삼성전자"], "순매수거래대금": [250_000_000.0]},
            index=pd.Index(["005930"], name="티커"),
        )

    def get_market_ohlcv(self, *args, **kwargs):
        self.ohlcv_calls.append((args, kwargs))
        if self._prices is None:
            raise RuntimeError("차단 페이지")
        return self._prices


@pytest.fixture()
def pykrx_available(monkeypatch):
    monkeypatch.setattr(radar, "PYKRX_AVAILABLE", True)


def test_가격은_날짜_하나와_시장으로_조회한다(monkeypatch, pykrx_available):
    """
    pykrx.get_market_ohlcv는 YYYYMMDD 인자가 두 개면 '한 종목의 기간 조회'
    (ticker=시장 이름)로 분기합니다. 그러면 전 종목 가격 표가 아니라 빈 결과나
    오류가 와서, 모든 종목이 가격 0·등락률 0으로 "ok" 저장됐습니다.
    """
    prices = pd.DataFrame({"종가": [71000.0], "등락률": [1.25]}, index=pd.Index(["005930"], name="티커"))
    fake = _FakePykrx(prices)
    monkeypatch.setattr(radar, "pykrx_stock", fake)

    rows = radar.fetch_pykrx_ranking("20260911", "KOSPI", "외국인", "순매수", 30)

    assert fake.ohlcv_calls == [(("20260911",), {"market": "KOSPI"})]
    assert rows[0]["price"] == 71000.0
    assert rows[0]["changePct"] == 1.25
    assert rows[0]["netAmountEok"] == 2.5


def test_가격_조회가_실패하면_가격은_모르는_값이다(monkeypatch, pykrx_available):
    monkeypatch.setattr(radar, "pykrx_stock", _FakePykrx(None))

    rows = radar.fetch_pykrx_ranking("20260911", "KOSPI", "외국인", "순매수", 30)

    assert len(rows) == 1
    assert rows[0]["price"] is None
    assert rows[0]["changePct"] is None
    assert rows[0]["netAmountEok"] == 2.5          # 순매수 금액은 안다


def test_가격_표에_같은_종목이_두_번_있어도_깨지지_않는다(monkeypatch, pykrx_available):
    """중복 인덱스에서 .loc[code]는 DataFrame을 돌려줘 float()가 TypeError를 냈고, 그 예외는
    try 밖이라 태스크 전체가 멈췄습니다."""
    prices = pd.DataFrame({"종가": [71000.0, 71000.0], "등락률": [1.25, 1.25]},
                          index=pd.Index(["005930", "005930"], name="티커"))
    monkeypatch.setattr(radar, "pykrx_stock", _FakePykrx(prices))

    rows = radar.fetch_pykrx_ranking("20260911", "KOSPI", "외국인", "순매수", 30)

    assert rows[0]["price"] == 71000.0


def test_pykrx가_응답하지_않으면_정해진_시간_뒤에_포기한다(monkeypatch, pykrx_available):
    """pykrx 내부 requests에는 timeout이 없어 KRX가 응답을 안 주면 스케줄러 스레드가 무한정 묶였습니다."""
    import threading

    class _Hanging:
        def get_market_net_purchases_of_equities_by_ticker(self, *a, **k):
            threading.Event().wait(5)      # 테스트 한도보다 훨씬 긴 '응답 없음'
            return None

    monkeypatch.setattr(radar, "pykrx_stock", _Hanging())
    monkeypatch.setattr(radar, "PYKRX_TIMEOUT_SECONDS", 0.2)

    import time
    started = time.perf_counter()
    rows = radar.fetch_pykrx_ranking("20260911", "KOSPI", "외국인", "순매수", 30)

    assert rows == []
    assert time.perf_counter() - started < 3.0


def test_pykrx가_한_번_응답하지_않으면_한동안_바로_건너뛴다(monkeypatch, pykrx_available):
    """
    응답 없는 호출 두 번이면 워커 2개가 영구히 묶여, 그 뒤 모든 pykrx 호출이 실행도 못 한 채
    제한 시간(20초)만 소비했습니다 — 조합당 커서 7일이면 140초, fast 그룹이면 주기마다 7분.
    응답이 없었으면 정해진 시간 동안 pykrx를 바로 건너뛰고(다른 폴백은 계속), 그동안 묶인 워커 뒤에
    줄 서지 않도록 풀을 새로 만듭니다.
    """
    import threading
    import time

    started_calls: list[str] = []

    class _Hanging:
        def get_market_net_purchases_of_equities_by_ticker(self, *a, **k):
            started_calls.append("hang")
            threading.Event().wait(5)
            return None

    monkeypatch.setattr(radar, "pykrx_stock", _Hanging())
    monkeypatch.setattr(radar, "PYKRX_TIMEOUT_SECONDS", 0.2)
    monkeypatch.setattr(radar, "_pykrx_blocked_until", 0.0)

    assert radar.fetch_pykrx_ranking("20260911", "KOSPI", "외국인", "순매수", 30) == []   # 0.2초 뒤 포기

    started = time.perf_counter()
    assert radar.fetch_pykrx_ranking("20260910", "KOSPI", "외국인", "순매수", 30) == []
    assert time.perf_counter() - started < 0.1                 # 기다리지 않고 건너뛴다
    assert started_calls == ["hang"]                           # pykrx를 다시 부르지 않았다

    monkeypatch.setattr(radar, "_pykrx_blocked_until", 0.0)    # 차단 시간이 지나면
    radar.fetch_pykrx_ranking("20260909", "KOSPI", "외국인", "순매수", 30)
    assert started_calls == ["hang", "hang"]                   # 다시 시도한다 (새 풀이라 실행된다)
