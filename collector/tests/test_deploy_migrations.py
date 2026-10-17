"""
tests/test_deploy_migrations.py
QA-016: 스키마 파일만 바뀐 배포에서 새 테이블이 생기지 않던 결함.

스키마 파일(db/migrations)은 수집기에 볼륨으로 들어가고, 수집기가 **기동할 때만** 적용됩니다. 백엔드만 바뀐
배포(예: V4 계정 테이블)에서는 `make up`이 수집기를 다시 만들지 않아 새 파일이 적용되지 않았고, 백엔드는
테이블이 없어 로그인을 받지 못했습니다(QA 스택에서 재현: schema_migrations가 V3에서 멈춤).
`make up`(과 QA 스택 up)이 기동 뒤에 대기 중인 스키마를 늘 적용해야 합니다.

QA-107: 같은 결함이 AWS 데모 배포 절차(README·compose.aws.yml 머리말)에 남아 있었습니다. 절차가 `build --pull`과
`up -d` 두 줄뿐이라, 스키마 파일과 백엔드만 바뀐 릴리스에서는 수집기가 다시 만들어지지 않아 새 스키마가 적용되지
않습니다. 문서에 적힌 배포 명령도 `up -d` 뒤에 스키마를 적용해야 합니다.
"""
from __future__ import annotations

import re
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]


def _recipe(makefile: str, target: str) -> str:
    match = re.search(rf"^{re.escape(target)}:.*?\n((?:\t.*\n|\s*\n)*)", makefile, re.M)
    assert match, f"Makefile에 {target} 타깃이 없습니다"
    return match.group(1)


def test_QA016_make_up은_기동_뒤_대기_중인_스키마를_적용한다():
    makefile = (ROOT / "Makefile").read_text(encoding="utf-8")
    assert "migrate" in _recipe(makefile, "up")
    assert "init_schema" in _recipe(makefile, "migrate")


def test_QA016_QA_스택_up도_스키마를_적용한다():
    script = (ROOT / "scripts/qa/qa-stack.sh").read_text(encoding="utf-8")
    up = script[script.index("  up)"):script.index(";;", script.index("  up)"))]
    assert "init_schema" in up


def _aws_readme_commands() -> str:
    """README '### AWS 데모 배포' 절의 첫 bash 블록."""
    readme = (ROOT / "README.md").read_text(encoding="utf-8")
    section = readme[readme.index("### AWS 데모 배포"):]
    section = section[:section.index("\n## ")]
    block = section[section.index("```bash") + len("```bash"):]
    return block[:block.index("```")]


def _aws_header_commands() -> str:
    """compose.aws.yml 머리말(services: 앞)의 명령 줄."""
    text = (ROOT / "compose.aws.yml").read_text(encoding="utf-8")
    header = text[:text.index("\nservices:")]
    return "\n".join(line for line in header.splitlines() if "docker compose" in line)


@pytest.mark.xfail(strict=True, reason="QA-107: 수정 전 재현 — AWS 배포 절차에 스키마 적용 단계가 없음")
@pytest.mark.parametrize("where", ["README", "compose.aws.yml"])
def test_QA107_AWS_배포_절차도_up_뒤에_스키마를_적용한다(where):
    commands = _aws_readme_commands() if where == "README" else _aws_header_commands()
    up = commands.index(" up -d")
    assert "init_schema" in commands[up:], f"{where}: up -d 뒤에 스키마 적용(init_schema)이 없습니다"
    # 수집기가 막 다시 만들어진 직후에는 exec가 실패할 수 있어 make migrate처럼 기다렸다 다시 시도합니다.
    schema_line = next(line for line in commands[up:].splitlines() if "init_schema" in line)
    assert "sleep" in schema_line, f"{where}: 스키마 적용에 재시도가 없습니다"


@pytest.mark.xfail(strict=True, reason="QA-107: 수정 전 재현 — compose 머리말이 docker compose up을 직접 안내")
def test_QA107_docker_compose_머리말의_실행_안내는_make_up():
    text = (ROOT / "docker-compose.yml").read_text(encoding="utf-8")
    run_line = next(line for line in text.splitlines() if line.startswith("# 실행:"))
    assert "make up" in run_line
