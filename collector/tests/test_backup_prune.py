"""
tests/test_backup_prune.py
QA-071: 백업 정리(scripts/db-common.sh의 prune_backups)가 `.env`의 BACKUP_KEEP을 읽지 않고,
접두어가 겹치는 다른 백업까지 세어 지우던 결함.

- 문서(.env.example·LOCAL_SETUP)는 `.env`의 BACKUP_KEEP을 늘리거나 0(지우지 않음)으로 두라고 안내하지만,
  스크립트는 셸 환경변수만 봐서 늘 기본 10개만 남겼습니다.
- `macrodash` 정리가 `macrodash-before-upgrade-….sql`처럼 이름 붙여 남긴 백업까지 세어 지웠습니다.

pg_dump·DB 없이 파일만 다루므로 test_db_scripts.py(pg_dump가 없으면 통째로 건너뜀) 밖에 둡니다.
"""
from __future__ import annotations

import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def _make_backups(folder: Path, names: list[str]) -> None:
    """names 순서대로 오래된 → 최근 mtime을 줍니다(ls -t가 보는 순서)."""
    folder.mkdir(parents=True, exist_ok=True)
    base = 1_790_000_000
    for i, name in enumerate(names):
        path = folder / name
        path.write_text("-- dump\n", encoding="utf-8")
        os.utime(path, (base + i * 60, base + i * 60))


def _regular(n: int) -> list[str]:
    return [f"macrodash-202610{day:02d}-120000.sql" for day in range(1, n + 1)]


def _prune(tmp_path: Path, env_file_lines: list[str], shell_keep: str | None = None) -> subprocess.CompletedProcess:
    env_file = tmp_path / "env"
    env_file.write_text("\n".join(["DATABASE_NAME=macrodash", *env_file_lines]) + "\n", encoding="utf-8")
    env = {k: v for k, v in os.environ.items() if k not in ("BACKUP_KEEP", "DATABASE_NAME", "DATABASE_USER")}
    env.update({"ENV_FILE": str(env_file), "BACKUP_DIR": str(tmp_path / "b"), "DB_EXEC": ""})
    if shell_keep is not None:
        env["BACKUP_KEEP"] = shell_keep
    return subprocess.run(
        ["bash", "-c", 'source scripts/db-common.sh && prune_backups "$DB_NAME"'],
        cwd=ROOT, env=env, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=30,
    )


def _left(tmp_path: Path) -> list[str]:
    return sorted(p.name for p in (tmp_path / "b").iterdir())


def test_BACKUP_KEEP이_어디에도_없으면_최근_10개를_남긴다(tmp_path):
    _make_backups(tmp_path / "b", _regular(12))
    result = _prune(tmp_path, [])
    assert result.returncode == 0, result.stderr
    assert _left(tmp_path) == _regular(12)[2:]


def test_QA071_env_파일의_BACKUP_KEEP_0이면_지우지_않는다(tmp_path):
    _make_backups(tmp_path / "b", _regular(12))
    result = _prune(tmp_path, ["BACKUP_KEEP=0"])
    assert result.returncode == 0, result.stderr
    assert _left(tmp_path) == _regular(12)
    assert "삭제" not in result.stdout


def test_QA071_env_파일의_BACKUP_KEEP_수만큼_남긴다(tmp_path):
    _make_backups(tmp_path / "b", _regular(12))
    result = _prune(tmp_path, ["BACKUP_KEEP=3"])
    assert result.returncode == 0, result.stderr
    assert _left(tmp_path) == _regular(12)[-3:]


def test_셸_환경변수가_env_파일보다_앞선다(tmp_path):
    # 다른 설정(DATABASE_USER·DATABASE_NAME)과 같은 순서: 환경변수 → .env → 기본값.
    _make_backups(tmp_path / "b", _regular(12))
    result = _prune(tmp_path, ["BACKUP_KEEP=3"], shell_keep="0")
    assert result.returncode == 0, result.stderr
    assert _left(tmp_path) == _regular(12)


def test_QA071_접두어가_겹치는_이름_붙인_백업은_세지도_지우지도_않는다(tmp_path):
    named = "macrodash-before-upgrade-20260901-120000.sql"
    _make_backups(tmp_path / "b", [named, *_regular(10)])
    result = _prune(tmp_path, [])
    assert result.returncode == 0, result.stderr
    assert named in _left(tmp_path)
    assert "삭제" not in result.stdout
