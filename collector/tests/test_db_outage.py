"""
tests/test_db_outage.py
QA-010 — PostgreSQL이 끊겼을 때 수집기의 응답.

QA 스택에서 postgres를 멈추자 /health는 계속 {"status": "ok"}였고, /status는 **30초** 뒤 평문
"Internal Server Error"(500)로 답했습니다(풀의 연결 대기 기본값 30초 + 예외 처리 없음). 백엔드의
/api/health는 같은 상황에서 5초 안에 503 + database:unreachable을 돌려줍니다. 수집기도 같아야 합니다.
"""
from __future__ import annotations

import psycopg
import pytest
from fastapi.testclient import TestClient

from app import main, store


@pytest.fixture()
def client(monkeypatch):
    monkeypatch.setattr(main, "API_TOKEN", "")
    return TestClient(main.app, raise_server_exceptions=False)


def test_QA010_health_reports_database_unreachable(client, monkeypatch):
    def refuse():
        raise psycopg.OperationalError("connection refused")
    monkeypatch.setattr(main.store, "ping", refuse, raising=False)

    response = client.get("/health")

    assert response.status_code == 503
    assert response.json()["database"] == "unreachable"
    assert response.json()["status"] == "degraded"


def test_QA010_database_error_is_503_json_not_plain_500(client, monkeypatch):
    def refuse(**kwargs):
        raise psycopg.OperationalError("connection refused")
    monkeypatch.setattr(main.store, "store_stats", refuse)

    response = client.get("/status")

    assert response.status_code == 503
    assert response.headers["content-type"].startswith("application/json")
    assert "데이터베이스" in response.json()["detail"]
    assert "connection refused" not in response.text        # 원문·스택은 로그에만


def test_QA010_pool_wait_is_short():
    """연결을 기다리는 시간이 30초면 DB 장애 때 요청 하나가 30초를 삼킵니다(백엔드의 제어 타임아웃 3초 초과)."""
    assert store.POOL_WAIT_SECONDS <= 5.0
