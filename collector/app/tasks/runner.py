"""
app/tasks/runner.py
실행기 — 같은 태스크의 동시 실행 합치기, 속도군별 시간 상한, 그룹 실행.
"""
from __future__ import annotations

import logging
import threading
import time
import traceback
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FuturesTimeoutError
from datetime import datetime, timezone

from .. import store

from .base import EmptyResult, Task
from .registry import ALL_TASKS, TASKS_BY_NAME

logger = logging.getLogger(__name__)


# ==============================================================================
# 같은 태스크의 동시 실행 합치기 (coalescing)
# ==============================================================================
# 화면 한 번 여는 것만으로 같은 태스크가 여러 번 돕니다. 백엔드는 스냅샷을
# 읽다가 오래됐으면 수집을 요청하는데, 한 화면이 여러 스냅샷을 병렬로 읽으면
# 그 요청이 각각 나갑니다. 실제 로그에서 확인된 모습입니다.
#
#   05:02:02 수집 시작 (cot_history): 1개 작업   ← 같은 초에 4번
#   05:02:02 수집 시작 (cot_history): 1개 작업
#   05:02:02 수집 시작 (cot_history): 1개 작업
#   05:02:02 수집 시작 (cot_history): 1개 작업
#
#   05:02:26 ✅ fred_series 80.03s   ← 스케줄러(slow)
#   05:03:07 ✅ fred_series 36.30s   ← 요청 1
#   05:03:08 ✅ fred_series 50.43s   ← 요청 2  (셋이 동시에 FRED를 두드림)
#
# 외부 API 호출이 그대로 몇 배가 되고(FRED·SEC는 호출 한도가 있습니다),
# 같은 행을 동시에 쓰게 됩니다.
#
# 뒤늦게 온 호출을 **거절하지 않고 기다리게** 합니다. 호출자가 원하는 것은
# "지금 새로 받아라"가 아니라 "신선한 값을 달라"이기 때문입니다. 이미 도는
# 수집이 끝나면 그 결과를 함께 씁니다.
_COALESCE_WAIT_SECONDS = 180.0

_inflight: dict[str, "_InFlight"] = {}
_inflight_lock = threading.Lock()


class _InFlight:
    """실행 중인 태스크 하나와 그 결과를 기다리는 자리."""

    __slots__ = ("done", "result")

    def __init__(self) -> None:
        self.done = threading.Event()
        self.result: tuple[bool, str] = (False, "결과 없음")


def run_task(task: Task, run_id: int | None = None) -> tuple[bool, str]:
    """
    태스크 1건을 실행하고 결과를 DB에 남깁니다.

    같은 태스크가 이미 돌고 있으면 새로 시작하지 않고 그 결과를 기다립니다.
    """
    with _inflight_lock:
        entry = _inflight.get(task.name)
        leader = entry is None
        if leader:
            entry = _InFlight()
            _inflight[task.name] = entry

    if not leader:
        return _wait_for_inflight(task, entry)

    try:
        result = _execute_task(task, run_id)
        entry.result = result
        return result
    except BaseException as exc:      # 기다리는 쪽을 영원히 붙잡아 두지 않습니다.
        entry.result = (False, f"{type(exc).__name__}: {exc}")
        raise
    finally:
        # 먼저 등록을 지워야 다음 호출이 새 수집을 시작할 수 있습니다.
        with _inflight_lock:
            _inflight.pop(task.name, None)
        entry.done.set()


def _wait_for_inflight(task: Task, entry: "_InFlight") -> tuple[bool, str]:
    """이미 도는 같은 태스크가 끝나기를 기다렸다가 그 결과를 씁니다."""
    logger.info("%s: 이미 실행 중입니다. 새로 시작하지 않고 결과를 기다립니다.", task.name)

    if not entry.done.wait(timeout=_COALESCE_WAIT_SECONDS):
        # 실패가 아닙니다 — 수집은 앞선 호출이 계속 진행 중이고 결과는 다음 조회에 반영됩니다.
        # 예전엔 False를 돌려줘 백엔드가 요청한 13F(10분 넘게 걸림)가 주간 수집과 겹칠 때마다
        # collector_runs에 'fail' 행이 남고 make status가 "마지막 실행: 실패"라고 말했습니다.
        return True, (
            f"이미 실행 중인 수집에 합류 — {int(_COALESCE_WAIT_SECONDS)}초 안에 끝나지 않아 "
            "결과는 그 수집이 끝난 뒤 반영됩니다"
        )

    ok, detail = entry.result
    return ok, f"{detail} (동시에 실행 중이던 수집 결과를 함께 사용)"


EMPTY_PREFIX = "수집 결과 없음: "


def _run_status(ok_count: int, failures: list[str]) -> str:
    """
    실행(run) 상태를 태스크 결과에서 유도합니다.

    ok      전부 성공            partial  일부 성공
    empty   실패가 전부 '수집 결과 없음'(소스가 새 데이터를 안 줌 — 저장본 유지)
    fail    오류가 하나라도 있음
    예전에는 empty를 fail로 적어, 금융위 시세가 '새 기준일 없음'으로 끝난 평일마다 make status가
    "마지막 실행: 실패"라고 말했습니다. 태스크 화면은 같은 것을 '데이터 없음'이라고 부릅니다.
    """
    if not failures:
        return "ok"
    if ok_count:
        return "partial"
    if all(f.split(": ", 1)[-1].startswith(EMPTY_PREFIX) for f in failures):
        return "empty"
    return "fail"


