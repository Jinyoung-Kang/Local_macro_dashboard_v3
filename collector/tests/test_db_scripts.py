"""
tests/test_db_scripts.py
DB 백업·복원 스크립트(scripts/db-backup.sh · scripts/db-restore.sh).

[왜 필요한가]
예전 `make restore`는 데이터가 있는 DB에 덤프를 그대로 부었습니다. 덤프에는 기존 표를 지우는
문장이 없어 CREATE·COPY가 오류 21건으로 실패했는데도 psql은 0으로 끝났고, 값은 백업 시점으로
돌아오지 않았습니다. `make backup`도 pg_dump가 실패하면 0바이트 파일을 남기고 0으로 끝났습니다.

고정하는 것
  - 복원하면 DB가 **백업 시점과 같아진다** (값·행 수 모두)
  - 복원 전에 지금 DB를 먼저 백업해 둔다
  - 복원 도중 실패하면 **아무것도 바뀌지 않는다** (한 트랜잭션)
  - 입력이 중간에 끊겨도(창을 닫음·Ctrl-C·읽기 오류) 반쯤 복원된 상태를 확정하지 않는다
  - 끝까지 기록되지 않은 백업·확인 없는 실행은 거부한다
  - 백업이 실패하면 파일을 남기지 않고 0이 아닌 코드로 끝난다

각 테스트는 전용 임시 데이터베이스를 만들어 씁니다. 복원은 public 스키마를 통째로
바꾸므로, 다른 테스트가 쓰는 테스트 DB를 건드리면 안 됩니다.
"""
from __future__ import annotations

import os
import shutil
import subprocess
import uuid
from pathlib import Path
from urllib.parse import urlparse

import psycopg
import pytest

from app import migrations

ROOT = Path(__file__).resolve().parents[2]
BACKUP = ROOT / "scripts" / "db-backup.sh"
RESTORE = ROOT / "scripts" / "db-restore.sh"

pytestmark = pytest.mark.skipif(
    shutil.which("pg_dump") is None or shutil.which("psql") is None,
    reason="pg_dump·psql 클라이언트가 없어 백업·복원 스크립트 테스트를 건너뜁니다.",
)


@pytest.fixture()
def scratch_db(database_url):
    """이 테스트만 쓰는 데이터베이스. 저장소의 마이그레이션을 적용한 상태로 줍니다."""
    name = f"scripts_{uuid.uuid4().hex[:10]}"
    with psycopg.connect(database_url, autocommit=True) as admin:
        admin.execute(f'CREATE DATABASE "{name}"')
    url = urlparse(database_url)
    db_url = url._replace(path=f"/{name}").geturl()
    with psycopg.connect(db_url, autocommit=True) as conn:
        migrations.apply_pending(conn, migrations.discover(ROOT / "db" / "migrations"))
    try:
        yield name, db_url
    finally:
        with psycopg.connect(database_url, autocommit=True) as admin:
            admin.execute(f'DROP DATABASE IF EXISTS "{name}" WITH (FORCE)')


def _env(database_url: str, db_name: str, backup_dir: Path) -> dict[str, str]:
    """스크립트가 컨테이너 대신 테스트 DB에 직접 붙도록 하는 환경."""
    url = urlparse(database_url)
    env = dict(os.environ)
    env.update({
        "DB_EXEC": "",                      # docker compose exec 없이 psql·pg_dump를 바로 실행
        "RESTORE_STOP_SERVICES": "",        # 테스트에는 멈출 컨테이너가 없습니다
        "DATABASE_USER": url.username or "macro",
        "DATABASE_NAME": db_name,
        "PGHOST": url.hostname or "localhost",
        "PGPORT": str(url.port or 5432),
        "PGPASSWORD": url.password or "",
        "BACKUP_DIR": str(backup_dir),
    })
    return env


def _run(script: Path, *args: str, env: dict[str, str]) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["bash", str(script), *args], env=env, cwd=ROOT,
        stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=120,
    )


def _snapshot_values(db_url: str) -> dict[str, str | None]:
    with psycopg.connect(db_url) as conn:
        rows = conn.execute("SELECT name, payload->>'v' FROM snapshots ORDER BY name").fetchall()
    return {name: value for name, value in rows}


def _set(db_url: str, sql: str) -> None:
    with psycopg.connect(db_url, autocommit=True) as conn:
        conn.execute(sql)


def _backups(directory: Path) -> list[Path]:
    return sorted(directory.glob("*.sql"))


# ------------------------------------------------------------------------ 백업
def test_백업이_실패하면_파일을_남기지_않고_실패로_끝난다(database_url, tmp_path):
    result = _run(BACKUP, env=_env(database_url, "no_such_database_for_backup", tmp_path))

    assert result.returncode != 0
    assert list(tmp_path.iterdir()) == []            # 0바이트 .sql도, .partial도 남지 않습니다
    assert "백업 실패" in result.stderr


