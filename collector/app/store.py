"""
app/store.py
PostgreSQL 저장 계층 (구버전 services/store.py의 SQLite 버전을 대체).

[무엇이 같고 무엇이 달라졌나]
같은 것 — 저장 형태 네 가지(snapshots / timeseries / observations / 실행 로그)와
"수집과 표시를 분리한다"는 구조. 수집기는 쓰기만, API는 읽기만 합니다.

달라진 것
  - SQLite 파일 + WAL → PostgreSQL. 수집기와 백엔드가 서로 다른 컨테이너에서
    도는 구조라 파일 공유가 불가능합니다.
  - payload를 pandas DataFrame으로 직렬화하던 부분을 **명시적 JSON 계약**으로
    바꿨습니다. 구버전은 DataFrame을 JSON으로 저장하면서 dtype을 함께 남겨야
    했습니다. 그러지 않으면 "069500" 같은 종목코드가 int로 추론돼 앞자리 0이
    사라졌기 때문입니다(실제 있었던 버그). 이제 종목코드는 애초에 문자열
    필드로 정의되므로 그 위험 자체가 없습니다.
"""
from __future__ import annotations

import json
import logging
import os
import socket
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Iterable, Sequence

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb
from psycopg_pool import ConnectionPool

from . import catalog, migrations, settings

logger = logging.getLogger(__name__)

# heartbeat가 이 시간 동안 갱신되지 않은 'running' 레코드는 죽은 것으로 봅니다.
# 가장 느린 태스크(13F)보다 넉넉하게 잡습니다.
STALE_RUN_SECONDS = 30 * 60

_pool: ConnectionPool | None = None


def get_pool() -> ConnectionPool:
    global _pool
    if _pool is None:
        _pool = ConnectionPool(
            conninfo=settings.database_url(),
            min_size=1,
            max_size=8,
            kwargs={"row_factory": dict_row},
            open=True,
        )
    return _pool


def close_pool() -> None:
    global _pool
    if _pool is not None:
        _pool.close()
        _pool = None


@contextmanager
def connection():
    with get_pool().connection() as conn:
        yield conn


def init_schema(path: str | None = None) -> None:
    """
    스키마를 최신 버전으로 맞춥니다 — db/migrations에서 아직 적용하지 않은 파일만
    번호 순서로 적용합니다(app/migrations.py).

    쓰기는 수집기의 몫이라 수집기가 기동할 때 적용합니다. 백엔드는 읽기만 합니다.

    :param path: 마이그레이션 디렉터리. 파일 경로를 주면 그 파일이 있는 디렉터리를 씁니다
    :raises psycopg.Error: 적용에 실패하면 전부 되돌리고 그대로 올립니다(기동 중단)
    """
    directory = _resolve_migrations_dir(path)
    if directory is None:
        logger.warning(
            "마이그레이션 디렉터리를 찾지 못했습니다. MACRO_MIGRATIONS_DIR로 경로를 지정하세요."
        )
        return

    found = migrations.discover(directory)
    with connection() as conn:
        migrations.apply_pending(conn, found)
    logger.info("저장 계층 준비 완료: %s (V%d까지)", directory, found[-1].version)


def _resolve_migrations_dir(path: str | None) -> Path | None:
    """
    마이그레이션 디렉터리를 찾습니다.

    배포 형태마다 경로가 다릅니다.
      - 저장소 실행 : <repo>/db/migrations
      - 컨테이너    : /app/db/migrations (compose가 마운트)
    명시 인자 → MACRO_MIGRATIONS_DIR → MACRO_SCHEMA_SQL(예전 설정: V1 파일 경로) →
    관례적 위치 순으로 찾고, 없으면 None을 돌려줍니다(잘못된 경로를 조용히 만들어 내지 않습니다).
    """
    candidates: list[Path] = []
    for value in (path, os.environ.get("MACRO_MIGRATIONS_DIR"), os.environ.get("MACRO_SCHEMA_SQL")):
        if value and value.strip():
            candidate = Path(value.strip())
            candidates.append(candidate.parent if candidate.suffix == ".sql" else candidate)

    here = Path(__file__).resolve()
    # app/ → collector/ → <repo> 순으로 거슬러 올라가며 찾습니다.
    candidates.extend(parent / "db" / "migrations" for parent in here.parents)

    for candidate in candidates:
        if candidate.is_dir() and any(candidate.glob("V*__*.sql")):
            return candidate
    return None


