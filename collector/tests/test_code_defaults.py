"""
tests/test_code_defaults.py
코드 기본값에 자리표시 값·테스트용 값을 두지 않는다(CLAUDE.md '예시 이메일 같은 자리표시 값도 코드 기본값으로 두지 않는다').
"""
from __future__ import annotations

import pytest

from app import main
from app.services import cot


@pytest.mark.xfail(strict=True, reason="QA-038: CFTC 요청 User-Agent에 자리표시 이메일(admin@example.com)")
def test_QA038_CFTC_요청에_자리표시_연락처를_싣지_않는다(monkeypatch):
    seen: dict = {}

    class _Session:
        def get(self, url, params=None, timeout=None, headers=None):
            seen.update(headers or {})

            class _Ok:
                def raise_for_status(self):
                    return None
            return _Ok()

    monkeypatch.setattr(cot, "get_session", lambda: _Session())
    cot._get_with_retry({"$limit": 1})

    assert "example.com" not in seen.get("User-Agent", "")


@pytest.mark.xfail(strict=True, reason="QA-038: 운영 Host 허용 목록 기본값에 테스트용 testserver")
def test_QA038_운영_Host_허용_목록_기본값에_테스트용_이름이_없다():
    assert "testserver" not in main.parse_allowed_hosts(None)
