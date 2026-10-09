"""
app/tasks/registry.py
태스크 목록 — 이름·속도군·함수·설명. 새 태스크는 여기에 한 줄.
"""
from __future__ import annotations

import logging
from .base import Task
from .filings import task_sec_13f
from .korea_public import task_dart_fundamentals, task_fsc_prices, task_kr_holidays
from .maintenance import task_run_log_retention
from .markets import task_macro_collected, task_radar_rankings, task_scraper_markets
from .series import (
    task_cot_history, task_daum_futures_trend, task_equity_history, task_fed_liquidity,
    task_fred_series, task_fx_history, task_krx_futures, task_sector_history, task_volatility_history,
)
from .toss import task_toss_market_flows, task_toss_radar_universe, task_toss_stock_flows

logger = logging.getLogger(__name__)


# ==============================================================================
# 태스크 목록 및 실행
# ==============================================================================
ALL_TASKS: tuple[Task, ...] = (
    Task("scraper_markets", "fast", task_scraper_markets, "TradingView/Yahoo 참고 시세"),
    Task("macro_collected", "fast", task_macro_collected, "매크로 카드 전 지표"),
    Task("radar_rankings", "fast", task_radar_rankings, "국내 수급 랭킹 (이력 누적)"),
    Task("fred_series", "slow", task_fred_series, "FRED 금리/신용 시계열 (이력 누적)"),
    Task("fed_liquidity", "slow", task_fed_liquidity, "연준 순유동성 (이력 누적)"),
    Task("krx_futures", "slow", task_krx_futures, "KRX 선물/미결제약정 (이력 누적)"),
    Task("sector_history", "slow", task_sector_history, "섹터·자산군 ETF 종가"),
    Task("fx_history", "slow", task_fx_history, "환율·달러인덱스 일별 종가"),
    Task("equity_history", "slow", task_equity_history, "13F 매핑 종목 일별 종가"),
    Task("volatility_history", "slow", task_volatility_history, "VIX·MOVE 변동성 시계열"),
    Task("cot_history", "slow", task_cot_history, "CFTC COT (주 1회 발표)"),
    Task("daum_futures_trend", "slow", task_daum_futures_trend, "Daum 선물 투자주체별 수급"),
    Task("fsc_prices", "slow", task_fsc_prices, "국내 공식 일별 시세 (금융위)"),
    Task("toss_market_flows", "slow", task_toss_market_flows, "코스피·코스닥 투자자별 매매대금 (토스증권)"),
    Task("toss_stock_flows", "slow", task_toss_stock_flows, "레이더 종목 투자자별 매매동향 (토스증권)"),
    Task("toss_radar_universe", "slow", task_toss_radar_universe, "레이더 폴백 — 거래대금 상위 100종목 투자자 매매 (토스증권)"),
    Task("sec_13f", "weekly", task_sec_13f, "SEC 13F 기관 포트폴리오 (분기 공시)"),
    Task("kr_holidays", "weekly", task_kr_holidays, "한국 공휴일 (천문연 특일정보)"),
    Task("dart_fundamentals", "weekly", task_dart_fundamentals, "국내 종목 재무 (DART 사업보고서)"),
    Task("run_log_retention", "weekly", task_run_log_retention, "수집 실행 기록 정리 (보존 기간이 지난 것만)"),
)

TASKS_BY_NAME = {task.name: task for task in ALL_TASKS}