# ==============================================================================
# 1. 스냅샷
# ==============================================================================
@dataclass(frozen=True)
class Snapshot:
    name: str
    payload: Any
    kind: str
    status: str
    error: str | None
    collected_at: datetime | None

    @property
    def age_seconds(self) -> float:
        if self.collected_at is None:
            return float("inf")
        delta = datetime.now(timezone.utc) - self.collected_at
        return max(0.0, delta.total_seconds())

    def is_fresh(self, max_age_seconds: float) -> bool:
        return self.age_seconds <= max_age_seconds


def put_snapshot(
    name: str,
    payload: Any,
    *,
    kind: str = "json",
    status: str = "ok",
    error: str | None = None,
) -> None:
    """스냅샷 upsert. 같은 이름은 최신 1건만 유지합니다."""
    with connection() as conn:
        conn.execute(
            """
            INSERT INTO snapshots (name, payload, kind, status, error, collected_at)
            VALUES (%s, %s, %s, %s, %s, now())
            ON CONFLICT (name) DO UPDATE SET
                payload      = EXCLUDED.payload,
                kind         = EXCLUDED.kind,
                status       = EXCLUDED.status,
                error        = EXCLUDED.error,
                collected_at = EXCLUDED.collected_at
            """,
            (name, Jsonb(payload), kind, status, error),
        )


def read_snapshot(name: str) -> Snapshot | None:
    with connection() as conn:
        row = conn.execute(
            "SELECT name, payload, kind, status, error, collected_at "
            "FROM snapshots WHERE name = %s",
            (name,),
        ).fetchone()

    if row is None:
        return None
    return Snapshot(
        name=row["name"],
        payload=row["payload"],
        kind=row["kind"],
        status=row["status"],
        error=row["error"],
        collected_at=row["collected_at"],
    )


def list_snapshots() -> list[dict]:
    with connection() as conn:
        rows = conn.execute(
            "SELECT name, status, error, collected_at FROM snapshots ORDER BY name"
        ).fetchall()
    return [dict(r) for r in rows]


# ==============================================================================
# 2. 시계열 누적
# ==============================================================================
def put_timeseries(
    dataset: str,
    series_id: str,
    points: Sequence[tuple[Any, float | None]],
) -> int:
    """
    (dataset, series_id, 날짜) → 값 upsert. 반영된 행 수를 반환합니다.

    같은 날짜는 최신 값으로 덮어씁니다. 매일 수집하면 과거는 유지되고
    최근 값만 갱신됩니다.
    """
    rows = []
    for raw_date, value in points:
        day = _coerce_date(raw_date)
        if day is None:
            continue
        rows.append((dataset, series_id, day, _coerce_float(value)))

    if not rows:
        return 0

    with connection() as conn, conn.cursor() as cur:
        cur.executemany(
            """
            INSERT INTO timeseries (dataset, series_id, obs_date, value, updated_at)
            VALUES (%s, %s, %s, %s, now())
            ON CONFLICT (dataset, series_id, obs_date) DO UPDATE SET
                value      = EXCLUDED.value,
                updated_at = EXCLUDED.updated_at
            """,
            rows,
        )
    return len(rows)


def read_timeseries(
    dataset: str,
    series_id: str,
    *,
    start_date: str | None = None,
) -> list[dict]:
    sql = (
        "SELECT obs_date, value FROM timeseries "
        "WHERE dataset = %s AND series_id = %s"
    )
    params: list[Any] = [dataset, series_id]
    if start_date:
        sql += " AND obs_date >= %s"
        params.append(start_date)
    sql += " ORDER BY obs_date"

    with connection() as conn:
        rows = conn.execute(sql, params).fetchall()
    return [
        {"date": r["obs_date"].isoformat(), "value": r["value"]}
        for r in rows
        if r["value"] is not None
    ]


