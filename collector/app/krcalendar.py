"""
app/krcalendar.py
한국 거래소 달력 — 거래일·장중 판정을 한 곳에서.

[왜 모았나]
수급 레이더(radar.py)는 "가장 최근에 끝난 거래일"을 주말만 빼고 계산했고, 금융위
시세(tasks.py)는 따로 공휴일 집합을 읽어 썼습니다. 그래서 평일 공휴일(설·추석·대체공휴일)
09:00 이후에는 Daum이 주는 **전 거래일** 랭킹이 공휴일 날짜로 이력에 쌓였습니다 — 같은
데이터가 두 날짜로 복제되고, 백업은 "수집기가 2026-01-01에 저장한 값"이라고 말했습니다.

[공휴일은 어디서 오나]
천문연 특일정보를 kr_holidays 태스크가 스냅샷(calendar.kr_holidays)에 저장합니다. 이 모듈은
그 날짜 집합을 **메모리에 들고** 판정합니다. 함수가 호출될 때마다 DB를 읽지 않는 이유:
거래일 판정은 순수 계산이어야 테스트와 재사용이 쉽고, DB가 없는 환경(단위 테스트)에서도
돌아야 합니다. 기동 때 load_from_store()로 채우고, 태스크가 새로 받으면 set_holidays()로
갱신합니다. 비어 있으면 주말만 건너뜁니다(첫 기동, 발표 전 연도).

거래소 휴장일과 공휴일은 다릅니다(PRINCIPLES.md). 여기서는 "공휴일 = 휴장"으로만 다루고,
공휴일이 아닌 휴장일(연말 마지막 거래일 등)은 다루지 않습니다 — 그날은 데이터가 비어
'아직 발표 전'으로 처리되므로 잘못 쌓이지는 않습니다.
"""
from __future__ import annotations

import logging
from datetime import date, datetime, time, timedelta
from typing import Iterable
from zoneinfo import ZoneInfo

logger = logging.getLogger(__name__)

KST = ZoneInfo("Asia/Seoul")
OPEN = time(9, 0)
CLOSE = time(15, 30)

_holidays: set[str] = set()


def set_holidays(dates: Iterable[str]) -> None:
    """공휴일 집합(YYYY-MM-DD)을 교체합니다."""
    global _holidays
    _holidays = {str(d)[:10] for d in dates}


def holidays() -> set[str]:
    return set(_holidays)


def load_from_store() -> int:
    """
    저장된 공휴일 스냅샷을 메모리에 올립니다. 올린 날짜 수를 돌려주고, 실패하면 0
    (판정은 주말만 건너뛰는 상태로 계속됩니다 — 기동을 막을 일은 아닙니다).
    """
    from . import catalog, store   # 순수 계산 모듈이 DB를 알지 않도록 여기서만 가져옵니다

    try:
        snap = store.read_snapshot(catalog.SNAP_KR_HOLIDAYS)
    except Exception as exc:  # noqa: BLE001
        logger.warning("공휴일 저장본을 읽지 못했습니다(주말만 건너뜁니다): %s", exc)
        return 0
    years = ((snap.payload or {}).get("years") or {}) if snap else {}
    set_holidays(
        day["date"] for year in years.values() for day in (year.get("holidays") or [])
        if isinstance(day, dict) and day.get("date")
    )
    return len(_holidays)


def is_trading_day(day: date) -> bool:
    return day.weekday() < 5 and day.isoformat() not in _holidays


def previous_trading_day(day: date) -> date:
    """day보다 앞선 가장 가까운 거래일."""
    cursor = day - timedelta(days=1)
    while not is_trading_day(cursor):
        cursor -= timedelta(days=1)
    return cursor


def previous_trading_days(today: date, count: int) -> list[date]:
    """어제부터 거슬러 올라가 거래일 count개(최근 날이 앞). 오늘은 넣지 않습니다."""
    out: list[date] = []
    cursor = today
    while len(out) < count:
        cursor = previous_trading_day(cursor)
        out.append(cursor)
    return out


def latest_completed_session(now: datetime | None = None) -> str:
    """
    지금 이 시점에 Naver/Daum이 '현재 데이터'로 보여주는 거래일(YYYYMMDD).

    - 거래일 09:00 이후: 오늘 (장중이면 집계 중인 오늘)
    - 그 외(장 시작 전·주말·공휴일): 가장 최근 거래일
    """
    now = now or datetime.now(KST)
    today = now.date()
    if is_trading_day(today) and now.time() >= OPEN:
        return today.strftime("%Y%m%d")
    return previous_trading_day(today).strftime("%Y%m%d")


def is_regular_session(now: datetime | None = None) -> bool:
    """정규장(09:00~15:30) 중인가. 공휴일은 장이 열리지 않습니다."""
    now = now or datetime.now(KST)
    return is_trading_day(now.date()) and OPEN <= now.time() < CLOSE