def test_백업은_끝까지_기록된_덤프를_남긴다(database_url, scratch_db, tmp_path):
    name, db_url = scratch_db
    _set(db_url, "INSERT INTO snapshots(name, payload, collected_at) VALUES ('a', '{\"v\":\"1\"}', now())")

    result = _run(BACKUP, env=_env(database_url, name, tmp_path))

    assert result.returncode == 0, result.stderr
    files = _backups(tmp_path)
    assert len(files) == 1
    assert "PostgreSQL database dump complete" in files[0].read_text(encoding="utf-8")


# ------------------------------------------------------------------------ 복원
def test_복원하면_백업_시점과_같아지고_복원_전_상태도_남긴다(database_url, scratch_db, tmp_path):
    name, db_url = scratch_db
    env = _env(database_url, name, tmp_path)
    _set(db_url, "INSERT INTO snapshots(name, payload, collected_at) VALUES ('macro', '{\"v\":\"backup-time\"}', now())")
    assert _run(BACKUP, env=env).returncode == 0
    backup_file = _backups(tmp_path)[0]

    # 백업 뒤에 값이 망가지고 행이 늘었습니다.
    _set(db_url, "UPDATE snapshots SET payload = '{\"v\":\"corrupted\"}'")
    _set(db_url, "INSERT INTO snapshots(name, payload, collected_at) VALUES ('extra', '{}', now())")

    result = _run(RESTORE, str(backup_file), "--yes", env=env)

    assert result.returncode == 0, result.stdout + result.stderr
    assert _snapshot_values(db_url) == {"macro": "backup-time"}
    safety = [f for f in _backups(tmp_path) if f.name.startswith("pre-restore-")]
    assert len(safety) == 1                          # 되돌릴 수 있게 복원 직전 상태를 남깁니다
    assert "corrupted" in safety[0].read_text(encoding="utf-8")


def test_복원_도중_실패하면_아무것도_바뀌지_않는다(database_url, scratch_db, tmp_path):
    name, db_url = scratch_db
    env = _env(database_url, name, tmp_path)
    _set(db_url, "INSERT INTO snapshots(name, payload, collected_at) VALUES ('macro', '{\"v\":\"before\"}', now())")
    assert _run(BACKUP, env=env).returncode == 0
    good = _backups(tmp_path)[0]
    broken = tmp_path / "broken.sql.txt"
    # 끝 표시는 그대로 두고 중간 문장만 망가뜨립니다(복원이 중간까지 진행된 뒤 실패하는 상황).
    broken.write_text(good.read_text(encoding="utf-8").replace(
        "CREATE TABLE public.snapshots", "CREATE TABLEX public.snapshots", 1), encoding="utf-8")
    _set(db_url, "UPDATE snapshots SET payload = '{\"v\":\"current\"}'")

    result = _run(RESTORE, str(broken), "--yes", env=env)

    assert result.returncode != 0
    assert _snapshot_values(db_url) == {"macro": "current"}   # 스키마를 지운 것까지 되돌아갔습니다


def test_끝까지_기록되지_않은_백업은_복원하지_않는다(database_url, scratch_db, tmp_path):
    name, db_url = scratch_db
    env = _env(database_url, name, tmp_path)
    _set(db_url, "INSERT INTO snapshots(name, payload, collected_at) VALUES ('macro', '{\"v\":\"current\"}', now())")
    truncated = tmp_path / "truncated.sql.txt"
    truncated.write_text("-- PostgreSQL database dump\nCREATE TABLE public.x (id int);\n", encoding="utf-8")

    result = _run(RESTORE, str(truncated), "--yes", env=env)

    assert result.returncode != 0
    assert _snapshot_values(db_url) == {"macro": "current"}
    assert _backups(tmp_path) == []                  # 시작도 하지 않았으므로 안전 백업도 없습니다


def test_확인_없이는_복원하지_않는다(database_url, scratch_db, tmp_path):
    name, db_url = scratch_db
    env = _env(database_url, name, tmp_path)
    _set(db_url, "INSERT INTO snapshots(name, payload, collected_at) VALUES ('macro', '{\"v\":\"current\"}', now())")
    assert _run(BACKUP, env=env).returncode == 0
    backup_file = _backups(tmp_path)[0]
    _set(db_url, "UPDATE snapshots SET payload = '{\"v\":\"changed\"}'")

    result = _run(RESTORE, str(backup_file), env=env)   # --yes 없음, 표준입력은 터미널이 아님

    assert result.returncode != 0
    assert _snapshot_values(db_url) == {"macro": "changed"}


