"""
tests/test_update_script.py
QA-109: `make update`(scripts/update.sh)가 추적하지 않는 파일과의 충돌을 '로컬 커밋이 갈라졌다'로 잘못 알리던 결함.

fast-forward가 실패하면 이유와 상관없이 '갈라져 있습니다'와 `git log --graph` 확인을 안내하고 git의 오류 문구는
버렸습니다. 받는 커밋이 새로 만드는 파일과 같은 이름의 추적하지 않는 파일이 있으면 git은 'untracked working
tree files would be overwritten'으로 거부하는데, graph에는 갈라진 곳이 없어 git 명령을 직접 치지 않는 사용자는
원인을 찾을 수 없었습니다.

임시 폴더에 원격(bare)과 클론 두 개를 만들어 재현합니다. 사용자 git 설정은 읽지 않습니다.
"""
from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]

pytestmark = pytest.mark.skipif(shutil.which("git") is None, reason="git이 없어 건너뜁니다.")

_GIT_ENV = {
    "GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_NOSYSTEM": "1",
    "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@example.invalid",
    "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@example.invalid",
}


def _env() -> dict[str, str]:
    env = {k: v for k, v in os.environ.items() if not k.startswith("GIT_")}
    env.update(_GIT_ENV)
    return env


def _git(cwd: Path, *args: str) -> None:
    subprocess.run(["git", *args], cwd=cwd, env=_env(), check=True, capture_output=True)


def _commit(repo: Path, path: str, text: str) -> None:
    (repo / path).parent.mkdir(parents=True, exist_ok=True)
    (repo / path).write_text(text, encoding="utf-8")
    _git(repo, "add", path)
    _git(repo, "commit", "-q", "-m", f"add {path}")


@pytest.fixture()
def repos(tmp_path):
    """원격 origin(bare), 이 폴더 역할의 here, 다른 곳에서 push하는 other."""
    origin, here, other = tmp_path / "origin.git", tmp_path / "here", tmp_path / "other"
    _git(tmp_path, "init", "-q", "--bare", "-b", "main", str(origin))
    _git(tmp_path, "clone", "-q", str(origin), str(here))
    _git(here, "symbolic-ref", "HEAD", "refs/heads/main")
    (here / "scripts").mkdir()
    shutil.copy(ROOT / "scripts/update.sh", here / "scripts/update.sh")
    _git(here, "add", "scripts/update.sh")
    _commit(here, "README.md", "readme\n")
    _git(here, "push", "-q", "-u", "origin", "main")
    _git(tmp_path, "clone", "-q", "-b", "main", str(origin), str(other))
    return here, other


def _update(here: Path) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["bash", "scripts/update.sh"], cwd=here, env=_env(),
        capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=60,
    )


def test_QA109_추적_안_된_파일과_겹치면_갈라짐이_아니라_git의_이유를_보여_준다(repos):
    here, other = repos
    _commit(other, "docs/improve/plan.md", "from elsewhere\n")
    _git(other, "push", "-q", "origin", "main")
    (here / "docs/improve").mkdir(parents=True)
    (here / "docs/improve/plan.md").write_text("local draft\n", encoding="utf-8")   # 추적하지 않는 파일

    result = _update(here)
    out = result.stdout + result.stderr
    assert result.returncode != 0, out
    assert "갈라져" not in out, out
    assert "docs/improve/plan.md" in out, out          # git이 알려 준 실제 이유(어느 파일인지)
    assert (here / "docs/improve/plan.md").read_text(encoding="utf-8") == "local draft\n"


def test_로컬과_원격이_각각_커밋했으면_갈라짐을_안내한다(repos):
    here, other = repos
    _commit(other, "a.txt", "remote\n")
    _git(other, "push", "-q", "origin", "main")
    _commit(here, "b.txt", "local\n")

    result = _update(here)
    out = result.stdout + result.stderr
    assert result.returncode != 0, out
    assert "갈라져" in out, out
    assert "git log --oneline --graph" in out, out


def test_받을_것만_있으면_fast_forward로_받는다(repos):
    here, other = repos
    _commit(other, "a.txt", "remote\n")
    _git(other, "push", "-q", "origin", "main")

    result = _update(here)
    assert result.returncode == 0, result.stdout + result.stderr
    assert "받은 커밋" in result.stdout
    assert (here / "a.txt").exists()
