"""
tests/test_qa_stack_isolation.py
QA-072: QA 스택의 '외부 호출 차단'이 백엔드에는 걸려 있지 않던 결함.

차단 프록시는 수집기에만 붙습니다(백엔드의 AI SDK는 HTTP_PROXY를 보지 않음). 백엔드가 유료 AI·메일을
부르지 않게 막는 것은 키가 비어 있다는 것 하나뿐인데, compose 보간은 셸 환경이 env 파일보다 앞서서
셸 프로필에 export한 ANTHROPIC_API_KEY가 `.env.qa`의 빈 값을 이겼습니다. 그 상태로 퍼저(fuzz-api.py)가
운영자로 /api/ai/report·/api/ai/test를 수십 번 부르면 실제 계정으로 과금됩니다.

QA 덧붙임 파일이 백엔드의 외부 키를 보간 없는 빈 값("")으로 고정하는지 봅니다. 보간이 없으면
셸 값이 끼어들 자리가 없습니다.
"""
from __future__ import annotations

import re
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]

# 백엔드가 밖으로 나가는 호출(AI 엔진·메일)을 여는 값. COLLECTOR_API_TOKEN은 내부 토큰이라 뺍니다.
_INTERNAL = {"COLLECTOR_API_TOKEN"}


def _backend_env(path: Path) -> dict:
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    return (data.get("services", {}).get("backend") or {}).get("environment") or {}


def _external_backend_keys() -> set[str]:
    """docker-compose.yml의 backend가 받는 값 가운데 외부 호출을 여는 것(엔진이 늘면 같이 늘어남)."""
    keys = set(_backend_env(ROOT / "docker-compose.yml"))
    external = {k for k in keys if re.search(r"(_API_KEY|_API_TOKEN|_ACCOUNT_ID)$", k)} - _INTERNAL
    return external | {"MAIL_HOST"}


def test_외부_키_목록이_비어_있지_않다():
    # 아래 검사가 빈 목록으로 통과해 버리지 않게 합니다.
    assert {"ANTHROPIC_API_KEY", "NVIDIA_API_KEY", "MAIL_HOST"} <= _external_backend_keys()


def test_QA072_QA_스택_백엔드는_외부_키를_보간_없는_빈_값으로_고정한다():
    override = _backend_env(ROOT / "scripts/qa/docker-compose.qa.yml")
    not_pinned = {k: override.get(k) for k in sorted(_external_backend_keys()) if override.get(k) != ""}
    assert not_pinned == {}


def test_QA072_env_qa_템플릿도_같은_키를_비운다():
    script = (ROOT / "scripts/qa/qa-stack.sh").read_text(encoding="utf-8")
    template = script[script.index("cat > .env.qa <<ENV"):script.index("\nENV\n")]
    missing = [k for k in sorted(_external_backend_keys()) if not re.search(rf"^{k}=$", template, re.M)]
    assert missing == []
