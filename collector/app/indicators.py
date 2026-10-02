"""
app/indicators.py
수집 대상 정의 (구버전 config.py의 지표 매핑 부분).

[구버전과 달라진 점 — 의도한 개선]
구버전은 지표 이름에 표시용 마크다운을 섞어 넣었습니다.

    "달러 인덱스 (DXY) :gray[[실시간]]": "DX-Y.NYB"

그래서 화면에 쓰려면 정규식으로 태그를 벗겨야 했고, 중첩 대괄호 때문에
차트 목록에 "달러 인덱스 (DXY) ]" 처럼 닫는 괄호가 남는 버그가 있었습니다
(구버전 services/macro_service.py clean_tag_ui의 주석 참고).

또 국채 보정은 **표시 이름 문자열**을 키로 삼았습니다(BOND_SCRAPER_KEY_MAP).
이름을 한 글자만 고쳐도 보정이 조용히 끊깁니다.

여기서는 지표마다 변하지 않는 `key`를 두고, 표시 이름과 지연 표기(note)를
필드로 분리합니다. 화면은 태그를 벗길 필요가 없고, 보정은 key로 연결됩니다.
"""
from __future__ import annotations

# ==============================================================================
# 1. 거시경제 매크로 지표 카테고리
# ==============================================================================
# note: 화면이 배지로 표시하는 데이터 지연 수준. 값 자체의 성격이라 여기 둡니다.
# market: 이 값이 거래되는 시장(거래 시간표 id). 화면이 "개장/마감" 배지를 계산합니다
#         (frontend/src/lib/marketSessions.ts — id와 거래 시간·출처가 거기 있습니다).
#         개장 여부는 "보는 시각"에 따라 바뀌므로 수집 시점에 계산해 저장하지 않습니다.
MACRO_CATEGORIES = [
    {
        "id": "fx",
        "title": "💵 통화 및 환율",
        "note": "실시간",
        "items": [
            {"key": "dxy", "market": "fx", "name": "달러 인덱스 (DXY)", "ticker": "DX-Y.NYB", "note": "실시간",
             "unit": "pt"},
            {"key": "usdkrw", "market": "fx", "name": "원/달러 (USD/KRW)", "ticker": "KRW=X", "note": "실시간",
             "unit": "원"},
            {"key": "usdjpy", "market": "fx", "name": "달러/엔 (USD/JPY)", "ticker": "JPY=X", "note": "실시간",
             "unit": "엔"},
            {"key": "jpykrw", "market": "fx", "name": "엔/원 100엔당 (JPY/KRW)", "ticker": "JPYKRW=X",
             "note": "실시간", "unit": "원"},
        ],
    },
    {
        "id": "ust",
        "title": "🏛️ 미국 국채 수익률",
        "note": "TradingView 참고 시세",
        "items": [
            {"key": "us02y", "market": "ust", "name": "미국채 2년물 수익률(%)", "ticker": "ZT=F", "note": "TradingView 참고"},
            {"key": "us10y", "market": "ust", "name": "미국채 10년물 수익률(%)", "ticker": "^TNX", "note": "TradingView 참고"},
            {"key": "us30y", "market": "ust", "name": "미국채 30년물 수익률(%)", "ticker": "^TYX", "note": "TradingView 참고"},
        ],
    },
    {
        "id": "commodity",
        "title": "🛢️ 원자재",
        "note": "15분 지연",
        "items": [
            {"key": "wti", "market": "cme", "name": "WTI 원유 ($)", "ticker": "CL=F", "note": "15분 지연"},
            {"key": "brent", "market": "ice_brent", "name": "브렌트유 ($)", "ticker": "BZ=F", "note": "15분 지연"},
            {"key": "gold", "market": "cme", "name": "금 선물 ($)", "ticker": "GC=F", "note": "15분 지연"},
        ],
    },
    {
        "id": "us_equity",
        "title": "🇺🇸 미국 주가지수 및 선물",
        "note": "15분 지연",
        "items": [
            {"key": "sp500", "market": "nyse", "name": "S&P 500", "ticker": "^GSPC", "note": "15분 지연"},
            {"key": "sp500_fut", "market": "cme", "name": "S&P 500 선물 (ES)", "ticker": "ES=F", "note": "15분 지연"},
            {"key": "ndx", "market": "nyse", "name": "나스닥 100", "ticker": "^NDX", "note": "15분 지연"},
            {"key": "ndx_fut", "market": "cme", "name": "나스닥 선물 (NQ)", "ticker": "NQ=F", "note": "15분 지연"},
        ],
    },
    {
        "id": "asia_equity",
        "title": "🌏 아시아 주요 주가지수",
        "note": "15분 지연",
        "items": [
            {"key": "kospi", "market": "krx", "name": "코스피 (KOSPI)", "ticker": "^KS11", "note": "15분 지연"},
            {"key": "nikkei", "market": "tse", "name": "닛케이 225 (Nikkei)", "ticker": "^N225", "note": "15분 지연"},
            {"key": "shanghai", "market": "sse", "name": "상하이 종합 (SSE)", "ticker": "000001.SS", "note": "15분 지연"},
            {"key": "hsi", "market": "hkex", "name": "항셍 지수 (HSI)", "ticker": "^HSI", "note": "15분 지연"},
        ],
    },
]

