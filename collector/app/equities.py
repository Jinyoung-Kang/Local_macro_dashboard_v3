"""
app/equities.py
13F 공시 종목명 → 티커 · 섹터 매핑표.

[왜 이 파일이 필요한가]
SEC 13F 공시에는 **종목코드(티커)가 없습니다.** 실제 저장본 한 건은 이렇습니다.

    {"name": "MICROSOFT CORP", "class": "COM", "cusip": "485151262",
     "value": 7140353471, "shares": 14383409, "weight": 17.157}

가격을 받으려면 티커가 있어야 하는데, SEC는 CUSIP↔티커 매핑을 무료로 주지
않습니다(상용 라이선스 데이터입니다). 그래서 **대형주 이름을 직접 적어 둡니다.**

[이 방식의 한계 — 화면이 반드시 함께 적어야 합니다]
매핑표에 없는 종목은 분석에서 빠집니다. 버크셔처럼 대형주에 집중한 포트폴리오는
비중의 대부분이 덮이지만, 노르웨이 국부펀드처럼 수천 종목을 든 기관은 커버리지가
낮게 나옵니다. **덮은 비중을 항상 함께 표시하고, 덮지 못한 몫은 "모른다"로
둡니다** — 덮인 것만으로 계산한 값을 전체인 것처럼 보여 주면 안 됩니다.

[섹터]
섹터 이름은 섹터 로테이션 화면(indicators.SECTOR_ETFS)과 **같은 한국어**를 씁니다.
다른 이름을 쓰면 같은 대시보드 안에서 "정보기술"과 "IT"가 따로 놀게 됩니다.
"""
from __future__ import annotations

import re