def test_입력이_중간에_끊기면_반쯤_복원된_상태를_확정하지_않는다(database_url, scratch_db, tmp_path):
    """
    psql은 --single-transaction이어도 입력이 오류 없이 끝나면(EOF) COMMIT을 보냅니다. 복원 중에
    터미널을 닫거나 Ctrl-C를 누르면 컨테이너 안의 psql은 입력이 끊긴 채로 남아, 스키마를 지우고
    일부만 적재한 상태가 확정될 수 있었습니다. 입력을 COPY 한가운데서 끊어 그 상황을 흉내냅니다.
    """
    name, db_url = scratch_db
    env = _env(database_url, name, tmp_path)
    _set(db_url, "INSERT INTO snapshots(name, payload, collected_at) VALUES "
                 "('a', '{\"v\":\"1\"}', now()), ('b', '{\"v\":\"2\"}', now())")
    assert _run(BACKUP, env=env).returncode == 0
    backup_file = _backups(tmp_path)[0]
    _set(db_url, "DELETE FROM snapshots; INSERT INTO snapshots(name, payload, collected_at) "
                 "VALUES ('macro', '{\"v\":\"current\"}', now())")

    # psql에만 표준입력을 "snapshots의 첫 데이터 줄"까지 넘기고 끊습니다(pg_dump는 그대로).
    wrapper = tmp_path / "cut-stdin.sh"
    wrapper.write_text(
        "#!/bin/bash\n"
        'if [ "$1" = "psql" ]; then\n'
        "  awk '{print} /^COPY public\\.snapshots /{getline; print; exit}' | \"$@\"\n"
        "else\n"
        '  exec "$@"\n'
        "fi\n", encoding="utf-8")
    wrapper.chmod(0o755)
    env["DB_EXEC"] = str(wrapper)

    result = _run(RESTORE, str(backup_file), "--yes", env=env)

    assert result.returncode != 0
    assert "복원했습니다" not in result.stdout
    assert _snapshot_values(db_url) == {"macro": "current"}   # 스키마를 지운 것까지 되돌아갔습니다


def test_복원은_돌고_있는_서비스를_멈췄다가_다시_켠다(database_url, scratch_db, tmp_path):
    """
    컨테이너 모드에서는 쓰기가 겹치지 않게 수집기·백엔드를 멈춥니다. 예전에는
    `docker compose ps … | grep -q`를 pipefail 아래에서 써서, grep이 먼저 찾고 끝나면 docker가
    SIGPIPE로 죽어 "돌고 있지 않음"으로 판정 — 수집기를 멈추지 않고 복원했습니다.
    가짜 docker가 첫 줄을 쓴 뒤 잠시 쉬었다가 나머지를 써서 그 상황을 만듭니다.
    """
    name, db_url = scratch_db
    env = _env(database_url, name, tmp_path)
    _set(db_url, "INSERT INTO snapshots(name, payload, collected_at) VALUES ('macro', '{\"v\":\"1\"}', now())")
    assert _run(BACKUP, env=env).returncode == 0
    backup_file = _backups(tmp_path)[0]

    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    log = tmp_path / "docker.log"
    fake_docker = bin_dir / "docker"
    fake_docker.write_text(
        "#!/bin/bash\n"
        'echo "$*" >> "$DOCKER_LOG"\n'
        'if [ "$1 $2" = "compose ps" ]; then echo collector; sleep 0.3; echo backend; echo frontend; fi\n',
        encoding="utf-8")
    fake_docker.chmod(0o755)
    passthrough = tmp_path / "passthrough.sh"          # 컨테이너 모드로 돌리되 psql·pg_dump는 바로 실행
    passthrough.write_text('#!/bin/bash\nexec "$@"\n', encoding="utf-8")
    passthrough.chmod(0o755)
    env.update({
        "DB_EXEC": str(passthrough),
        "RESTORE_STOP_SERVICES": "collector backend",
        "PATH": f"{bin_dir}:{env['PATH']}",
        "DOCKER_LOG": str(log),
    })

    result = _run(RESTORE, str(backup_file), "--yes", env=env)

    assert result.returncode == 0, result.stdout + result.stderr
    calls = log.read_text(encoding="utf-8").splitlines()
    assert "compose stop collector backend" in calls
    assert "compose start collector backend" in calls


@pytest.mark.parametrize("line", [
    'DATABASE_NAME="{name}"',
    "DATABASE_NAME='{name}'",
    "DATABASE_NAME={name}  # 로컬 DB",
    "DATABASE_NAME={name}\r",
])
def test_env_파일의_값을_docker_compose와_같게_읽는다(database_url, scratch_db, tmp_path, line):
    """따옴표·줄 끝 주석·윈도 줄바꿈이 있어도 compose가 읽는 DB 이름과 같은 DB를 백업합니다."""
    name, _ = scratch_db
    env = _env(database_url, name, tmp_path)
    env.pop("DATABASE_NAME")
    env_file = tmp_path / "test.env"
    env_file.write_bytes(("DATABASE_USER=macro\n" + line.format(name=name) + "\n").encode("utf-8"))
    env["ENV_FILE"] = str(env_file)

    result = _run(BACKUP, env=env)

    assert result.returncode == 0, result.stderr
    assert _backups(tmp_path)[0].name.startswith(f"{name}-")
