"""
tests/test_fsc.py
금융위원회 주식시세정보 (GetStockSecuritiesInfoService_V2 / getStockPriceInfo_V2).

응답 형태의 근거 — 금융위원회_주식시세정보 오픈API 활용가이드의 요청·응답 명세와
응답 예시(tests/fixtures/public/fsc_getStockPriceInfo_V2_guide_sample.xml).

고정하는 규칙
  1. 활용가이드의 오퍼레이션 이름(getStockPriceInfo_V2)으로 부른다.
     (getStockPriceInfo로 부르면 NO_OPENAPI_SERVICE_ERROR — 실제로 겪은 일)
  2. "A005930" 같은 접두어를 떼고 6자리 코드로 맞춘다. 숫자가 아니면 None.
  3. 시장 합계는 값이 있는 행만 더하고, 빠진 행 수를 남긴다.
  4. totalCount만큼 받으면 페이지 요청을 멈춘다.
  5. 이미 최신 기준일이 저장돼 있으면 호출 0회.
"""
from __future__ import annotations

from types import SimpleNamespace

import pytest

from app import publicapi
from app.services import fsc


def _xml(items: list[dict], total: int, code: str = "00") -> SimpleNamespace:
    body = "".join(
        "<item>" + "".join(f"<{k}>{v}</{k}>" for k, v in item.items()) + "</item>" for item in items
    )
    text = (f"<response><header><resultCode>{code}</resultCode><resultMsg>NORMAL SERVICE.</resultMsg></header>"
            f"<body><items>{body}</items><numOfRows>1000</numOfRows><pageNo>1</pageNo>"
            f"<totalCount>{total}</totalCount></body></response>")
    return SimpleNamespace(text=text, status_code=200)


SAMSUNG = {"basDt": "20260923", "srtnCd": "A005930", "itmsNm": "삼성전자", "mrktCtg": "KOSPI",
           "clpr": "71,000", "vs": "-500", "fltRt": "-0.70", "trqu": "1000", "trPrc": "71000000",
           "lstgStCnt": "100", "mrktTotAmt": "7100000"}


def test_행을_정규화한다():
    row = fsc.normalize(SAMSUNG)
    assert row["code"] == "005930" and row["market"] == "KOSPI"
    assert row["close"] == 71000.0 and row["changePct"] == -0.70
    assert fsc.normalize({"srtnCd": "XYZ", "clpr": "-"})["code"] is None
    assert fsc.normalize({"srtnCd": "000660", "clpr": ""})["close"] is None


def test_시장_합계는_빈_값을_0으로_섞지_않는다():
    rows = [
        {"market": "KOSPI", "marketCap": 100.0, "tradingValue": 10.0},
        {"market": "KOSPI", "marketCap": None, "tradingValue": 5.0},
        {"market": None, "marketCap": 7.0, "tradingValue": None},
    ]
    totals = fsc.market_totals(rows)
    assert totals["KOSPI"] == {"count": 2, "marketCap": 100.0, "tradingValue": 15.0, "missingCap": 1}
    assert totals["기타"]["marketCap"] == 7.0


def test_시장_합계는_전부_비면_0이_아니라_None():
    """
    모든 행에 시가총액이 없으면(필드 이름 변경) 합계가 0.0으로 나와 timeseries에
    '시가총액 0원'이 쌓이고 화면 차트가 0으로 떨어졌습니다. 하나도 모르면 모르는 것입니다.
    거래대금도 같은 규칙입니다.
    """
    rows = [
        {"market": "KOSPI", "marketCap": None, "tradingValue": None},
        {"market": "KOSPI", "marketCap": None, "tradingValue": 5.0},
    ]
    totals = fsc.market_totals(rows)
    assert totals["KOSPI"]["marketCap"] is None
    assert totals["KOSPI"]["missingCap"] == 2
    assert totals["KOSPI"]["tradingValue"] == 5.0
    assert fsc.market_totals([{"market": "KOSDAQ", "marketCap": 1.0, "tradingValue": None}])["KOSDAQ"]["tradingValue"] is None


def test_활용가이드의_V2_오퍼레이션으로_부른다(monkeypatch):
    monkeypatch.setattr(fsc.settings, "data_go_kr_key", lambda: "k" * 20)
    monkeypatch.delenv("FSC_STOCK_PRICE_URL", raising=False)
    seen = []

    def fake_get(url, params, **kwargs):
        seen.append(url)
        return _xml([SAMSUNG], 1)

    monkeypatch.setattr(fsc.publicapi, "get", fake_get)
    rows, url = fsc.fetch_day("20260923", publicapi.CallBudget(5))
    assert seen == [url] and url.endswith("/GetStockSecuritiesInfoService_V2/getStockPriceInfo_V2")
    assert [r["code"] for r in rows] == ["005930"]


