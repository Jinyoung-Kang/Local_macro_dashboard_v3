"""
tests/test_cross_site.py
브라우저에서 온 교차 출처 요청 차단.

[왜 필요한가]
수집기는 127.0.0.1에만 열려 있어도 **사용자가 켜 둔 브라우저**는 그 주소에 닿습니다.
다른 사이트의 페이지가

    fetch("http://127.0.0.1:8000/collect?group=all", {method: "POST", mode: "no-cors"})

를 실행하면 응답은 못 읽어도 요청 자체는 실행됩니다(사전 확인 없는 '단순 요청') — 외부 API
호출과 저장이 일어납니다. 토큰을 비우고 띄운 로컬 개발 기동(COLLECTOR_ALLOW_NO_TOKEN=1)에서는
인증 없이 그대로 통과합니다. 처음 재현했을 때는 (지금은 없앤) /maintenance/purge로 다시 받을 수
없는 누적 이력 100행이 0행이 됐습니다.

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
    # 토큰을 비운 상태(로컬 개발용 COLLECTOR_ALLOW_NO_TOKEN=1)에서도 막혀야 합니다.
    monkeypatch.setattr(main, "API_TOKEN", "")
    # /health는 DB까지 확인합니다(QA-010). 이 테스트들은 요청 경계만 보므로 DB 없이 돌게 둡니다(CI에는 운영 DB가 없음).
    monkeypatch.setattr(main.store, "ping", lambda: None)
    return TestClient(main.app, raise_server_exceptions=False)


@pytest.fixture()
def spies(monkeypatch):
    """
    막혀야 하는 요청이 수집·저장·외부 호출까지 가면 실패로 드러나게 합니다.

    실제 함수 대신 기록만 하므로, 가드가 뚫리는 회귀가 생겨도 테스트가 외부 API(Daum 등)를
    부르거나 DB에 쓰지 않습니다.
    """
    called: list[str] = []

    def run_group(group=None, task_name=None):
        called.append(f"collect:{group or task_name}")
        return {}

    def refresh(scope="global"):
        called.append(f"refresh:{scope}")
        return datetime.now(timezone.utc)

    def daum_intraday(lookback_minutes=30):
        called.append(f"live:daum-intraday:{lookback_minutes}")
        return {}

    monkeypatch.setattr(main.tasks, "run_group", run_group)
    monkeypatch.setattr(main.store, "request_refresh", refresh)
    # /live/daum-intraday는 Daum으로 바로 요청합니다(저장 없음). 핸들러가 호출 시점에 찾으므로 여기서 바꿔 둡니다.
    monkeypatch.setattr(main.krx_service, "collect_daum_intraday_acceleration", daum_intraday)
    return called


# ---------------------------------------------------------------- 막아야 하는 것
@pytest.mark.parametrize("site", ["cross-site", "same-site"])
def test_다른_사이트의_POST는_403이고_실행되지_않는다(client, spies, site):
    # same-site: localhost의 다른 포트(다른 로컬 앱)도 같은 '사이트'로 취급되므로 막습니다.
    response = client.post(
        "/collect?group=fast",
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
    response = client.get("/live/daum-intraday", headers={"Sec-Fetch-Site": "cross-site"})
    assert response.status_code == 403
    assert spies == []


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
    monkeypatch.setattr(main.store, "ping", lambda: None)
    client = TestClient(main.app, raise_server_exceptions=False)
    response = client.post(
        "/refresh",
        headers={"Sec-Fetch-Site": "cross-site", "X-Service-Token": "test-token"},
    )
    assert response.status_code == 403
    assert spies == []
