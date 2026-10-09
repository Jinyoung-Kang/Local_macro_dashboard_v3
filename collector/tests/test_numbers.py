"""
tests/test_numbers.py
수집기의 숫자 해석 규칙 — 모르는 값은 None, 0으로 바꾸지 않음.
"""
from __future__ import annotations

import math

import pytest

from app.numbers import to_float, to_int


@pytest.mark.parametrize("value, expected", [
    ("1,234.5", 1234.5), (" 12 ", 12.0), ("1\xa0234", 1234.0), ("-3.2", -3.2), ("+4", 4.0),
    (7, 7.0), (2.5, 2.5), ("0", 0.0), (0, 0.0),
])
def test_숫자로_읽는다(value, expected):
    assert to_float(value) == expected


@pytest.mark.parametrize("value", [None, "", "  ", "-", "N/A", "abc", float("nan"), "nan", math.inf, "inf", True, False])
def test_모르는_값은_None이다(value):
    assert to_float(value) is None


@pytest.mark.parametrize("value, expected", [("1,234", 1234), (" 7 ", 7), (12, 12), (3.0, 3), ("-5", -5)])
def test_정수로_읽는다(value, expected):
    assert to_int(value) == expected


@pytest.mark.parametrize("value", [None, "", "12.5", 12.5, "x", True, float("nan")])
def test_정수가_아니면_None이다(value):
    assert to_int(value) is None
