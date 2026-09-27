"""
tests/test_cross_site.py
브라우저에서 온 교차 출처 요청 차단.

[왜 필요한가]
수집기 토큰(COLLECTOR_API_TOKEN)은 기본값이 비어 있어, 그대로면 수집기는 인증 없이
열려 있습니다. 127.0.0.1에만 열려 있어도 **사용자가 켜 둔 브라우저**는 그 주소에
닿습니다. 다른 사이트의 페이지가

    fetch("http://127.0.0.1:8000/maintenance/purge?days=30", {method: "POST", mode: "no-cors"})

를 실행하면 응답은 못 읽어도 요청 자체는 실행됩니다(사전 확인 없는 '단순 요청').
실제로 재현했을 때 누적 이력 100행이 0행이 됐습니다 — 다시 받을 수 없는 데이터입니다.

수집기를 부르는 쪽은 백엔드(RestClient)·make(curl)·헬스체크뿐이고, 이들은 브라우저
헤더(Origin, Sec-Fetch-Site)를 보내지 않습니다. 그래서 그 헤더로 브라우저발 요청을
가려냅니다. 토큰 유무와 무관하게 동작해야 합니다.
"""
from __future__ import annotations

from datetime import datetime, timezone

import pytest
from fastapi.testclient import TestClient

from app import main


@pytest.fixture()
def client(monkeypatch):
    # 토큰을 비운 기본 상태에서도 막혀야 합니다.
    monkeypatch.setattr(main, "API_TOKEN", "")
    return TestClient(main.app, raise_server_exceptions=False)


@pytest.fixture()
def spies(monkeypatch):
    """막혀야 하는 요청이 저장 계층까지 가면 실패로 드러나게 합니다."""
    called: list[str] = []

    def purge(days):
        called.append(f"purge:{days}")
        return {"timeseries": 0, "observations": 0, "collectorRuns": 0}

    def refresh(scope="global"):
        called.append(f"refresh:{scope}")
        return datetime.now(timezone.utc)

    monkeypatch.setattr(main.store, "purge_older_than", purge)
    monkeypatch.setattr(main.store, "request_refresh", refresh)
    monkeypatch.setattr(main.store, "read_observations", lambda *a, **k: [])
    return called


# ---------------------------------------------------------------- 막아야 하는 것
@pytest.mark.parametrize("site", ["cross-site", "same-site"])
def test_다른_사이트의_POST는_403이고_실행되지_않는다(client, spies, site):
    # same-site: localhost의 다른 포트(다른 로컬 앱)도 같은 '사이트'로 취급되므로 막습니다.
    response = client.post(
        "/maintenance/purge?days=30",
        headers={"Sec-Fetch-Site": site, "Origin": "http://evil.example"},
    )
    assert response.status_code == 403
    assert spies == []


def test_Fetch_Metadata가_없는_옛_브라우저도_Origin으로_막는다(client, spies):
    response = client.post("/refresh", headers={"Origin": "http://evil.example"})
    assert response.status_code == 403
    assert spies == []


def test_Origin이_null이면_막는다(client, spies):
    # 샌드박스 iframe·file:// 페이지는 Origin: null을 보냅니다.
    response = client.post("/refresh", headers={"Origin": "null"})
    assert response.status_code == 403
    assert spies == []


def test_img_태그로_부르는_GET도_막는다(client, spies):
    # <img src>·<script src>는 Origin을 보내지 않지만 Sec-Fetch-Site는 보냅니다.
    response = client.get("/live/radar-history", headers={"Sec-Fetch-Site": "cross-site"})
    assert response.status_code == 403


def test_거부_응답은_이유를_알려준다(client, spies):
    response = client.post("/refresh", headers={"Sec-Fetch-Site": "cross-site"})
    assert "브라우저" in response.json()["detail"]


# ---------------------------------------------------------------- 통과해야 하는 것
def test_브라우저_헤더가_없는_호출은_통과한다(client, spies):
    # 백엔드·make(curl)의 호출 형태입니다.
    response = client.post("/refresh")
    assert response.status_code == 200
    assert spies == ["refresh:global"]


@pytest.mark.parametrize("site", ["none", "same-origin"])
def test_주소창_입력과_같은_출처는_통과한다(client, site):
    # none: 사용자가 주소창에 직접 입력·북마크(make up이 수집기 주소를 안내합니다)
    # same-origin: 수집기 자신의 /docs 화면
    response = client.get("/health", headers={"Sec-Fetch-Site": site})
    assert response.status_code == 200


def test_자기_자신의_Origin은_통과한다(client):
    response = client.get("/health", headers={"Origin": "http://testserver"})
    assert response.status_code == 200


def test_토큰을_설정해도_교차_출처는_막는다(monkeypatch, spies):
    monkeypatch.setattr(main, "API_TOKEN", "test-token")
    client = TestClient(main.app, raise_server_exceptions=False)
    response = client.post(
        "/refresh",
        headers={"Sec-Fetch-Site": "cross-site", "X-Service-Token": "test-token"},
    )
    assert response.status_code == 403
    assert spies == []