# 태스크 하나의 시간 상한(초, 속도군별). 요청마다 timeout이 있어도 재시도 × 소스 수만큼 곱해져, 외부가
# '연결은 받고 응답하지 않는' 상태에서는 2초짜리 fast 태스크가 3~4분씩 돌았습니다(QA-011). 상한을 넘기면
# 실패로 기록하고 다음 태스크로 넘어갑니다. 작업 스레드는 요청 timeout들이 끝날 때까지 남아 뒤늦게
# 끝나는데, 그 결과는 로그로만 남깁니다(저장본은 그 작업이 알아서 씁니다 — 진짜 데이터입니다).
# weekly(13F)는 한 태스크가 10분 넘게 걸리는 것이 정상이라 길게 둡니다.
TASK_DEADLINE_SECONDS: dict[str, float] = {"fast": 120.0, "slow": 600.0, "weekly": 1800.0}
_task_threads = ThreadPoolExecutor(max_workers=8, thread_name_prefix="task")


def deadline_for(task: Task) -> float:
    return TASK_DEADLINE_SECONDS.get(task.speed, TASK_DEADLINE_SECONDS["slow"])


def _execute_task(task: Task, run_id: int | None) -> tuple[bool, str]:
    started_wall = datetime.now(timezone.utc)
    started = time.perf_counter()

    status = "ok"
    ok = True
    detail = ""

    deadline = deadline_for(task)
    future = _task_threads.submit(task.run)
    try:
        detail = future.result(timeout=deadline) or ""
    except FuturesTimeoutError:
        status, ok = "error", False
        detail = (f"시간 초과: {deadline:.0f}초 안에 끝나지 않았습니다 — 외부 응답 없음 의심. "
                  "작업은 뒤에서 계속 끝나기를 기다립니다(결과는 로그에만)")
        future.add_done_callback(lambda done, name=task.name, t0=started: _log_late_finish(name, t0, done))
    except EmptyResult as exc:
        status, ok, detail = "empty", False, f"{EMPTY_PREFIX}{exc}"
    except Exception as exc:  # noqa: BLE001
        status, ok = "error", False
        detail = f"{type(exc).__name__}: {exc}"
        logger.debug(traceback.format_exc())

    elapsed = time.perf_counter() - started
    icon = {"ok": "✅", "empty": "⚠️", "error": "❌"}[status]
    (logger.info if ok else logger.warning)(
        "  %s %-22s %6.2fs  %s", icon, task.name, elapsed, detail
    )

    store.record_task_run(
        run_id,
        task.name,
        speed=task.speed,
        status=status,
        started_at=started_wall,
        duration_ms=int(elapsed * 1000),
        detail=detail,
    )
    return ok, detail


def _log_late_finish(name: str, started: float, done) -> None:
    elapsed = time.perf_counter() - started
    exc = done.exception()
    if exc is None:
        logger.warning("  ⏱️ %-22s %6.1fs  뒤늦게 끝남(이미 시간 초과로 기록): %s", name, elapsed, done.result())
    else:
        logger.warning("  ⏱️ %-22s %6.1fs  뒤늦게 실패(이미 시간 초과로 기록): %s: %s", name, elapsed, type(exc).__name__, exc)


def run_group(group: str | None = None, task_name: str | None = None) -> dict:
    """
    작업군 또는 단일 태스크를 1회 실행합니다.

    반환: {"group","okCount","failCount","elapsedSeconds","results":[...]}
    """
    if task_name:
        selected = [TASKS_BY_NAME[task_name]] if task_name in TASKS_BY_NAME else []
    elif group in (None, "all"):
        selected = list(ALL_TASKS)
    else:
        selected = [task for task in ALL_TASKS if task.speed == group]

    label = task_name or group or "all"
    if not selected:
        logger.warning("실행할 작업이 없습니다 (%s)", label)
        return {"group": label, "okCount": 0, "failCount": 0, "results": []}

    logger.info("수집 시작 (%s): %d개 작업", label, len(selected))

    run_id = None
    try:
        run_id = store.start_run(group_name=label)
    except Exception as exc:  # noqa: BLE001
        logger.warning("실행 로그 기록 실패(수집은 계속합니다): %s", exc)

    started = time.perf_counter()
    results = []
    ok_count = 0
    failures: list[str] = []

    for task in selected:
        success, detail = run_task(task, run_id)
        results.append({
            "task": task.name,
            "speed": task.speed,
            "ok": success,
            "detail": detail,
        })
        if success:
            ok_count += 1
        else:
            failures.append(f"{task.name}: {detail}")

        # 태스크마다 heartbeat를 남겨, 죽은 수집기를 "진행 중"으로 오인하지
        # 않게 합니다 (13F는 한 태스크가 10분 넘게 걸립니다).
        if run_id is not None:
            store.heartbeat_run(run_id)

    elapsed = time.perf_counter() - started

    if run_id is not None:
        try:
            store.finish_run(
                run_id,
                status=_run_status(ok_count, failures),
                ok_count=ok_count,
                fail_count=len(failures),
                detail="; ".join(failures) or None,
            )
        except Exception as exc:  # noqa: BLE001
            logger.warning("실행 로그 마감 실패: %s", exc)

    logger.info(
        "수집 완료 (%s): 성공 %d · 실패 %d · %.1fs",
        label, ok_count, len(failures), elapsed,
    )

    return {
        "group": label,
        "runId": run_id,
        "okCount": ok_count,
        "failCount": len(failures),
        "elapsedSeconds": round(elapsed, 2),
        "results": results,
    }