# 야간선물/해외 지수선물 스크래핑 결과가 주입되는 카테고리
SCRAPED_INJECT_CATEGORY = "asia_equity"

# 주입할 선물 카드. key는 services/scraper.py SCRAPER_MARKETS의 key입니다.
# fallback: 선물 조회가 실패했을 때 대신 보여 줄 같은 거래소의 지수. 이름에 "(선물 대체)",
#           배지에 "선물 조회 실패"를 적어 지수 값임을 숨기지 않습니다.
#
# 코스피200 야간선물 — 예전 이름의 "(CME 연계)"는 뺐습니다. 2025-06-09부터 KRX가
# 야간 파생상품 시장(18:00~06:00)을 직접 운영합니다(Korea Times 2025-06-09 보도).
SCRAPED_FUTURES = (
    {"key": "kospi200_night", "name": "코스피200 야간선물", "market": "krx_futures"},
    {"key": "nikkei_fut", "name": "닛케이225 선물", "market": "ose_futures",
     "fallback": {"key": "nikkei", "name": "닛케이225 지수 (선물 대체)",
                  "market": "tse"}},
    {"key": "hsi_fut", "name": "항셍 선물", "market": "hkex_futures",
     "fallback": {"key": "hang_seng", "name": "항셍 지수 (선물 대체)",
                  "market": "hkex"}},
)

# ==============================================================================
# 1-1. 환율 비교 차트 (여러 계열 겹쳐 보기)
# ==============================================================================
# 위 fx 카테고리와 **같은 티커**를 씁니다. 차트만 따로 티커를 고르면 카드의
# 최근값과 차트의 끝값이 어긋나고, 보는 사람은 둘 중 무엇이 맞는지 알 수
# 없습니다. 목록을 복사하지 않고 그때그때 꺼내 오는 이유입니다.
FX_HISTORY_PERIOD = "5y"


def fx_history_specs() -> list[dict]:
    """환율 비교 차트가 쓸 계열 정의 (매크로 카드 fx 카테고리 그대로)."""
    for category in MACRO_CATEGORIES:
        if category["id"] == "fx":
            return [dict(item) for item in category["items"]]
    return []


# TradingView Scanner 수익률로 덮어쓸 지표.
# ZT=F는 2년 국채 **선물 가격**(~100pt)이라 "수익률(%)" 라벨과 단위가 맞지
# 않습니다. Scanner의 실제 수익률로 수집 시점에 한 번 보정해, 화면·AI 텍스트·
# 스프레드 계산이 모두 같은 값을 보게 합니다.
BOND_SCANNER_KEYS = ("us02y", "us10y", "us30y")

# 이 가운데 yfinance 원본이 **선물 가격**이라 보정 없이는 수익률로 쓸 수 없는 키.
# 보정에 실패하면 카드를 '수집 실패'로 둡니다 — 101.5(가격)를 101.5%(수익률)로
# 보여 주는 것보다 낫습니다. 10년·30년의 원본(^TNX·^TYX)은 이미 수익률입니다.
BOND_PRICE_ONLY_KEYS = ("us02y",)

