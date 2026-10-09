"""
app/numbers.py
외부 응답의 숫자 해석 — 수집기 전체가 한 규칙을 씁니다.

예전에는 소스마다 `_to_float`류가 9가지였고, 규칙이 조금씩 달랐습니다(쉼표·공백을 지우는지, NaN·빈 문자열·'-'을
어떻게 보는지). 같은 "1,234"가 어떤 소스에서는 숫자, 어떤 소스에서는 None이 됐습니다(코드 리뷰 1-4·1-6).

규칙: **모르는 값은 None입니다. 0으로 바꾸지 않습니다**(PRINCIPLES "없는 숫자를 만들지 않는다").
0으로 채우거나 0을 '모름'으로 보는 것은 소스의 사정이라, 그런 곳은 각 서비스에 이름 붙은 한 줄 함수로 둡니다
(예: KIS는 모르는 값을 0으로 줌 → `services/kis.py`의 `_to_float`).
"""
from __future__ import annotations

import math

_STRIP = (",", " ", "\xa0", " ")


def _clean(value) -> str | None:
    text = str(value).strip()
    for mark in _STRIP:
        text = text.replace(mark, "")
    return text or None


def to_float(value) -> float | None:
    """
    숫자로 읽을 수 있으면 float, 아니면 None.

    - None·빈 문자열·'-'·'N/A' 같은 글자 → None
    - '1,234.5'·' 12 '·'1\xa0234'처럼 쉼표·공백이 섞인 문자열도 읽습니다
    - NaN·무한대 → None (모르는 값입니다)
    - True/False → None (숫자로 쓰면 1·0이 되어 틀린 값이 됩니다)
    """
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        number = float(value)
    else:
        text = _clean(value)
        if text is None:
            return None
        try:
            number = float(text)
        except (TypeError, ValueError):
            return None
    return number if math.isfinite(number) else None


def to_int(value) -> int | None:
    """
    정수로 읽을 수 있으면 int, 아니면 None. '1,234'는 읽고, '12.5'처럼 소수가 있으면 None입니다
    (반올림하면 없던 값이 됩니다).
    """
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, float):
        return int(value) if math.isfinite(value) and value.is_integer() else None
    text = _clean(value)
    if text is None:
        return None
    try:
        return int(text)
    except (TypeError, ValueError):
        return None