# ==============================================================================
# 매핑표
# ==============================================================================
# 키는 13F 공시에 찍히는 이름(대문자)입니다. 값은 (야후 티커, 섹터).
#
# 같은 회사가 클래스별로 따로 공시되기도 합니다(ALPHABET CL A / CL C).
# 가격이 사실상 같으므로 하나의 티커로 모읍니다 — 위험 분석에 쓰는 값이라
# 클래스 간 미세한 차이는 결론을 바꾸지 않습니다.
EQUITY_MAP: dict[str, tuple[str, str]] = {
    # ── 정보기술 ────────────────────────────────────────────────────────
    "APPLE INC": ("AAPL", "정보기술"),
    "MICROSOFT CORP": ("MSFT", "정보기술"),
    "NVIDIA CORP": ("NVDA", "정보기술"),
    "BROADCOM INC": ("AVGO", "정보기술"),
    "ADVANCED MICRO DEVICES INC": ("AMD", "정보기술"),
    "INTEL CORP": ("INTC", "정보기술"),
    "QUALCOMM INC": ("QCOM", "정보기술"),
    "TEXAS INSTRS INC": ("TXN", "정보기술"),
    "TEXAS INSTRUMENTS INC": ("TXN", "정보기술"),
    "ORACLE CORP": ("ORCL", "정보기술"),
    "SALESFORCE INC": ("CRM", "정보기술"),
    "ADOBE INC": ("ADBE", "정보기술"),
    "CISCO SYS INC": ("CSCO", "정보기술"),
    "CISCO SYSTEMS INC": ("CSCO", "정보기술"),
    "ACCENTURE PLC": ("ACN", "정보기술"),
    "INTERNATIONAL BUSINESS MACHS CORP": ("IBM", "정보기술"),
    "APPLIED MATLS INC": ("AMAT", "정보기술"),
    "APPLIED MATERIALS INC": ("AMAT", "정보기술"),
    "LAM RESEARCH CORP": ("LRCX", "정보기술"),
    "KLA CORP": ("KLAC", "정보기술"),
    "MICRON TECHNOLOGY INC": ("MU", "정보기술"),
    "ANALOG DEVICES INC": ("ADI", "정보기술"),
    "SYNOPSYS INC": ("SNPS", "정보기술"),
    "CADENCE DESIGN SYS INC": ("CDNS", "정보기술"),
    "INTUIT INC": ("INTU", "정보기술"),
    "SERVICENOW INC": ("NOW", "정보기술"),
    "PALO ALTO NETWORKS INC": ("PANW", "정보기술"),
    "ARISTA NETWORKS INC": ("ANET", "정보기술"),
    "DELL TECHNOLOGIES INC": ("DELL", "정보기술"),
    "HEWLETT PACKARD ENTERPRISE CO": ("HPE", "정보기술"),
    "NETAPP INC": ("NTAP", "정보기술"),
    "TAIWAN SEMICONDUCTOR MFG LTD": ("TSM", "정보기술"),

    # ── 통신서비스 ──────────────────────────────────────────────────────
    "ALPHABET INC": ("GOOGL", "통신서비스"),
    "META PLATFORMS INC": ("META", "통신서비스"),
    "NETFLIX INC": ("NFLX", "통신서비스"),
    "DISNEY WALT CO": ("DIS", "통신서비스"),
    "WALT DISNEY CO": ("DIS", "통신서비스"),
    "COMCAST CORP": ("CMCSA", "통신서비스"),
    "T-MOBILE US INC": ("TMUS", "통신서비스"),
    "VERIZON COMMUNICATIONS INC": ("VZ", "통신서비스"),
    "AT&T INC": ("T", "통신서비스"),

    # ── 임의소비재 ──────────────────────────────────────────────────────
    "AMAZON COM INC": ("AMZN", "임의소비재"),
    "TESLA INC": ("TSLA", "임의소비재"),
    "HOME DEPOT INC": ("HD", "임의소비재"),
    "MCDONALDS CORP": ("MCD", "임의소비재"),
    "NIKE INC": ("NKE", "임의소비재"),
    "STARBUCKS CORP": ("SBUX", "임의소비재"),
    "LOWES COS INC": ("LOW", "임의소비재"),
    "BOOKING HLDGS INC": ("BKNG", "임의소비재"),
    "TJX COS INC NEW": ("TJX", "임의소비재"),
    "TJX COMPANIES INC": ("TJX", "임의소비재"),
    "GENERAL MTRS CO": ("GM", "임의소비재"),
    "FORD MTR CO DEL": ("F", "임의소비재"),

    # ── 필수소비재 ──────────────────────────────────────────────────────
    "PROCTER & GAMBLE CO": ("PG", "필수소비재"),
    "COCA COLA CO": ("KO", "필수소비재"),
    "PEPSICO INC": ("PEP", "필수소비재"),
    "WALMART INC": ("WMT", "필수소비재"),
    "COSTCO WHSL CORP NEW": ("COST", "필수소비재"),
    "COSTCO WHOLESALE CORP": ("COST", "필수소비재"),
    "PHILIP MORRIS INTL INC": ("PM", "필수소비재"),
    "ALTRIA GROUP INC": ("MO", "필수소비재"),
    "MONDELEZ INTL INC": ("MDLZ", "필수소비재"),
    "COLGATE PALMOLIVE CO": ("CL", "필수소비재"),
    "KRAFT HEINZ CO": ("KHC", "필수소비재"),

    # ── 헬스케어 ────────────────────────────────────────────────────────
    "LILLY ELI & CO": ("LLY", "헬스케어"),
    "ELI LILLY & CO": ("LLY", "헬스케어"),
    "UNITEDHEALTH GROUP INC": ("UNH", "헬스케어"),
    "JOHNSON & JOHNSON": ("JNJ", "헬스케어"),
    "ABBVIE INC": ("ABBV", "헬스케어"),
    "MERCK & CO INC": ("MRK", "헬스케어"),
    "THERMO FISHER SCIENTIFIC INC": ("TMO", "헬스케어"),
    "ABBOTT LABS": ("ABT", "헬스케어"),
    "PFIZER INC": ("PFE", "헬스케어"),
    "DANAHER CORP": ("DHR", "헬스케어"),
    "AMGEN INC": ("AMGN", "헬스케어"),
    "BRISTOL MYERS SQUIBB CO": ("BMY", "헬스케어"),
    "GILEAD SCIENCES INC": ("GILD", "헬스케어"),
    "MEDTRONIC PLC": ("MDT", "헬스케어"),
    "CVS HEALTH CORP": ("CVS", "헬스케어"),
    "ELEVANCE HEALTH INC": ("ELV", "헬스케어"),
    "VERTEX PHARMACEUTICALS INC": ("VRTX", "헬스케어"),
    "REGENERON PHARMACEUTICALS": ("REGN", "헬스케어"),
    "INTUITIVE SURGICAL INC": ("ISRG", "헬스케어"),
    "STRYKER CORP": ("SYK", "헬스케어"),
    "ILLUMINA INC": ("ILMN", "헬스케어"),
    "MCKESSON CORP": ("MCK", "헬스케어"),

    # ── 금융 ────────────────────────────────────────────────────────────
    "BERKSHIRE HATHAWAY INC DEL": ("BRK-B", "금융"),
    "BERKSHIRE HATHAWAY INC": ("BRK-B", "금융"),
    "JPMORGAN CHASE & CO": ("JPM", "금융"),
    "BANK AMER CORP": ("BAC", "금융"),
    "BANK OF AMERICA CORP": ("BAC", "금융"),
    "WELLS FARGO & CO NEW": ("WFC", "금융"),
    "WELLS FARGO & CO": ("WFC", "금융"),
    "GOLDMAN SACHS GROUP INC": ("GS", "금융"),
    "MORGAN STANLEY": ("MS", "금융"),
    "CITIGROUP INC": ("C", "금융"),
    "AMERICAN EXPRESS CO": ("AXP", "금융"),
    "VISA INC": ("V", "금융"),
    "MASTERCARD INC": ("MA", "금융"),
    "BLACKROCK INC": ("BLK", "금융"),
    "SCHWAB CHARLES CORP": ("SCHW", "금융"),
    "S&P GLOBAL INC": ("SPGI", "금융"),
    "PROGRESSIVE CORP": ("PGR", "금융"),
    "CHUBB LTD": ("CB", "금융"),
    "MARSH & MCLENNAN COS INC": ("MRSH", "금융"),   # 2026 회사명·티커 변경(Marsh McLennan). MMC는 야후에서 폐지
    "PAYPAL HLDGS INC": ("PYPL", "금융"),

    # ── 산업재 ──────────────────────────────────────────────────────────
    "CATERPILLAR INC": ("CAT", "산업재"),
    "DEERE & CO": ("DE", "산업재"),
    "HONEYWELL INTL INC": ("HON", "산업재"),
    "UNION PAC CORP": ("UNP", "산업재"),
    "BOEING CO": ("BA", "산업재"),
    "RTX CORP": ("RTX", "산업재"),
    "RAYTHEON TECHNOLOGIES CORP": ("RTX", "산업재"),
    "LOCKHEED MARTIN CORP": ("LMT", "산업재"),
    "GENERAL ELECTRIC CO": ("GE", "산업재"),
    "UNITED PARCEL SERVICE INC": ("UPS", "산업재"),
    "3M CO": ("MMM", "산업재"),
    "EATON CORP PLC": ("ETN", "산업재"),
    "ILLINOIS TOOL WKS INC": ("ITW", "산업재"),
    "EMERSON ELEC CO": ("EMR", "산업재"),

    # ── 에너지 ──────────────────────────────────────────────────────────
    "EXXON MOBIL CORP": ("XOM", "에너지"),
    "CHEVRON CORP NEW": ("CVX", "에너지"),
    "CHEVRON CORP": ("CVX", "에너지"),
    "CONOCOPHILLIPS": ("COP", "에너지"),
    "EOG RES INC": ("EOG", "에너지"),
    "SCHLUMBERGER LTD": ("SLB", "에너지"),
    "MARATHON PETE CORP": ("MPC", "에너지"),
    "PHILLIPS 66": ("PSX", "에너지"),
    "OCCIDENTAL PETE CORP": ("OXY", "에너지"),
    "VALERO ENERGY CORP NEW": ("VLO", "에너지"),

    # ── 소재 ────────────────────────────────────────────────────────────
    "LINDE PLC": ("LIN", "소재"),
    "SHERWIN WILLIAMS CO": ("SHW", "소재"),
    "AIR PRODS & CHEMS INC": ("APD", "소재"),
    "FREEPORT-MCMORAN INC": ("FCX", "소재"),
    "NEWMONT CORP": ("NEM", "소재"),
    "DOW INC": ("DOW", "소재"),

    # ── 유틸리티 ────────────────────────────────────────────────────────
    "NEXTERA ENERGY INC": ("NEE", "유틸리티"),
    "SOUTHERN CO": ("SO", "유틸리티"),
    "DUKE ENERGY CORP NEW": ("DUK", "유틸리티"),
    "CONSTELLATION ENERGY CORP": ("CEG", "유틸리티"),
    "AMERICAN ELEC PWR CO INC": ("AEP", "유틸리티"),

    # ── 부동산 ──────────────────────────────────────────────────────────
    "PROLOGIS INC": ("PLD", "부동산"),
    "AMERICAN TOWER CORP": ("AMT", "부동산"),
    "EQUINIX INC": ("EQIX", "부동산"),
    "CROWN CASTLE INC": ("CCI", "부동산"),
    "SIMON PPTY GROUP INC NEW": ("SPG", "부동산"),

    # ── ETF (기관이 자주 담는 것만) ─────────────────────────────────────
    "SPDR S&P 500 ETF TR": ("SPY", "ETF·펀드"),
    "INVESCO QQQ TR": ("QQQ", "ETF·펀드"),
}

