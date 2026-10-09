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


@dataclass(frozen=True)
class Task:
    name: str
    speed: str            # fast | slow | weekly
    run: Callable[[], str]
    description: str

