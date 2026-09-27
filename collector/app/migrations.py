"""
app/migrations.py
DB 스키마 버전 관리 — db/migrations/V{번호}__{설명}.sql을 번호 순서대로 한 번씩 적용합니다.

[왜 필요한가]
예전에는 V1__init.sql 하나를 기동할 때마다 다시 실행했습니다(CREATE … IF NOT EXISTS).
그래서 **이미 있는 DB를 바꿀 방법이 없었습니다** — 예컨대 쓰지 않는 인덱스를 지우는 변경을
넣어도, 다음 기동 때 V1이 그 인덱스를 다시 만듭니다. 이제 파일마다 한 번만 적용하고
적용 기록(schema_migrations)을 남깁니다.

[누가 적용하나]
쓰기는 수집기의 몫이라는 원칙대로 수집기가 기동할 때 적용합니다. 백엔드는 읽기만 하므로
마이그레이션 도구(의존성)를 따로 두지 않습니다.

[규칙]
- 파일 이름은 V{정수}__{설명}.sql. 번호 순서(문자열 순서가 아님: V10은 V9 다음)로 적용합니다.
- 대기 중인 파일 전체를 한 트랜잭션으로 적용합니다. 하나라도 실패하면 모두 되돌리고
  기동을 멈춥니다 — 절반만 바뀐 스키마로 도는 것보다 낫습니다.
- **이미 적용한 파일은 고치지 마세요.** 새 번호로 추가합니다(내용이 바뀌면 경고를 남깁니다).
- 모든 파일은 여러 번 실행해도 안전하게(IF EXISTS / IF NOT EXISTS) 씁니다. 새 DB는
  postgres 컨테이너가 초기화할 때 V1을 먼저 실행하고, 테스트·CI는 psql로 직접 적용합니다.
- 두 프로세스가 동시에 적용하지 않도록 트랜잭션 단위 advisory lock을 잡습니다.
"""
from __future__ import annotations

import hashlib
import logging
import re
from dataclasses import dataclass
from pathlib import Path

logger = logging.getLogger(__name__)

TABLE = "schema_migrations"

# 다른 기능의 advisory lock과 겹치지 않게 고정한 임의의 키입니다.
_LOCK_KEY = 7302_1945

_FILE_NAME = re.compile(r"^V(\d+)__(.+)\.sql$")


@dataclass(frozen=True)
class Migration:
    version: int
    description: str
    sql: str
    checksum: str


def discover(directory: Path) -> list[Migration]:
    """
    디렉터리의 마이그레이션 파일을 번호 순서로 읽습니다.

    :raises ValueError: 같은 번호가 둘 이상일 때(어느 쪽이 맞는지 알 수 없습니다)
    """
    found: dict[int, Migration] = {}
    for path in sorted(directory.glob("V*__*.sql")):
        match = _FILE_NAME.match(path.name)
        if not match:
            continue
        version = int(match.group(1))
        if version in found:
            raise ValueError(f"마이그레이션 번호가 겹칩니다: V{version} ({path.name})")
        sql = path.read_text(encoding="utf-8")
        found[version] = Migration(
            version=version,
            description=match.group(2).replace("_", " "),
            sql=sql,
            checksum=hashlib.sha256(sql.encode("utf-8")).hexdigest(),
        )
    return [found[version] for version in sorted(found)]


def apply_pending(conn, migrations: list[Migration]) -> list[int]:
    """
    아직 적용하지 않은 마이그레이션을 번호 순서로 적용합니다.

    :param conn: psycopg 연결 (행을 dict로 받는 row_factory)
    :returns: 이번에 적용한 번호 목록 (없으면 빈 목록)
    """
    applied_now: list[int] = []
    with conn.transaction():
        conn.execute("SELECT pg_advisory_xact_lock(%s)", (_LOCK_KEY,))
        conn.execute(
            f"CREATE TABLE IF NOT EXISTS {TABLE} ("
            " version     INTEGER PRIMARY KEY,"
            " description TEXT        NOT NULL,"
            " checksum    TEXT        NOT NULL,"
            " applied_at  TIMESTAMPTZ NOT NULL DEFAULT now())"
        )
        recorded = {
            row["version"]: row["checksum"]
            for row in conn.execute(f"SELECT version, checksum FROM {TABLE}").fetchall()
        }
        for migration in migrations:
            if migration.version in recorded:
                if recorded[migration.version] != migration.checksum:
                    logger.warning(
                        "이미 적용한 마이그레이션 V%d의 내용이 바뀌었습니다. 적용한 파일은 고치지 말고 "
                        "새 번호로 추가하세요.", migration.version,
                    )
                continue
            conn.execute(migration.sql)
            conn.execute(
                f"INSERT INTO {TABLE} (version, description, checksum) VALUES (%s, %s, %s)",
                (migration.version, migration.description, migration.checksum),
            )
            applied_now.append(migration.version)
    if applied_now:
        logger.info("스키마 마이그레이션 적용: %s", ", ".join(f"V{v}" for v in applied_now))
    return applied_now