def count_timeseries() -> int:
    with connection() as conn:
        row = conn.execute("SELECT COUNT(*) AS n FROM timeseries").fetchone()
    return int(row["n"])


# ==============================================================================
# 3. 레코드 누적 (수급 랭킹처럼 행이 여러 컬럼인 데이터)
# ==============================================================================
def put_observations(
    dataset: str,
    obs_date: str,
    records: Iterable[dict],
    *,
    entity_key: str,
) -> int:
    rows = []
    for rec in records:
        entity = rec.get(entity_key)
        if entity is None:
            continue
        rows.append((dataset, obs_date, str(entity), Jsonb(rec)))

    if not rows:
        return 0

    with connection() as conn, conn.cursor() as cur:
        cur.executemany(
            """
            INSERT INTO observations (dataset, obs_date, entity, payload, updated_at)
            VALUES (%s, %s, %s, %s, now())
            ON CONFLICT (dataset, obs_date, entity) DO UPDATE SET
                payload    = EXCLUDED.payload,
                updated_at = EXCLUDED.updated_at
            """,
            rows,
        )
    return len(rows)


def read_observations(
    dataset: str,
    *,
    obs_date: str | None = None,
    start_date: str | None = None,
    filters: dict[str, str] | None = None,
) -> list[dict]:
    sql = "SELECT obs_date, payload FROM observations WHERE dataset = %s"
    params: list[Any] = [dataset]

    if obs_date:
        sql += " AND obs_date = %s"
        params.append(obs_date)
    if start_date:
        sql += " AND obs_date >= %s"
        params.append(start_date)
    if filters:
        sql += " AND payload @> %s"
        params.append(Jsonb(filters))
    sql += " ORDER BY obs_date, entity"

    with connection() as conn:
        rows = conn.execute(sql, params).fetchall()

    out = []
    for r in rows:
        rec = dict(r["payload"])
        rec.setdefault("obsDate", r["obs_date"].isoformat())
        out.append(rec)
    return out


def recent_observation_codes(dataset: str, since: str, limit: int) -> list[str]:
    """
    최근 누적 레코드에 등장한 종목코드(payload.code), 등장 횟수 많은 순.

    :param dataset: 누적 데이터셋 이름 (예: radar_ranking)
    :param since: 이 날짜(YYYY-MM-DD) 이후만
    :param limit: 최대 개수 — 외부 API 호출 수가 여기에 비례합니다
    """
    with connection() as conn:
        rows = conn.execute(
            """
            SELECT payload->>'code' AS code, COUNT(*) AS n
            FROM observations
            WHERE dataset = %s AND obs_date >= %s AND payload ? 'code'
            GROUP BY 1 ORDER BY n DESC, code LIMIT %s
            """,
            (dataset, since, limit),
        ).fetchall()
    return [r["code"] for r in rows if r["code"]]


def latest_observation_date(dataset: str) -> str | None:
    """그 데이터셋의 가장 최근 obs_date (YYYY-MM-DD). 없으면 None."""
    with connection() as conn:
        row = conn.execute(
            "SELECT MAX(obs_date) AS d FROM observations WHERE dataset = %s", (dataset,),
        ).fetchone()
    return row["d"].isoformat() if row and row["d"] else None


def delete_observations_before(dataset: str, before: str) -> int:
    """오래된 누적 레코드 정리. 지운 행 수."""
    with connection() as conn:
        cur = conn.execute(
            "DELETE FROM observations WHERE dataset = %s AND obs_date < %s", (dataset, before),
        )
        return cur.rowcount or 0


def list_observation_dates(dataset: str) -> list[str]:
    with connection() as conn:
        rows = conn.execute(
            "SELECT DISTINCT obs_date FROM observations "
            "WHERE dataset = %s ORDER BY obs_date",
            (dataset,),
        ).fetchall()
    return [r["obs_date"].isoformat() for r in rows]


def count_observations() -> int:
    with connection() as conn:
        row = conn.execute("SELECT COUNT(*) AS n FROM observations").fetchone()
    return int(row["n"])


