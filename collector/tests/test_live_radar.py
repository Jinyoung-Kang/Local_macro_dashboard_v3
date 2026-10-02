"""
tests/test_live_radar.py
/live/radar — 백엔드가 auto 모드에서 부르는 즉시 수집 경로의 저장 규칙.
"""
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app import main


@pytest.fixture()
def client(monkeypatch):
    monkeypatch.setattr(main, "API_TOKEN", "")
    return TestClient(main.app, raise_server_exceptions=False)


@pytest.fixture()
def spies(monkeypatch):
    saved: dict[str, list] = {"snapshots": [], "history": []}
    rows = [{"rank": 1, "code": "005930", "name": "삼성전자", "price": 71000.0,
             "changePct": 1.2, "netAmountEok": 1200.5, "source": "Daum", "collectedAt": "x"}]
    monkeypatch.setattr(
        main.radar_service, "collect_radar_ranking",
        lambda *a, **k: {"source": "Daum", "sourceKind": "daum", "isHistorical": False, "rows": rows},
    )
    monkeypatch.setattr(main.store, "put_snapshot", lambda name, payload, **k: saved["snapshots"].append(name))
    monkeypatch.setattr(main.radar_service, "accumulate_history",
                        lambda rows, *combo: saved["history"].append(combo) or len(rows))
    return saved


def test_기본_조건의_결과만_저장본과_이력에_쓴다(client, spies):
    response = client.get("/live/radar?market=KOSPI&investor=외국인&tradeType=순매수")
    assert response.status_code == 200
    assert spies["snapshots"] == ["radar.scanner.KOSPI.외국인.순매수.TODAY"]
    assert spies["history"] == [("KOSPI", "외국인", "순매수", "TODAY")]


@pytest.mark.parametrize("query", ["topN=5", "topN=50", "targetDate=2026-09-01"])
def test_다른_조건의_조회는_기본_저장본을_덮지_않는다(client, spies, query):
    """
    백엔드는 topN≠30이나 과거 날짜 요청이면 저장본을 건너뛰지만, 수집기는 그 결과도
    기본 이름(radar.scanner.KOSPI.외국인.순매수.TODAY)에 저장했습니다. 다음 15분 동안
    5행짜리나 2020년 랭킹이 '현재 KOSPI 외국인 순매수'로 나갔고, 과거 날짜 결과는
    isHistorical=False라 이력에도 쌓였습니다.
    """
    response = client.get(f"/live/radar?market=KOSPI&investor=외국인&tradeType=순매수&{query}")
    assert response.status_code == 200
    assert response.json()["rows"], "결과 자체는 그대로 돌려줍니다"
    assert spies["snapshots"] == []
    assert spies["history"] == []


def test_시장_이름은_대문자로_정규화하고_모르면_400이다(client, spies):
    assert client.get("/live/radar?market=kospi").status_code == 200
    assert spies["snapshots"] == ["radar.scanner.KOSPI.외국인.순매수.TODAY"]
    assert client.get("/live/radar?market=NYSE").status_code == 400