# 보정 실패 시 전일 종가를 채워 줄 FRED 공식 일별 확정치.
BOND_FRED_FALLBACK = {
    "us02y": "DGS2",
    "us10y": "DGS10",
    "us30y": "DGS30",
}


# ==============================================================================
# 2. SEC 13F 주요 기관
# ==============================================================================
INSTITUTIONS = [
    {"key": "nps", "name": "🇰🇷 국민연금 (National Pension Service)", "cik": "0001608046",
     "desc": "글로벌 자산배분 및 미국 대형 우량주 중심 장기 투자"},
    {"key": "norges", "name": "🇳🇴 노르웨이 국부펀드 (Norges Bank / GPFG)", "cik": "0001374170",
     "desc": "세계 최대 규모의 글로벌 국부펀드, 인덱스형 거인"},
    {"key": "cppib", "name": "🇨🇦 캐나다 연금투자위원회 (CPPIB)", "cik": "0001283718",
     "desc": "캐나다 연금을 운용하는 대형 연기금, 글로벌 자산배분 중심"},
    {"key": "apg", "name": "🇳🇱 네덜란드 연금자산운용 (APG Asset Management)", "cik": "0001434819",
     "desc": "네덜란드 최대 연기금 자산운용사, 글로벌 분산투자"},
    {"key": "pif", "name": "🇸🇦 사우디 국부펀드 (Public Investment Fund - PIF)", "cik": "0001767640",
     "desc": "대규모 글로벌 전략적 투자, 공격적 성장 베팅"},
    {"key": "blackrock", "name": "🇺🇸 블랙록 (BlackRock)", "cik": "0002012383",
     "desc": "세계 최대 자산운용사, 광범위한 글로벌 자산군"},
    {"key": "vanguard", "name": "🇺🇸 뱅가드 (Vanguard Group)", "cik": "0000102909",
     "desc": "글로벌 인덱스 펀드의 거두, 시장 전체를 아우르는 포트폴리오"},
    {"key": "berkshire", "name": "🇺🇸 버크셔 해서웨이 (Berkshire Hathaway)", "cik": "0001067983",
     "desc": "가치투자 포트폴리오, 핵심 우량주 집중"},
    {"key": "duquesne", "name": "🇺🇸 듀케인 패밀리 오피스 (Duquesne Family Office)", "cik": "0001536411",
     "desc": "스탠리 드러켄밀러, 테크 트렌드 포착형 매크로 운용"},
    {"key": "fisher", "name": "🇺🇸 피셔 자산운용 (Fisher Asset Management)", "cik": "0000850529",
     "desc": "켄 피셔의 글로벌 성장주·빅테크 중심 탑다운 롱온리 전략"},
    {"key": "bridgewater", "name": "🇺🇸 브리지워터 어소시에이츠 (Bridgewater)", "cik": "0001350694",
     "desc": "레이 달리오 설립, 올웨더 및 글로벌 매크로 헤지펀드"},
    {"key": "scion", "name": "🇺🇸 사이언 자산운용 (Scion Asset Management)", "cik": "0001649339",
     "desc": "마이클 버리의 역발상 딥밸류 및 숏/롱 전략"},
]


# ==============================================================================
# 3. S&P 500 11개 섹터 ETF
# ==============================================================================
SECTOR_ETFS = {
    "XLK": {"name": "정보기술 (Technology)", "type": "공격 / 성장"},
    "XLC": {"name": "통신서비스 (Communication)", "type": "공격 / 성장"},
    "XLY": {"name": "임의소비재 (Consumer Discretionary)", "type": "경기민감 / 성장"},
    "XLI": {"name": "산업재 (Industrials)", "type": "경기민감 / 가치"},
    "XLF": {"name": "금융 (Financials)", "type": "경기민감 / 가치"},
    "XLB": {"name": "소재 (Materials)", "type": "경기민감 / 원자재"},
    "XLE": {"name": "에너지 (Energy)", "type": "경기민감 / 원자재"},
    "XLV": {"name": "헬스케어 (Health Care)", "type": "방어주"},
    "XLP": {"name": "필수소비재 (Consumer Staples)", "type": "방어주"},
    "XLU": {"name": "유틸리티 (Utilities)", "type": "방어주 / 배당"},
    "XLRE": {"name": "부동산 (Real Estate)", "type": "방어주 / 금리민감"},
}