def test_실패하면_다른_주소로_호출하지_않고_사유를_올린다(monkeypatch):
    """이전 주소 폴백은 승인되지 않은 서비스라 매번 403으로 호출만 낭비했습니다."""
    monkeypatch.setattr(fsc.settings, "data_go_kr_key", lambda: "k" * 20)
    calls = []

    def fake_get(url, params, **kwargs):
        calls.append(url)
        return SimpleNamespace(
            text="<OpenAPI_ServiceResponse><cmmMsgHeader><errMsg>NO_OPENAPI_SERVICE_ERROR</errMsg>"
                 "<returnReasonCode>12</returnReasonCode></cmmMsgHeader></OpenAPI_ServiceResponse>",
            status_code=400)

    monkeypatch.setattr(fsc.publicapi, "get", fake_get)
    with pytest.raises(publicapi.PublicApiError, match="NO_OPENAPI_SERVICE_ERROR"):
        fsc.fetch_day("20260923", publicapi.CallBudget(5))
    assert len(calls) == 1


def test_활용가이드_응답_예시를_읽는다():
    from pathlib import Path

    text = (Path(__file__).parent / "fixtures" / "public" / "fsc_getStockPriceInfo_V2_guide_sample.xml").read_text()
    root = publicapi.parse_xml(SimpleNamespace(text=text, status_code=200), key="k" * 20)
    row = fsc.normalize(publicapi.xml_items(root)[0])
    assert row == {
        "code": "900290", "name": "GRT", "market": "KOSDAQ", "close": 3185.0, "change": 15.0,
        "changePct": 0.47, "volume": 493925.0, "tradingValue": 1533304201.0,
        "listedShares": 81800000.0, "marketCap": 260533000000.0, "basDt": "20260611",
    }


def test_totalCount만큼_받으면_멈춘다(monkeypatch):
    monkeypatch.setattr(fsc.settings, "data_go_kr_key", lambda: "k" * 20)
    pages = []

    def fake_get(url, params, **kwargs):
        pages.append(params["pageNo"])
        item = dict(SAMSUNG, srtnCd=f"{params['pageNo']:06d}")
        return _xml([item], 2)

    monkeypatch.setattr(fsc.publicapi, "get", fake_get)
    rows, _ = fsc.fetch_day("20260923", publicapi.CallBudget(10))
    assert pages == [1, 2] and len(rows) == 2


def test_예산이_없으면_호출하지_않는다(monkeypatch):
    monkeypatch.setattr(fsc.settings, "data_go_kr_key", lambda: "k" * 20)
    monkeypatch.setattr(fsc.publicapi, "get", lambda *a, **k: pytest.fail("호출하면 안 됩니다"))
    budget = publicapi.CallBudget(0)
    with pytest.raises(publicapi.PublicApiError, match="예산"):
        fsc.fetch_day("20260923", budget)


class _FakeStore:
    def __init__(self, latest=None):
        self.latest = latest
        self.observations, self.timeseries, self.snapshots = [], [], {}

    def latest_observation_date(self, dataset):
        return self.latest

    def read_snapshot(self, name):
        return None

    def put_observations(self, dataset, obs_date, rows, entity_key):
        self.observations.append((obs_date, len(rows)))
        return len(rows)

    def put_timeseries(self, dataset, series, points):
        self.timeseries.append((series, points))

    def put_snapshot(self, name, payload):
        self.snapshots[name] = payload

    def delete_observations_before(self, dataset, before):
        return 0


def test_이미_최신이면_호출_0회(monkeypatch):
    from datetime import datetime, timedelta

    from app import tasks

    today = datetime.now(tasks.KST).date()
    fake = _FakeStore(latest=(today - timedelta(days=1)).isoformat())
    monkeypatch.setattr(tasks, "store", fake)
    monkeypatch.setattr(tasks.fsc_service, "fetch_day", lambda *a: pytest.fail("호출하면 안 됩니다"))
    assert "최신 상태" in tasks.task_fsc_prices()


def test_발표_전이면_하루_더_거슬러_올라가_저장한다(monkeypatch):
    from app import catalog, tasks

    fake = _FakeStore(latest=None)
    monkeypatch.setattr(tasks, "store", fake)
    calls = []

    def fetch(bas_dt, budget):
        budget.take()
        calls.append(bas_dt)
        return ([] if len(calls) == 1 else [fsc.normalize(SAMSUNG)]), fsc.URL

    monkeypatch.setattr(tasks.fsc_service, "fetch_day", fetch)
    result = tasks.task_fsc_prices()

    assert len(calls) == 2 and len(fake.observations) == 1
    assert ("KOSPI.marketCap", [(fake.observations[0][0], 7100000.0)]) in fake.timeseries
    assert fake.snapshots[catalog.SNAP_FSC_PRICES_META]["endpoint"] == fsc.URL
    assert "1종목" in result