# 위험 분석의 기준이 되는 벤치마크. 매핑 종목과 함께 받아 둡니다.
BENCHMARKS: dict[str, str] = {
    "SPY": "S&P 500 (SPY)",
    "QQQ": "나스닥 100 (QQQ)",
    "ACWI": "글로벌 주식 (ACWI)",
}

PRICE_HISTORY_PERIOD = "5y"

# 이름 끝에 붙는 법인격·클래스 표기. 매칭할 때만 떼어 냅니다.
_SUFFIXES = {
    "INC", "CORP", "CORPORATION", "CO", "COMPANY", "PLC", "LTD", "LIMITED",
    "LLC", "LP", "NEW", "DEL", "THE", "CL", "A", "B", "C", "COM", "SA", "NV",
    "HLDGS", "HOLDINGS", "GROUP", "TR", "ETF",
}


def normalize(name: str) -> str:
    """대문자·영숫자만 남기고 공백을 하나로. '&'는 남깁니다(JOHNSON & JOHNSON)."""
    if not name:
        return ""
    cleaned = re.sub(r"[^A-Z0-9&\s-]", " ", name.upper())
    return re.sub(r"\s+", " ", cleaned).strip()


def core(name: str) -> str:
    """법인격·클래스 표기를 떼어 낸 핵심 이름. 매칭 2차 시도에 씁니다."""
    tokens = [t for t in normalize(name).split(" ") if t and t not in _SUFFIXES]
    return " ".join(tokens)


