"""
app/tasks/base.py
태스크가 함께 쓰는 것 — 결과 없음 예외, 실패 사유 덧붙이기, 태스크 정의, 레이더 조합.
"""
from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Callable
from zoneinfo import ZoneInfo


logger = logging.getLogger(__name__)

logger = logging.getLogger(__name__)

KST = ZoneInfo("Asia/Seoul")

# 수급 레이더에서 미리 받아 둘 조건 조합. 전부 받으면 수집 시간이 과도하게
# 길어지므로 화면 기본값만 받습니다.
RADAR_COMBINATIONS = (
    ("KOSPI", "외국인", "순매수", "TODAY"),
    ("KOSPI", "기관", "순매수", "TODAY"),
    ("KOSPI", "외국인", "순매도", "TODAY"),
)

DAUM_TREND_LOOKBACK = 25


class EmptyResult(Exception):
    """수집은 끝났지만 쓸 수 있는 데이터가 없음 → 실패로 집계합니다."""


def _reason_suffix(reasons: list[str | None]) -> str:
    """
    실패 사유를 메시지 끝에 덧붙입니다.

    "0/12 시리즈"만 남으면 운영자가 무엇을 고쳐야 할지 알 수 없습니다.
    같은 사유가 반복되는 경우가 대부분이므로 중복을 제거하고 두 개까지만
    싣습니다(상태 화면의 detail 길이 제한 1000자를 넘기지 않기 위함).
    """
    unique: list[str] = []
    for reason in reasons:
        if reason and reason not in unique:
            unique.append(reason)
    if not unique:
        return ""
    return " (사유: " + " / ".join(unique[:2]) + ")"


def carry_over_missing(
    fresh: dict,
    previous: dict | None,
    expected,
    reason: str | None,
    has_data: Callable[[dict], bool],
) -> list[str]:
    """
    이번 수집에서 빠졌거나 빈 항목을 **직전 저장본의 같은 항목**으로 잇습니다(QA-032, fast 태스크의 QA-001과 같은 규칙).

    일부만 실패한 수집이 받은 것만으로 저장본을 덮으면, 빠진 항목의 마지막 정상 값이 다음 실행까지 사라졌습니다.
    이어받은 항목은 값을 그대로 두고 ``isStale``·``staleReason``만 더합니다. 직전 저장본에도 없으면 그대로 빠집니다.

    :param fresh: 이번에 받은 항목(키 → 항목). 이 사전을 고칩니다
    :param expected: 있어야 하는 키(태스크가 받으려던 목록) — 목록에서 뺀 키는 잇지 않습니다
    :return: 이어받은 키
    """
    previous = previous or {}
    carried: list[str] = []
    for key in expected:
        if key in fresh and isinstance(fresh[key], dict) and has_data(fresh[key]):
            continue
        before = previous.get(key)
        if not isinstance(before, dict) or not has_data(before):
            continue
        fresh[key] = {**before, "isStale": True, "staleReason": reason or "수집 실패"}
        carried.append(key)
    return carried


def previous_payload(name: str) -> dict:
    """직전 저장본의 내용(없으면 빈 사전)."""
    from .. import store

    snapshot = store.read_snapshot(name)
    return snapshot.payload if snapshot and isinstance(snapshot.payload, dict) else {}


def carried_suffix(carried: list[str]) -> str:
    return f" · 이전 값 {len(carried)}개({', '.join(carried[:5])}{'…' if len(carried) > 5 else ''})" if carried else ""


@dataclass(frozen=True)
class Task:
    name: str
    speed: str            # fast | slow | weekly
    run: Callable[[], str]
    description: str