# ==============================================================================
# 4. 글로벌 자산군 ETF
# ==============================================================================
ASSET_CLASS_ETFS = {
    "SPY": {"name": "미국 대형주 (S&P 500)", "category": "주식"},
    "QQQ": {"name": "미국 기술주 (Nasdaq 100)", "category": "주식"},
    "IWM": {"name": "미국 중소형주 (Russell 2000)", "category": "주식"},
    "EEM": {"name": "신흥국 주식 (Emerging Markets)", "category": "주식"},
    "TLT": {"name": "미국 20년+ 장기국채", "category": "채권"},
    "IEF": {"name": "미국 7-10년 중기국채", "category": "채권"},
    "SHY": {"name": "미국 1-3년 단기국채", "category": "채권"},
    "GLD": {"name": "금 (Gold)", "category": "원자재"},
    "USO": {"name": "원유 (WTI Crude Oil)", "category": "원자재"},
    "DBA": {"name": "농산물 (Agriculture)", "category": "원자재"},
    "UUP": {"name": "미국 달러 인덱스 ETF", "category": "통화"},
}


# ==============================================================================
# 5. 로테이션 모멘텀 대상
# ==============================================================================
ROTATION_SECTORS = {
    "정보기술": "XLK", "금융": "XLF", "헬스케어": "XLV", "임의소비재": "XLY",
    "산업재": "XLI", "통신서비스": "XLC", "에너지": "XLE", "필수소비재": "XLP",
    "부동산": "XLRE", "유틸리티": "XLU", "소재": "XLB",
}

ROTATION_ASSET_CLASSES = {
    "미국 주식": "SPY", "글로벌 주식": "ACWI", "미국 장기국채": "TLT",
    "미국 중기국채": "IEF", "하이일드채권": "HYG", "금": "GLD",
    "원유": "USO", "달러": "UUP", "원자재 종합": "DBC",
}


def all_rotation_tickers() -> tuple[str, ...]:
    """섹터·자산군 화면이 쓰는 전체 티커 (중복 제거, 정렬)."""
    return tuple(sorted(set(
        list(SECTOR_ETFS)
        + list(ASSET_CLASS_ETFS)
        + list(ROTATION_SECTORS.values())
        + list(ROTATION_ASSET_CLASSES.values())
        + ["SPY"]
    )))


# ==============================================================================
# 6. CFTC COT 자산
# ==============================================================================
COT_ASSETS = {
    "S&P 500 E-Mini": {"code": "13874A", "category": "주식"},
    "NASDAQ 100 E-Mini": {"code": "209742", "category": "주식"},
    "미국 국채 10년물": {"code": "043602", "category": "채권"},
    "달러 인덱스": {"code": "098662", "category": "통화"},
    "WTI 원유": {"code": "067651", "category": "원자재"},
    "금": {"code": "088691", "category": "원자재"},
}

COT_YEARS = 3
COT_WEEKS = int(COT_YEARS * 52 + 10)


# ==============================================================================
# 7. FRED 시리즈
# ==============================================================================
# 화면이 직접 쓰는 기본 시리즈
FRED_BASE_SERIES = (
    "DGS2", "DGS10", "DGS30", "DGS3MO",
    "BAMLH0A0HYM2",   # 하이일드 OAS
    "STLFSI4",        # 세인트루이스 연준 금융스트레스
    "CPF3M",          # 3M 금융 CP
)

# 심화 지표. 30Y-3M은 FRED에 시리즈가 없어 DGS30 − DGS3MO로 백엔드가 계산합니다.
FRED_ADVANCED_SERIES = (
    "T10Y3M",       # 장단기 금리차 10Y-3M — 뉴욕 연준 침체확률 모델의 스프레드
    "DFII10",       # 10년 실질금리 (TIPS)
    "T10YIE",       # 10년 기대인플레이션 (BEI)
    "BAMLC0A0CM",   # 투자등급(IG) 회사채 스프레드
    "NFCI",         # 시카고 연준 금융상황지수
)

FRED_ALL_SERIES = FRED_BASE_SERIES + FRED_ADVANCED_SERIES

# 연준 순유동성 구성 시리즈
LIQUIDITY_SERIES = ("WALCL", "WTREGEN", "RRPONTSYD")
