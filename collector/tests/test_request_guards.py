"""
tests/test_request_guards.py
요청 경계의 검증 — Host 허용 목록, 티커 심볼 형식.
"""
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app import main


@pytest.fixture()
def client(monkeypatch):
    monkeypatch.setattr(main, "API_TOKEN", "")
    return TestClient(main.app, raise_server_exceptions=False)


def test_모르는_Host_헤더는_400이다(client):
    """
    DNS 리바인딩: attacker.example:8000을 가리키던 이름이 127.0.0.1로 바뀌면 브라우저는
    '같은 출처' 요청을 보내고 Sec-Fetch-Site 검사도 통과합니다. 그 요청의 Host는 공격자
    도메인이므로 여기서 끊습니다.
    """
    assert client.get("/health", headers={"Host": "attacker.example"}).status_code == 400
    assert client.get("/health", headers={"Host": "localhost:8000"}).status_code == 200
    assert client.get("/health", headers={"Host": "127.0.0.1:8000"}).status_code == 200
    assert client.get("/health", headers={"Host": "collector:8000"}).status_code == 200


@pytest.mark.parametrize("symbol", ["AAPL/etc", "VIX%20OR%201", "a" * 25, "AAPL;rm", "$(id)"])
def test_야후_형식이_아닌_심볼은_400이다(client, monkeypatch, symbol):
    monkeypatch.setattr(main.market_service, "collect_ticker", lambda *a, **k: pytest.fail("호출하면 안 됩니다"))
    assert client.get(f"/live/ticker/{symbol}").status_code == 400


@pytest.mark.parametrize("symbol", ["^VIX", "ZT=F", "BRK-B", "005930.KS"])
def test_야후_형식_심볼은_통과한다(client, monkeypatch, symbol):
    seen = []
    monkeypatch.setattr(main.market_service, "collect_ticker", lambda s, p: seen.append(s) or {"ok": True})
    assert client.get(f"/live/ticker/{symbol}").status_code == 200
    assert seen == [symbol]
