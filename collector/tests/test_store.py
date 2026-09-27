"""
tests/test_store.py
저장 계층 회귀 테스트.

구버전 tests/test_store.py가 지키던 성질을 PostgreSQL 위에서 다시 확인합니다.
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone


from app import catalog


def test_snapshot_upsert_keeps_single_row(store):
    store.put_snapshot("demo.snap", {"value": 1})
    store.put_snapshot("demo.snap", {"value": 2})

    snapshot = store.read_snapshot("demo.snap")
    assert snapshot is not None
    assert snapshot.payload == {"value": 2}
    assert len(store.list_snapshots()) == 1


def test_snapshot_freshness(store):
    store.put_snapshot("demo.snap", {"value": 1})
    snapshot = store.read_snapshot("demo.snap")

    assert snapshot.is_fresh(60) is True
    assert snapshot.age_seconds < 60


def test_missing_snapshot_returns_none(store):
    assert store.read_snapshot("never.collected") is None


def test_stock_codes_keep_leading_zeros(store):
    """
    "069500"이 숫자로 바뀌면 69500이 되어 이후 모든 조회가 실패합니다.
    구버전에서 실제로 났던 사고라 계약으로 고정합니다.
    """
    store.put_snapshot("radar.demo", {"rows": [{"code": "069500", "name": "KODEX 200"}]})

    payload = store.read_snapshot("radar.demo").payload
    assert payload["rows"][0]["code"] == "069500"


def test_timeseries_upsert_is_idempotent(store):
    points = [("2026-01-02", 1.0), ("2026-01-03", 2.0)]
    assert store.put_timeseries("demo", "SERIES", points) == 2

    # 같은 날짜를 다시 쓰면 갱신만 되고 행이 늘지 않습니다.
    store.put_timeseries("demo", "SERIES", [("2026-01-03", 9.9)])

    rows = store.read_timeseries("demo", "SERIES")
    assert len(rows) == 2
    assert rows[-1] == {"date": "2026-01-03", "value": 9.9}


def test_timeseries_drops_unparseable_points(store):
    written = store.put_timeseries(
        "demo", "SERIES",
        [("2026-01-02", 1.0), ("bad-date", 5.0), (None, 7.0)],
    )
    assert written == 1


def test_observations_filter_by_payload(store):
    records = [
        {"entity": "K|F|B|001", "market": "KOSPI", "investor": "외국인",
         "tradeType": "순매수", "code": "005930", "netAmountEok": 120.0},
        {"entity": "K|I|B|002", "market": "KOSPI", "investor": "기관",
         "tradeType": "순매수", "code": "000660", "netAmountEok": 80.0},
    ]
    store.put_observations(catalog.OBS_RADAR, "2026-09-11", records, entity_key="entity")

    foreign = store.read_observations(
        catalog.OBS_RADAR, filters={"investor": "외국인"}
    )
    assert len(foreign) == 1
    assert foreign[0]["code"] == "005930"

    assert store.list_observation_dates(catalog.OBS_RADAR) == ["2026-09-11"]


def test_run_lifecycle_and_task_summary(store):
    run_id = store.start_run("fast")
    store.record_task_run(
        run_id, "macro_collected",
        speed="fast", status="ok",
        started_at=datetime.now(timezone.utc), duration_ms=1200, detail="21/21 지표",
    )
    store.finish_run(run_id, status="ok", ok_count=1, fail_count=0)

    last = store.read_last_run()
    assert last["status"] == "ok"
    assert store.resolve_run_status(last) == "ok"

    summary = store.read_task_summary()
    assert summary[0]["task"] == "macro_collected"
    assert summary[0]["status"] == "ok"


def test_dead_running_record_is_reported_as_interrupted(store):
    """
    수집기가 Ctrl+C·절전으로 죽으면 status가 'running'에 영구히 남습니다.
    그것을 "진행 중"이라고 보고하면 상태 화면이 거짓말을 합니다.
    """
    dead = {
        "status": "running",
        "pid": 999_999,                 # 존재하지 않는 PID
        "host": None,                   # None이면 같은 호스트로 간주
        "heartbeat_at": datetime.now(timezone.utc),
        "started_at": datetime.now(timezone.utc),
    }
    assert store.resolve_run_status(dead) == "interrupted"


def test_stale_heartbeat_is_interrupted(store):
    stale = {
        "status": "running",
        "pid": None,
        "host": "other-host",
        "heartbeat_at": datetime.now(timezone.utc) - timedelta(hours=2),
        "started_at": datetime.now(timezone.utc) - timedelta(hours=3),
    }
    assert store.resolve_run_status(stale) == "interrupted"


def test_mark_stale_runs_interrupted(store):
    run_id = store.start_run("fast")
    with store.connection() as conn:
        conn.execute(
            "UPDATE collector_runs SET pid = 999999, heartbeat_at = now() - interval '2 hours' "
            "WHERE id = %s",
            (run_id,),
        )

    assert store.mark_stale_runs_interrupted() == 1
    assert store.read_last_run()["status"] == "interrupted"


def test_refresh_request_moves_forward(store):
    first = store.request_refresh()
    second = store.request_refresh()

    assert second >= first
    assert store.refresh_requested_at() == second


def test_missing_datasets_lists_expected_names(store):
    """
    존재하는 스냅샷만 나열하면 "수집이 아예 안 된" 데이터셋을 놓칩니다.
    기대 목록과 대조해야 누락을 알아챌 수 있습니다.
    """
    missing = store.missing_datasets()
    names = {entry["name"] for entry in missing}

    assert catalog.SNAP_MACRO_COLLECTED in names
    assert catalog.snap_fred_series("T10Y3M") in names

    store.put_snapshot(catalog.SNAP_MACRO_COLLECTED, {"categories": []})
    names_after = {entry["name"] for entry in store.missing_datasets()}
    assert catalog.SNAP_MACRO_COLLECTED not in names_after


def test_purge_removes_old_history_only(store):
    store.put_timeseries("demo", "S", [("2000-01-03", 1.0), ("2026-01-03", 2.0)])
    removed = store.purge_older_than(365)

    assert removed["timeseries"] == 1
    assert [row["date"] for row in store.read_timeseries("demo", "S")] == ["2026-01-03"]


def test_task_summary_hides_removed_tasks(store):
    """없앤 태스크의 옛 실행 기록이 상태 화면에 남지 않아야 합니다(다시 실행할 수 없는 버튼)."""
    run_id = store.start_run("weekly")
    for name in ("kr_holidays", "seoul_apartments"):
        store.record_task_run(
            run_id, name, speed="weekly", status="ok",
            started_at=datetime.now(timezone.utc), duration_ms=10, detail="x",
        )

    names = [row["task"] for row in store.read_task_summary(["kr_holidays", "fsc_prices"])]
    assert names == ["kr_holidays"]
    # 거르지 않으면 둘 다 보입니다(이전 동작).
    assert {row["task"] for row in store.read_task_summary()} == {"kr_holidays", "seoul_apartments"}


def test_task_summary_is_last_recorded_row_per_task_in_name_order(store):
    """
    태스크마다 마지막에 기록한 1건을 태스크 이름 순으로 돌려줍니다 (PERF-03).

    실행 기록 전체를 훑던 DISTINCT ON을 태스크별 인덱스 첫 행 조회로 바꿨으므로,
    결과가 예전과 같은지(최신 기준 = 기록 순서, 이름 순, 같은 컬럼) 고정해 둡니다.
    """
    run_id = store.start_run("fast")
    base = datetime(2026, 9, 1, tzinfo=timezone.utc)
    for task, status, minutes in (
        ("radar_rankings", "ok", 0),
        ("fx_history", "error", 5),
        ("radar_rankings", "error", 10),
        ("fx_history", "ok", 1),         # 시작 시각은 더 이르지만 나중에 기록 → 이것이 최신
        ("cot_history", "empty", 3),
    ):
        store.record_task_run(
            run_id, task, speed="fast", status=status,
            started_at=base + timedelta(minutes=minutes), duration_ms=1, detail=None,
        )

    everything = store.read_task_summary()
    assert [(row["task"], row["status"]) for row in everything] == [
        ("cot_history", "empty"), ("fx_history", "ok"), ("radar_rankings", "error"),
    ]
    assert list(everything[0]) == ["task", "speed", "status", "started_at", "duration_ms", "detail", "run_id"]

    # 한 번도 돌지 않은 이름은 행이 없고, 같은 이름을 두 번 줘도 한 행입니다.
    named = store.read_task_summary(["radar_rankings", "never_ran", "fx_history", "fx_history"])
    assert [(row["task"], row["status"]) for row in named] == [("fx_history", "ok"), ("radar_rankings", "error")]
    assert store.read_task_summary([]) == []


def test_purge_retired_datasets(store):
    store.put_observations("molit_apt", "2026-08-01", [{"lawd": "11680"}], entity_key="lawd")
    store.put_observations(catalog.OBS_RADAR, "2026-09-23", [{"code": "005930"}], entity_key="code")

    assert store.purge_retired_datasets() == 1
    assert store.read_observations("molit_apt") == []
    assert len(store.read_observations(catalog.OBS_RADAR)) == 1


def test_purge_run_logs_only_touches_old_run_logs(store):
    """
    보존 기간이 지난 실행 기록만 지웁니다 (ARC-03). 수집한 데이터는 기간과 무관하게 남습니다.

    기간과 무관하게 남기는 실행 기록
      - 태스크마다 가장 최근 1건: 오래 돌지 않은 태스크도 상태 화면이 "마지막 실행"을 보여 줍니다.
      - 최근 실행 50건: 수동 정리(purge_older_than)와 같은 규칙.
    """
    long_ago = datetime.now(timezone.utc) - timedelta(days=100)
    with store.connection() as conn:
        conn.execute(
            "INSERT INTO collector_runs (started_at, status, group_name) "
            "SELECT %s + g * interval '1 minute', 'ok', 'slow' FROM generate_series(1, 55) g",
            (long_ago,),
        )
    recent_run = store.start_run("slow")
    for task, started_at in (
        ("fx_history", long_ago),                                      # 오래됨 + 더 최근 기록 있음 → 삭제
        ("fx_history", datetime.now(timezone.utc) - timedelta(days=1)),
        ("cot_history", long_ago),                                     # 오래됐지만 이 태스크의 마지막 기록 → 유지
    ):
        store.record_task_run(recent_run, task, speed="slow", status="ok",
                              started_at=started_at, duration_ms=1, detail=None)
    old_day = (long_ago - timedelta(days=300)).date().isoformat()
    store.put_timeseries("demo", "S", [(old_day, 1.0)])
    store.put_observations("demo_obs", old_day, [{"code": "005930"}], entity_key="code")

    removed = store.purge_run_logs(90)

    assert removed == {"taskRuns": 1, "collectorRuns": 6}    # 오래된 실행 55건 중 최근 50건 안에 드는 49건은 유지
    remaining = {(row["task"], row["started_at"].date()) for row in store.read_task_history(limit=10)}
    assert remaining == {
        ("fx_history", (datetime.now(timezone.utc) - timedelta(days=1)).date()),
        ("cot_history", long_ago.date()),
    }
    assert [row["date"] for row in store.read_timeseries("demo", "S")] == [old_day]
    assert len(store.read_observations("demo_obs")) == 1
    assert store.purge_run_logs(90) == {"taskRuns": 0, "collectorRuns": 0}
