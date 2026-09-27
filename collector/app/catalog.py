"""
app/catalog.py
데이터셋 이름과 신선도 기준의 단일 출처.

수집기(Python)와 백엔드(Java)가 **같은 문자열**을 써야 하므로 한쪽에서
오타가 나면 "수집은 되는데 화면에 안 보이는" 버그가 생깁니다. 구버전
services/datasets.py가 같은 이유로 존재했고, 이 파일이 그 역할을 잇습니다.

Java 쪽 대응 파일: backend/src/main/java/com/macrodash/store/Datasets.java
두 파일은 같은 문자열을 정의하며, 백엔드 테스트가 대조 검증합니다.
"""
from __future__ import annotations

# ==============================================================================
# 스냅샷 (최신 상태 1건)
# ==============================================================================
SNAP_MACRO_COLLECTED = "macro.collected"        # 매크로 카드 + 2Y/10Y 금리
SNAP_SCRAPER_MARKETS = "macro.scraper_markets"  # TradingView/Yahoo 참고 시세
SNAP_FED_LIQUIDITY = "liquidity.fed_net"        # 연준 순유동성
SNAP_KRX_FUTURES = "krx.futures_history"        # KOSPI200 선물 시계열
SNAP_SECTOR_HISTORY = "sector.etf_history"      # 섹터/자산군 ETF 종가
SNAP_FX_HISTORY = "macro.fx_history"            # 환율·달러인덱스 일별 종가 (겹쳐 보기)
SNAP_EQUITY_HISTORY = "equity.price_history"    # 13F 매핑 종목 일별 종가 + 이름→티커 매핑표
SNAP_COT_HISTORY = "cot.multi_asset"            # CFTC COT 통합
SNAP_KR_HOLIDAYS = "calendar.kr_holidays"       # 한국 공휴일 (천문연 특일정보) — 거래소 휴장 판정
SNAP_DART_CORP_CODES = "kr.dart_corp_codes"     # DART 고유번호 ↔ 종목코드 (상장사만)
SNAP_DART_FUNDAMENTALS = "kr.dart_fundamentals" # DART 사업보고서 주요계정 (수급 레이더 종목)
SNAP_FSC_PRICES_META = "kr.fsc_prices_meta"     # 금융위 시세 최신 기준일·시장 합계 (종목별 값은 observations)
SNAP_TOSS_MARKET_FLOWS = "kr.toss_market_flows" # 코스피·코스닥 투자자별 매매대금 (토스증권 공식, 일별)
SNAP_TOSS_RADAR_UNIVERSE = "kr.toss_radar_universe"  # 거래대금 상위 100종목의 투자자 매매 (레이더 폴백용)

# 변동성 지수(^VIX / ^MOVE)는 가장 긴 기간으로 한 번 저장하고, 짧은 기간
# 요청은 잘라 씁니다 (13F에서 q1을 q8에서 유도하는 것과 같은 방식).
VOLATILITY_STORE_PERIOD = "5y"


def snap_daum_futures_trend(lookback_days: int) -> str:
    """
    Daum 선물 투자주체별 매매동향.

    접미사 .CONTRACT는 "계약수 기준"임을 명시합니다. 금액(억원) 모드는
    Daum이 제공하지 않아 제거됐지만(응답이 계약수 그대로였고 1억으로 나눠
    화면이 전부 0이 됐습니다), 예전 키의 저장본과 섞이지 않도록 남깁니다.
    """
    return f"krx.daum_futures_trend.d{lookback_days}.CONTRACT"


def snap_ticker_history(symbol: str, period: str) -> str:
    safe = symbol.replace("^", "").replace("=", "_").replace(".", "_")
    return f"ticker.{safe}.{period}"


def snap_cot_contract(contract_code: str, limit: int) -> str:
    return f"cot.contract.{contract_code}.l{limit}"


def snap_sec_13f(cik: str, max_quarters: int) -> str:
    return f"sec.13f.{cik}.q{max_quarters}"


def snap_fred_series(series_id: str) -> str:
    return f"fred.series.{series_id}"


def snap_radar_scanner(
    market: str,
    investor: str,
    trade_type: str,
    interval_type: str,
) -> str:
    return f"radar.scanner.{market}.{investor}.{trade_type}.{interval_type}"


# ==============================================================================
# 누적 이력 (timeseries / observations)
# ==============================================================================
TS_FRED = "fred"                # series_id = FRED 시리즈 ID
TS_KRX_FUTURES = "krx_futures"  # series_id = 종가/거래량/미결제약정
TS_LIQUIDITY = "fed_liquidity"  # series_id = WALCL/WTREGEN/RRP_M/Net_Liquidity_M

OBS_RADAR = "radar_ranking"     # 날짜별 수급 상위 종목 (과거 조회 불가 소스)
# 기능을 없애 더 쓰지 않는 누적 데이터셋. 수집기가 기동할 때 지웁니다.
#   molit_apt — 서울 아파트 실거래 (2026-09 제거)
RETIRED_OBSERVATION_DATASETS = ("molit_apt",)
OBS_FSC_PRICE = "fsc_price"     # 금융위 공식 일별 시세 (entity = 6자리 종목코드)
TS_FSC_MARKET = "fsc_market"    # 시장별 합계 시가총액·거래대금 (series = "KOSPI.marketCap" 등)
OBS_TOSS_STOCK_FLOW = "toss_stock_flow"  # 종목별 일별 투자자 매매동향 (토스증권 공식, entity = 종목코드)


# ==============================================================================
# 신선도 기준 (초)
# ==============================================================================
MAX_AGE_REALTIME = 15 * 60      # 장중 시세성 (수집 주기 5분의 3배)
MAX_AGE_DAILY = 6 * 60 * 60     # 일별 확정치 (FRED/KRX 마감)
MAX_AGE_SLOW = 24 * 60 * 60     # 분기 공시(13F) 등 거의 변하지 않는 데이터

# 13F 수집기가 저장하는 최대 분기 수. q1은 q8의 앞부분을 잘라 만듭니다.
MAX_TRACKED_QUARTERS = 8