# ==============================================================================
# 4. 수집 실행 로그
# ==============================================================================
def start_run(group_name: str | None = None) -> int:
    with connection() as conn:
        row = conn.execute(
            "INSERT INTO collector_runs "
            "(started_at, status, pid, host, heartbeat_at, group_name) "
            "VALUES (now(), 'running', %s, %s, now(), %s) RETURNING id",
            (os.getpid(), socket.gethostname(), group_name),
        ).fetchone()
    return int(row["id"])


def heartbeat_run(run_id: int) -> None:
    try:
        with connection() as conn:
            conn.execute(
                "UPDATE collector_runs SET heartbeat_at = now() WHERE id = %s",
                (run_id,),
            )
    except psycopg.Error as exc:
        logger.debug("heartbeat 갱신 실패: %s", exc)


def finish_run(
    run_id: int,
    *,
    status: str,
    ok_count: int = 0,
    fail_count: int = 0,
    detail: str | None = None,
) -> None:
    with connection() as conn:
        conn.execute(
            "UPDATE collector_runs SET finished_at = now(), status = %s, "
            "ok_count = %s, fail_count = %s, detail = %s, heartbeat_at = now() "
            "WHERE id = %s",
            (status, ok_count, fail_count, (detail or "")[:2000] or None, run_id),
        )


def record_task_run(
    run_id: int | None,
    task: str,
    *,
    speed: str | None,
    status: str,
    started_at: datetime,
    duration_ms: int,
    detail: str | None = None,
) -> None:
    """status: ok | empty(수집됐지만 데이터 없음) | error"""
    try:
        with connection() as conn:
            conn.execute(
                "INSERT INTO collector_task_runs "
                "(run_id, task, speed, status, started_at, duration_ms, detail) "
                "VALUES (%s, %s, %s, %s, %s, %s, %s)",
                (
                    run_id, task, speed, status,
                    started_at.astimezone(timezone.utc),
                    duration_ms, (detail or "")[:1000] or None,
                ),
            )
    except psycopg.Error as exc:
        logger.warning("태스크 로그 기록 실패 (%s): %s", task, exc)


def _pid_is_alive(pid: int | None) -> bool:
    if not pid:
        return False
    try:
        os.kill(int(pid), 0)   # 시그널 0 = 존재 확인만
    except ProcessLookupError:
        return False
    except PermissionError:
        return True            # 존재하지만 권한이 없음
    except (TypeError, ValueError, OSError):
        return False
    return True


def resolve_run_status(run: dict | None) -> str:
    """
    기록된 status를 실제 상태로 보정합니다.

    'running'으로 남아 있지만 프로세스가 죽었거나 heartbeat가 끊긴 경우
    'interrupted'로 보고합니다. 그래야 상태 화면이 거짓말을 하지 않습니다.
    """
    if not run:
        return "none"

    status = run.get("status") or "?"
    if status != "running":
        return status

    same_host = (run.get("host") or socket.gethostname()) == socket.gethostname()
    if same_host and not _pid_is_alive(run.get("pid")):
        return "interrupted"

    beat = run.get("heartbeat_at") or run.get("started_at")
    if isinstance(beat, datetime):
        age = (datetime.now(timezone.utc) - beat).total_seconds()
        if age > STALE_RUN_SECONDS:
            return "interrupted"

    return "running"


def mark_stale_runs_interrupted() -> int:
    """죽은 'running' 레코드를 정리합니다. 수집기 기동 시 호출합니다."""
    with connection() as conn:
        rows = conn.execute(
            "SELECT * FROM collector_runs WHERE status = 'running'"
        ).fetchall()

        stale = [r["id"] for r in rows if resolve_run_status(dict(r)) == "interrupted"]
        if not stale:
            return 0

        conn.execute(
            "UPDATE collector_runs SET status = 'interrupted', "
            "detail = COALESCE(detail, '') || ' [비정상 종료로 판정]' "
            "WHERE id = ANY(%s)",
            (stale,),
        )
    logger.info("비정상 종료된 수집 기록 %d건을 정리했습니다.", len(stale))
    return len(stale)


def read_last_run() -> dict | None:
    with connection() as conn:
        row = conn.execute(
            "SELECT * FROM collector_runs ORDER BY id DESC LIMIT 1"
        ).fetchone()
    return dict(row) if row else None