# 매칭용 색인. 모듈을 불러올 때 한 번만 만듭니다.
_BY_NORMALIZED = {normalize(k): v for k, v in EQUITY_MAP.items()}
_BY_CORE: dict[str, tuple[str, str]] = {}
for _key, _value in EQUITY_MAP.items():
    # 핵심 이름이 겹치면(클래스가 다른 같은 회사) 먼저 들어온 것을 남깁니다.
    _BY_CORE.setdefault(core(_key), _value)


def lookup(name: str) -> tuple[str, str] | None:
    """
    13F 종목명 → (티커, 섹터). 모르면 None.

    <b>추측하지 않습니다.</b> 이름이 비슷하다고 아무 티커나 붙이면 엉뚱한 회사의
    가격으로 위험을 계산하게 됩니다. 두 단계(정확히 일치 → 법인격 제거 후 일치)
    모두 실패하면 "모른다"로 둡니다.
    """
    if not name:
        return None
    hit = _BY_NORMALIZED.get(normalize(name))
    if hit:
        return hit
    return _BY_CORE.get(core(name))


def all_tickers() -> tuple[str, ...]:
    """매핑된 종목 + 벤치마크 전체 (중복 제거, 정렬)."""
    tickers = {ticker for ticker, _ in EQUITY_MAP.values()}
    tickers.update(BENCHMARKS.keys())
    return tuple(sorted(tickers))


def mapping_payload() -> dict:
    """
    저장본에 함께 싣는 매핑표.

    백엔드가 이 표를 따로 들고 있으면 두 곳이 조용히 어긋납니다(한쪽만 종목을
    추가하는 순간). 가격과 같은 저장본에 담아 **단일 출처**로 둡니다.
    """
    return {
        name: {"ticker": ticker, "sector": sector}
        for name, (ticker, sector) in EQUITY_MAP.items()
    }
