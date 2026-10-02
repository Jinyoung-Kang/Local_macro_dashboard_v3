"""
tests/test_task_coalescing.py
같은 태스크가 동시에 두 번 돌지 않는지 고정합니다.

실제 로그에서 나온 문제입니다. 화면 하나를 여는 것만으로 백엔드가 스냅샷을
병렬로 읽고, 오래된 것마다 수집을 요청해 같은 태스크가 네 번 시작됐습니다.

    05:02:02 수집 시작 (cot_history): 1개 작업   ← 같은 초에 4번
    05:02:26 ✅ fred_series 80.03s   ← 스케줄러
    05:03:07 ✅ fred_series 36.30s   ← 요청 1
    05:03:08 ✅ fred_series 50.43s   ← 요청 2

FRED·SEC는 호출 한도가 있고, 같은 행을 동시에 쓰는 것도 안전하지 않습니다.
"""
from __future__ import annotations

import threading
import time
from concurrent.futures import ThreadPoolExecutor

import pytest

from app import tasks


@pytest.fixture(autouse=True)
def _clean_registry():
    tasks._inflight.clear()
    yield
    tasks._inflight.clear()


@pytest.fixture(autouse=True)
def _no_db(monkeypatch):
    """실행 기록은 이 테스트의 관심사가 아닙니다."""
    monkeypatch.setattr(tasks.store, "record_task_run", lambda *a, **kw: None)


def _slow_task(counter: list[int], barrier: threading.Event, name="slow_task"):
    def run() -> str:
        counter.append(1)
        barrier.wait(timeout=5)
        return f"{len(counter)}회차 실행"

    return tasks.Task(name, "fast", run, "")


def test_동시_호출은_한_번만_실행된다():
    counter: list[int] = []
    barrier = threading.Event()
    task = _slow_task(counter, barrier)

    with ThreadPoolExecutor(max_workers=4) as pool:
        futures = [pool.submit(tasks.run_task, task) for _ in range(4)]
        time.sleep(0.3)          # 네 호출이 모두 도착할 시간
        barrier.set()
        results = [f.result(timeout=10) for f in futures]

    assert len(counter) == 1, f"태스크가 {len(counter)}번 실행됐습니다 (1번이어야 합니다)"
    assert all(ok for ok, _ in results), "기다린 쪽도 성공 결과를 받아야 합니다"


def test_기다린_쪽은_같은_결과를_받는다():
    counter: list[int] = []
    barrier = threading.Event()
    task = _slow_task(counter, barrier)

    with ThreadPoolExecutor(max_workers=2) as pool:
        leader = pool.submit(tasks.run_task, task)
        time.sleep(0.2)
        follower = pool.submit(tasks.run_task, task)
        time.sleep(0.2)
        barrier.set()
        leader_ok, leader_detail = leader.result(timeout=10)
        follower_ok, follower_detail = follower.result(timeout=10)

    assert leader_ok and follower_ok
    assert leader_detail in follower_detail
    # 화면에 "왜 이 값인지"가 보여야 합니다.
    assert "함께 사용" in follower_detail


def test_끝나면_다음_호출은_새로_실행된다():
    """합치기가 태스크를 영구히 막아 버리면 안 됩니다."""
    counter: list[int] = []
    barrier = threading.Event()
    barrier.set()
    task = _slow_task(counter, barrier)

    tasks.run_task(task)
    tasks.run_task(task)

    assert len(counter) == 2
    assert tasks._inflight == {}, "끝난 뒤에는 등록이 남아 있으면 안 됩니다"


def test_실패해도_기다리는_쪽을_붙잡아_두지_않는다():
    started = threading.Event()

    def run() -> str:
        started.set()
        time.sleep(0.4)
        raise RuntimeError("외부 소스 폭발")

    task = tasks.Task("boom", "fast", run, "")

    with ThreadPoolExecutor(max_workers=2) as pool:
        leader = pool.submit(tasks.run_task, task)
        started.wait(timeout=5)
        follower = pool.submit(tasks.run_task, task)

        leader_ok, _ = leader.result(timeout=10)
        follower_ok, follower_detail = follower.result(timeout=10)

    assert leader_ok is False
    assert follower_ok is False
    assert "폭발" in follower_detail, "기다린 쪽도 실패 사유를 알아야 합니다"
    assert tasks._inflight == {}


def test_다른_태스크는_서로를_막지_않는다():
    counter_a: list[int] = []
    counter_b: list[int] = []
    barrier = threading.Event()
    task_a = _slow_task(counter_a, barrier, "task_a")
    task_b = _slow_task(counter_b, barrier, "task_b")

    with ThreadPoolExecutor(max_workers=2) as pool:
        fa = pool.submit(tasks.run_task, task_a)
        fb = pool.submit(tasks.run_task, task_b)
        time.sleep(0.3)
        barrier.set()
        fa.result(timeout=10)
        fb.result(timeout=10)

    assert len(counter_a) == 1
    assert len(counter_b) == 1


def test_기다리다_한도를_넘기면_실패가_아니라_합류다(monkeypatch):
    """
    백엔드가 요청한 13F가 주간 수집(10분 넘게)과 겹치면 180초 뒤 (False, …)가 돌아와
    collector_runs에 'fail' 행이 남고 make status가 "마지막 실행: 실패"라고 말했습니다.
    수집은 앞선 호출이 계속 진행 중이고 결과는 다음 조회에 반영되므로 실패가 아닙니다.
    """
    import threading
    from app import tasks

    monkeypatch.setattr(tasks, "_COALESCE_WAIT_SECONDS", 0.05)
    release = threading.Event()
    task = tasks.Task("slow_one", "fast", lambda: release.wait(5) or "끝", "느린 태스크")

    leader = threading.Thread(target=lambda: tasks.run_task(task))
    leader.start()
    deadline = time.time() + 2
    while task.name not in tasks._inflight and time.time() < deadline:
        time.sleep(0.005)

    ok, detail = tasks.run_task(task)      # 두 번째 호출: 기다리다 한도 초과

    assert ok is True
    assert "합류" in detail
    release.set()
    leader.join(timeout=5)
    assert tasks._inflight == {}
