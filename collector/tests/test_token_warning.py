"""
tests/test_token_warning.py
수집기 토큰(COLLECTOR_API_TOKEN)이 비어 있으면 기동할 때 경고를 남깁니다.

[왜 필요한가]
토큰이 비어 있으면 수집기는 인증 없이 모든 요청을 받습니다(로컬 개발 편의). `make setup`이
토큰을 만들어 주지만, .env를 손으로 고치다 지우거나 다른 방식으로 띄우면 비어 있을 수 있습니다.
예전에는 이 상태를 어디에도 알리지 않아, 인증이 꺼진 줄 모르고 운영할 수 있었습니다.
"""
from __future__ import annotations

import asyncio
import logging

import pytest

from app import main, settings, store


@pytest.fixture()
def quiet_lifespan(monkeypatch):
    """DB·스케줄러 없이 lifespan의 기동 단계만 돌립니다."""
    for name in ("get_pool", "close_pool", "init_schema"):
        monkeypatch.setattr(store, name, lambda *a, **k: None)
    monkeypatch.setattr(store, "purge_retired_datasets", lambda: 0)
    monkeypatch.setattr(store, "mark_stale_runs_interrupted", lambda: 0)
    monkeypatch.setattr(settings, "scheduler_enabled", lambda: False)

    def run() -> None:
        async def enter_and_exit() -> None:
            async with main.lifespan(main.app):
                pass
        asyncio.run(enter_and_exit())
    return run


def _token_warnings(caplog) -> list[logging.LogRecord]:
    return [
        r for r in caplog.records
        if r.levelno >= logging.WARNING and "COLLECTOR_API_TOKEN" in r.getMessage()
    ]


def test_토큰이_비어_있으면_기동하지_않는다(monkeypatch, quiet_lifespan):
    """
    토큰이 비면 수집기 API가 인증 없이 열립니다. 예전에는 경고 한 줄만 남기고 떴는데,
    로그를 보지 않으면 모르는 채로 계속 돌았습니다. 명시적으로 허용하지 않으면 기동 거부.
    """
    monkeypatch.setattr(main, "API_TOKEN", "")
    monkeypatch.setattr(main, "ALLOW_NO_TOKEN", False)

    with pytest.raises(RuntimeError, match="COLLECTOR_API_TOKEN"):
        quiet_lifespan()


def test_명시적으로_허용하면_경고만_남기고_기동한다(monkeypatch, caplog, quiet_lifespan):
    monkeypatch.setattr(main, "API_TOKEN", "")
    monkeypatch.setattr(main, "ALLOW_NO_TOKEN", True)

    with caplog.at_level(logging.INFO):
        quiet_lifespan()

    assert len(_token_warnings(caplog)) == 1


def test_토큰이_있으면_경고하지_않는다(monkeypatch, caplog, quiet_lifespan):
    monkeypatch.setattr(main, "API_TOKEN", "test-token")

    with caplog.at_level(logging.INFO):
        quiet_lifespan()

    assert _token_warnings(caplog) == []
