"""
tests/test_ci_config.py
QA-108: CI의 pip-audit가 이미지에 들어가는 판이 아니라 그날 새로 해석한 판을 검사하던 결함.

`pip-audit -r requirements.txt`는 임시 가상환경에서 requirements.txt를 constraints 없이 다시 해석해, 그날 PyPI의
최신 간접 의존성(starlette·pydantic 등)을 검사합니다. 그런데 수집기 이미지(Dockerfile)와 CI 테스트는
constraints.txt의 고정판을 설치합니다. 고정판에 취약점이 공개되고 상위 판에서 고쳐지면, CI 감사는 늘 수정판을
보고 '취약점 없음'으로 통과합니다. 감사 대상은 이미지와 같은 constraints.txt여야 합니다.
"""
from __future__ import annotations

from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]


def _pip_audit_step() -> dict:
    workflow = yaml.safe_load((ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8"))
    steps = workflow["jobs"]["collector"]["steps"]
    matches = [s for s in steps if "pip-audit" in (s.get("run") or "")]
    assert len(matches) == 1, "수집기 job에 pip-audit 단계가 하나 있어야 합니다"
    return matches[0]


def test_취약점_점검은_빌드를_막지_않는다():
    # 정책(경고만): 새 공시마다 모든 PR이 빨개지면 아무도 보지 않게 됩니다.
    assert _pip_audit_step().get("continue-on-error") is True


@pytest.mark.xfail(strict=True, reason="QA-108: 수정 전 재현 — requirements.txt를 다시 해석해 감사")
def test_QA108_pip_audit는_이미지와_같은_constraints_고정판을_감사한다():
    run = _pip_audit_step()["run"]
    audit = run[run.rindex("pip-audit "):]          # 'pip install pip-audit==…' 뒤의 실제 감사 명령
    assert "-r constraints.txt" in audit
    # 다시 해석하지 않고(--no-deps) 설치도 하지 않고(--disable-pip) 고정판 그대로 봅니다.
    assert "--no-deps" in audit and "--disable-pip" in audit
    assert "requirements.txt" not in audit