def read_task_summary(task_names: Sequence[str] | None = None) -> list[dict]:
    """
    태스크별 '가장 최근 실행 결과'.

    :param task_names: 지금 등록된 태스크 이름. 주면 그 태스크만 돌려줍니다.
    주의사항 — 실행 기록은 태스크를 없앤 뒤에도 남습니다. 거르지 않으면 없어진
    태스크가 상태 화면에 영원히 남고, 누를 수 없는 "다시 실행" 버튼이 생깁니다.

    태스크마다 (task, id DESC) 인덱스의 첫 행만 읽습니다. 예전 DISTINCT ON은 실행 기록
    전체를 정렬해, 1년치(43만 행)에서 125ms가 걸렸습니다(지금 0.14ms).
    """
    params: list[Any] = []
    if task_names is None:
        # 기록에 있는 태스크 이름을 인덱스에서 하나씩 건너뛰며 찾습니다.
        names_sql = """
            WITH RECURSIVE names AS (
                (SELECT task FROM collector_task_runs ORDER BY task LIMIT 1)
                UNION ALL
                SELECT (SELECT r.task FROM collector_task_runs r
                        WHERE r.task > n.task ORDER BY r.task LIMIT 1)
                FROM names n WHERE n.task IS NOT NULL
            )
            SELECT task FROM names
        """
    else:
        names_sql = "SELECT DISTINCT unnest(%s::text[]) AS task"
        params.append(list(task_names))
    sql = f"""
        SELECT latest.* FROM ({names_sql}) t
        CROSS JOIN LATERAL (
            SELECT task, speed, status, started_at, duration_ms, detail, run_id
            FROM collector_task_runs r
            WHERE r.task = t.task
            ORDER BY r.id DESC LIMIT 1
        ) latest
        ORDER BY latest.task
    """
    with connection() as conn:
        rows = conn.execute(sql, params).fetchall()
    return [dict(r) for r in rows]


def read_task_history(task: str | None = None, limit: int = 50) -> list[dict]:
    sql = (
        "SELECT run_id, task, speed, status, started_at, duration_ms, detail "
        "FROM collector_task_runs"
    )
    params: list[Any] = []
    if task:
        sql += " WHERE task = %s"
        params.append(task)
    sql += " ORDER BY id DESC LIMIT %s"
    params.append(int(limit))

    with connection() as conn:
        rows = conn.execute(sql, params).fetchall()
    return [dict(r) for r in rows]


# ==============================================================================
# 5. 수동 새로고침 기준 시각
# ==============================================================================
def request_refresh(scope: str = "global") -> datetime:
    """
    "이 시각 이전 저장본은 낡은 것으로 본다"는 기준을 세웁니다.

    저장본을 지우지 않는 이유: 수집이 실패하면 보여 줄 값이 아예 없어지기
    때문입니다. 구버전 store.request_refresh()와 같은 판단입니다.
    """
    with connection() as conn:
        row = conn.execute(
            "INSERT INTO refresh_requests (scope, requested_at) VALUES (%s, now()) "
            "ON CONFLICT (scope) DO UPDATE SET requested_at = now() "
            "RETURNING requested_at",
            (scope,),
        ).fetchone()
    return row["requested_at"]


def refresh_requested_at(scope: str = "global") -> datetime | None:
    with connection() as conn:
        row = conn.execute(
            "SELECT requested_at FROM refresh_requests WHERE scope = %s",
            (scope,),
        ).fetchone()
    return row["requested_at"] if row else None


# ==============================================================================
# 6. 상태 요약 / 정리
# ==============================================================================
def purge_retired_datasets() -> int:
    """
    더 이상 쓰지 않는 누적 데이터셋을 지웁니다(기동 시 1회).

    기능을 없애도 쌓아 둔 행은 DB에 남아 용량과 백업 크기만 차지합니다.
    지운 행 수를 돌려줍니다.
    """
    removed = 0
    with connection() as conn:
        for dataset in catalog.RETIRED_OBSERVATION_DATASETS:
            cur = conn.execute("DELETE FROM observations WHERE dataset = %s", (dataset,))
            removed += cur.rowcount or 0
    return removed


