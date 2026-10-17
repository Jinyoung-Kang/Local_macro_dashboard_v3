"""
tests/test_toss_call_budget.py
DATA_SOURCES.md의 토스 호출 예산이 코드의 상수와 맞는지.

문서는 투자자 매매 그룹(STOCK_TRADING_TREND·MARKET_INDICATOR)을 '1시간에 최대 42회'(레이더 40종목 + 시장 2)로
적었지만, 그 뒤 관심 종목(최대 60)과 레이더 폴백(거래대금 상위 100종목)이 같은 시간 주기에 붙어 실제로는
최대 202회입니다. 한도를 가늠하거나 403·429 원인을 찾는 사람이 문서의 숫자를 믿으면 부하를 5배 낮게 봅니다.
상수를 바꾸면 이 테스트가 문서도 함께 고치게 합니다.
"""
from __future__ import annotations

import re
from pathlib import Path

import pytest

from app.services import toss as toss_service
from app.tasks import toss

ROOT = Path(__file__).resolve().parents[2]


def _budget_line() -> str:
    doc = (ROOT / "docs/DATA_SOURCES.md").read_text(encoding="utf-8")
    line = next((ln for ln in doc.splitlines() if ln.startswith("- **호출 간격**")), None)
    assert line, "DATA_SOURCES.md에 '호출 간격' 항목이 없습니다"
    start = doc.index(line)
    return doc[start:doc.index("\n- **", start + 1)]


@pytest.mark.xfail(strict=True, reason="문서 수정 전 재현 — '1시간에 최대 42회'가 코드(202회)와 다름")
def test_DATA_SOURCES의_토스_호출_예산이_코드_상수와_같다():
    text = _budget_line()
    stated = re.search(r"1시간에 최대 (\d+)회", text)
    assert stated, text
    per_hour = (toss.TOSS_STOCK_LIMIT + toss.TOSS_WATCHED_LIMIT + toss.TOSS_RADAR_UNIVERSE
                + len(toss_service.MARKET_SYMBOLS))
    assert int(stated.group(1)) == per_hour
    # 숫자만 다시 적으면 또 어긋나므로, 어떤 상수의 합인지 함께 적습니다.
    for name in ("TOSS_STOCK_LIMIT", "TOSS_WATCHED_LIMIT", "TOSS_RADAR_UNIVERSE"):
        assert name in text, name
