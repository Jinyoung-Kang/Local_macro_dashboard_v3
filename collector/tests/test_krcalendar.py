"""
tests/test_krcalendar.py
거래일·장중 판정이 공휴일을 아는지.
"""
from __future__ import annotations

from datetime import date, datetime

import pytest

from app import krcalendar
from app.krcalendar import KST


@pytest.fixture(autouse=True)
def holidays():
    krcalendar.set_holidays({"2026-01-01", "2026-02-16", "2026-02-17", "2026-02-18"})
    yield
    krcalendar.set_holidays(())


def test_평일_공휴일_아침_이후에는_전_거래일이_최근_거래일이다():
    """
    2026-01-01(목) 10:00에 Daum이 주는 랭킹은 12-31(수)의 것입니다. 주말만 건너뛰면
    이 데이터가 01-01로 쌓여 하루가 복제됩니다.
    """
    assert krcalendar.latest_completed_session(datetime(2026, 1, 1, 10, 0, tzinfo=KST)) == "20251231"


def test_연휴_뒤_장_시작_전에는_연휴_전_거래일이다():
    # 설 연휴 02-16(월)~18(수) → 02-19(목) 08:00의 최근 거래일은 02-13(금)
    assert krcalendar.latest_completed_session(datetime(2026, 2, 19, 8, 0, tzinfo=KST)) == "20260213"
    assert krcalendar.latest_completed_session(datetime(2026, 2, 19, 9, 0, tzinfo=KST)) == "20260219"


def test_주말_규칙은_그대로다():
    assert krcalendar.latest_completed_session(datetime(2026, 9, 12, 8, 0, tzinfo=KST)) == "20260911"   # 토
    assert krcalendar.latest_completed_session(datetime(2026, 9, 11, 7, 0, tzinfo=KST)) == "20260910"   # 금 장전
    assert krcalendar.latest_completed_session(datetime(2026, 9, 11, 10, 0, tzinfo=KST)) == "20260911"


def test_공휴일에는_장중이_아니다():
    assert krcalendar.is_regular_session(datetime(2026, 1, 1, 10, 0, tzinfo=KST)) is False
    assert krcalendar.is_regular_session(datetime(2026, 1, 2, 10, 0, tzinfo=KST)) is True
    assert krcalendar.is_regular_session(datetime(2026, 1, 2, 15, 30, tzinfo=KST)) is False


def test_이전_거래일_목록은_공휴일과_주말을_건너뛴다():
    days = krcalendar.previous_trading_days(date(2026, 2, 19), 3)
    assert days == [date(2026, 2, 13), date(2026, 2, 12), date(2026, 2, 11)]


def test_공휴일_목록이_비어_있으면_주말만_건너뛴다():
    krcalendar.set_holidays(())
    assert krcalendar.latest_completed_session(datetime(2026, 1, 1, 10, 0, tzinfo=KST)) == "20260101"