def store_stats(task_names: Sequence[str] | None = None) -> dict:
    last = read_last_run()
    return {
        "snapshots": list_snapshots(),
        "timeseriesRows": count_timeseries(),
        "observationRows": count_observations(),
        "lastRun": _serialize_run(last),
        "lastRunStatus": resolve_run_status(last),
        "taskSummary": [_serialize_task(t) for t in read_task_summary(task_names)],
        "missingDatasets": missing_datasets(),
    }


def _serialize_run(run: dict | None) -> dict | None:
    if not run:
        return None
    out = dict(run)
    for key in ("started_at", "finished_at", "heartbeat_at"):
        value = out.get(key)
        if isinstance(value, datetime):
            out[key] = value.isoformat()
    return {
        "id": out.get("id"),
        "startedAt": out.get("started_at"),
        "finishedAt": out.get("finished_at"),
        "heartbeatAt": out.get("heartbeat_at"),
        "status": out.get("status"),
        "okCount": out.get("ok_count"),
        "failCount": out.get("fail_count"),
        "detail": out.get("detail"),
        "pid": out.get("pid"),
        "host": out.get("host"),
        "groupName": out.get("group_name"),
    }


def _serialize_task(task: dict) -> dict:
    started = task.get("started_at")
    return {
        "task": task.get("task"),
        "speed": task.get("speed"),
        "status": task.get("status"),
        "startedAt": started.isoformat() if isinstance(started, datetime) else started,
        "durationMs": task.get("duration_ms"),
        "detail": task.get("detail"),
        "runId": task.get("run_id"),
    }


def missing_datasets() -> list[dict]:
    """
    "있어야 하는데 없는" 데이터셋을 찾습니다.

    존재하는 스냅샷만 나열하면 수집이 아예 안 된 데이터셋은 목록에서 조용히
    빠집니다. 기대 목록과 대조해야 누락을 알아챌 수 있습니다.
    """
    from . import indicators

    with connection() as conn:
        rows = conn.execute("SELECT name FROM snapshots").fetchall()
    present = {r["name"] for r in rows}

    expected: list[tuple[str, str]] = [
        (catalog.SNAP_MACRO_COLLECTED, "매크로 카드"),
        (catalog.SNAP_SCRAPER_MARKETS, "TradingView/Yahoo 참고 시세"),
        (catalog.SNAP_FED_LIQUIDITY, "연준 순유동성"),
        (catalog.SNAP_KRX_FUTURES, "KRX 선물 시계열"),
        (catalog.SNAP_SECTOR_HISTORY, "섹터·자산군 ETF 종가"),
        (catalog.SNAP_FX_HISTORY, "환율·달러인덱스 일별 종가"),
        (catalog.SNAP_EQUITY_HISTORY, "13F 매핑 종목 일별 종가"),
        (catalog.SNAP_COT_HISTORY, "CFTC COT 통합"),
        (catalog.snap_daum_futures_trend(25), "Daum 선물 수급 (계약수)"),
    ]

    # 키가 있어야 받을 수 있는 데이터셋은 키가 설정된 경우에만 "누락"으로 봅니다.
    # 키를 넣지 않은 사람에게 매번 누락 경고를 띄우면 진짜 누락이 묻힙니다.
    from . import settings
    if settings.data_go_kr_key():
        expected.append((catalog.SNAP_KR_HOLIDAYS, "한국 공휴일 (천문연)"))
    if settings.data_go_kr_key():
        expected.append((catalog.SNAP_FSC_PRICES_META, "국내 공식 시세 (금융위)"))
    if settings.dart_key():
        expected.append((catalog.SNAP_DART_FUNDAMENTALS, "국내 종목 재무 (DART)"))

    for sid in indicators.FRED_ALL_SERIES:
        expected.append((catalog.snap_fred_series(sid), f"FRED {sid}"))

    for symbol in ("^VIX", "^MOVE"):
        expected.append((
            catalog.snap_ticker_history(symbol, catalog.VOLATILITY_STORE_PERIOD),
            f"변동성 {symbol}",
        ))

    for asset, info in indicators.COT_ASSETS.items():
        expected.append((
            catalog.snap_cot_contract(info["code"], indicators.COT_WEEKS),
            f"COT {asset}",
        ))

    for market, investor, trade in (
        ("KOSPI", "외국인", "순매수"),
        ("KOSPI", "기관", "순매수"),
        ("KOSPI", "외국인", "순매도"),
    ):
        expected.append((
            catalog.snap_radar_scanner(market, investor, trade, "TODAY"),
            f"수급 레이더 {investor}/{trade}",
        ))

    for inst in indicators.INSTITUTIONS:
        short = inst["name"].split("(")[0].strip()
        for quarters in (1, catalog.MAX_TRACKED_QUARTERS):
            expected.append((
                catalog.snap_sec_13f(inst["cik"], quarters),
                f"13F {short} q{quarters}",
            ))

    return [
        {"name": name, "label": label}
        for name, label in expected
        if name not in present
    ]


def purge_older_than(days: int) -> dict[str, int]:
    """오래된 누적 이력을 정리합니다 (장기 운영용)."""
    cutoff = (datetime.now(timezone.utc) - timedelta(days=days)).date()
    with connection() as conn:
        ts = conn.execute(
            "DELETE FROM timeseries WHERE obs_date < %s", (cutoff,)
        ).rowcount
        ob = conn.execute(
            "DELETE FROM observations WHERE obs_date < %s", (cutoff,)
        ).rowcount
        runs = conn.execute(
            "DELETE FROM collector_runs WHERE started_at < %s "
            "AND id NOT IN (SELECT id FROM collector_runs ORDER BY id DESC LIMIT 50)",
            (cutoff,),
        ).rowcount
    return {"timeseries": ts, "observations": ob, "collectorRuns": runs}


def purge_run_logs(days: int) -> dict[str, int]:
    """
    보존 기간이 지난 수집 실행 기록(collector_task_runs · collector_runs)을 지웁니다.

    실행 기록은 하루 약 1,200행씩 쌓여 DB를 키우고 상태 화면 조회를 느리게 합니다.
    **수집한 데이터(timeseries · observations)는 건드리지 않습니다** — 외부에서 다시
    받을 수 없는 이력이 있습니다.

    기간과 무관하게 남기는 것
      - 태스크마다 가장 최근 1건: 오래 돌지 않은 태스크도 상태 화면이 "마지막 실행"을 보여 줍니다.
      - 최근 실행 50건: purge_older_than과 같은 규칙.

    :param days: 보존 기간(일). 이보다 먼저 시작한 기록이 대상입니다
    :returns: {"taskRuns": 지운 태스크 기록 수, "collectorRuns": 지운 실행 기록 수}
    """
    cutoff = datetime.now(timezone.utc) - timedelta(days=days)
    with connection() as conn:
        task_runs = conn.execute(
            "DELETE FROM collector_task_runs old WHERE old.started_at < %s "
            "AND EXISTS (SELECT 1 FROM collector_task_runs newer "
            "            WHERE newer.task = old.task AND newer.id > old.id)",
            (cutoff,),
        ).rowcount
        runs = conn.execute(
            "DELETE FROM collector_runs WHERE started_at < %s "
            "AND id NOT IN (SELECT id FROM collector_runs ORDER BY id DESC LIMIT 50)",
            (cutoff,),
        ).rowcount
    return {"taskRuns": task_runs, "collectorRuns": runs}


# ==============================================================================
# 7. 내부 헬퍼
# ==============================================================================
def _coerce_date(value: Any) -> date | None:
    if value is None:
        return None
    if isinstance(value, datetime):
        return value.date()
    if isinstance(value, date):
        return value
    text = str(value)[:10]
    try:
        return date.fromisoformat(text)
    except ValueError:
        return None


def _coerce_float(value: Any) -> float | None:
    if value is None:
        return None
    try:
        out = float(value)
    except (TypeError, ValueError):
        return None
    return None if out != out else out      # NaN 제거


def json_dumps(value: Any) -> str:
    """로그/디버그용. DB 저장에는 Jsonb를 씁니다."""
    return json.dumps(value, ensure_ascii=False, default=str)
