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

# 국면 판정 성과(이후 수익률)에 쓰는 국내 자산. 위험 분석 벤치마크와 목적이 달라 따로 둡니다.
# KODEX 200은 원화 종가(수정주가)입니다 — 성과는 비율이라 통화 단위와 무관합니다.
TRACK_RECORD_TICKERS: dict[str, str] = {
    "069500.KS": "KOSPI 200 (KODEX 200)",
}

PRICE_HISTORY_PERIOD = "5y"

# 이름 끝에 붙는 법인격·클래스 표기. 매칭할 때만 떼어 냅니다.
_SUFFIXES = {
    "INC", "CORP", "CORPORATION", "CO", "COMPANY", "PLC", "LTD", "LIMITED",
    "LLC", "LP", "NEW", "DEL", "THE", "CL", "A", "B", "C", "COM", "SA", "NV",
    "HLDGS", "HOLDINGS", "GROUP", "TR", "ETF",
}


# 펀드·ETF·신탁을 뜻하는 토큰(이름·클래스). 2단계(법인격 제거) 매칭에서 이런 보유가 운영회사에 붙지 않게 합니다.
# 'ETF'·'TR'은 위 접미사에도 있어, 'GOLDMAN SACHS ETF TR'(운용사의 ETF 신탁)의 핵심 이름이 'GOLDMAN SACHS'가 되어
# 골드만삭스 주식(GS)에 붙었습니다(QA-087). 매핑표에 ETF로 적어 둔 이름(SPY·QQQ)은 정확히 일치로 찾습니다.
_FUND_TOKENS = {
    "ETF", "ETFS", "ETN", "ETNS", "TR", "TRUST", "FUND", "FUNDS", "FD", "FDS",
    "PORTFOLIO", "INDEX", "UNIT", "UNITS", "BEN",
}
# 채무증권(노트·전환사채·채권)을 뜻하는 클래스(titleOfClass) 토큰. 회사 이름이 같아도 주가로 덮지 않습니다.
_DEBT_TOKENS = {"NOTE", "NOTES", "DEBT", "DEB", "DBCV", "SDCV", "BOND", "BONDS"}
# 보통주가 아닌 지분형 증권(우선주·의무전환 우선주·워런트·권리·지분 유닛)은 같은 회사 보통주 가격으로 대신하지 않습니다 —
# 영구 우선주는 채권처럼, 워런트는 레버리지로 움직여 보통주 가격과 다릅니다(QA-127). 아래 규칙은 2026-10-12 백업의 모든
# 클래스 표기에 맞춰 봤습니다.
# 1) 어디에 있든 보통주가 아닌 토큰. 'RT'는 금리(RATE) 약자로도 쓰여('FLT RT') 넣지 않습니다.
_NON_COMMON_TOKENS = {
    "PFD", "PREF", "PREFERRED", "PRF", "PERP", "MAND", "CUM",
    "WT", "WTS", "WARRANT", "WARRANTS", "RIGHT", "RIGHTS",
}
# 2) 워런트 표기 '*W EXP 08/03/202'·'W EXP …'. 'COM W/I'(발행일 거래 보통주)의 W와 이름 표기('BERKLEY W R CORP')는 아닙니다.
_WARRANT_CLASS = re.compile(r"(^|\s)(\*W|W EXP)\b")
# 3) 지분 유닛(주식 매수 계약 + 사채, 'UNIT 02/15/2029')은 만기일이 있습니다. 날짜가 없거나 만기 없음 자리표시
#    '99/99/9999'인 'UNIT'은 그 회사의 보통 지분입니다 — 이중 상장·묶음 보통주(Carnival·Granite 'UNIT 99/99/9999'), 조합
#    단위('COM UNIT'·'DEPOSITARY UNIT'), 신탁 단위('TR UNITS'·'UNIT BEN INT'), 단위 ADR('SPON ADR UNITS').
_EQUITY_UNIT_CLASS = re.compile(r"\bUNITS?\s*(?!99/99/)\d{1,2}/\d{1,2}/\d{2,4}")
# 4) 전환 표기와 예탁주 약자. 의무전환 우선주('7.625 MAND CONV')·우선주 예탁주('7.25% DEP SHS A'·'DEP SHS REPSTG')를 뜻하지만,
#    예탁증서(ADR) 표기나 보통주 클래스(CL)와 함께면 보통주입니다 — 'ADR ECH CNV IN 3'(혼다 ADR 1주 = 보통주 3주),
#    'AMERICAN DEP'(ADR), 'CL B CONV'(전환권이 붙은 B주).
_CONVERSION_TOKENS = {"CONV", "CNV", "CV", "DEP"}
_RECEIPT_OR_CLASS_TOKENS = {"ADR", "ADRS", "ADS", "AMERICAN", "AMERN", "AMER", "GDR", "GDS", "CL", "CLASS"}
# 5) 'CON'은 'CON NEW'(COM 오타)·'STATE STREET CON'(소비재 ETF)으로도 쓰여, 시리즈나 표면 금리와 함께일 때만
#    전환 우선주로 봅니다('6.25 CON SER D'). 표면 금리는 '6.25'·'7.25%'이고 액면가('$0.01'·'USD0.001')는 아닙니다.
_SERIES_TOKENS = {"SER", "SERIES"}
_COUPON = re.compile(r"(?<![\w$.])\d+(\.\d+|\s*%)")
FUND_SECTOR = "ETF·펀드"

# 수집기가 일부러 티커를 붙이지 않은 보유의 종류(저장본 ``tickerNote``). 백엔드 미커버 목록이 이유로 옮겨 적습니다.
NOTE_DEBT = "debt"
NOTE_NON_COMMON = "non_common"


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


def _tokens(text: str | None) -> set[str]:
    return set(normalize(text or "").split(" ")) - {""}


def lookup(name: str, title_class: str = "") -> tuple[str, str] | None:
    """
    13F 종목명 → (티커, 섹터). 모르면 None.

    <b>추측하지 않습니다.</b> 이름이 비슷하다고 아무 티커나 붙이면 엉뚱한 회사의
    가격으로 위험을 계산하게 됩니다. 두 단계(정확히 일치 → 법인격 제거 후 일치)
    모두 실패하면 "모른다"로 둡니다. 이름이나 클래스가 펀드·ETF·신탁을 뜻하면 2단계로
    운영회사에 붙이지 않습니다('GOLDMAN SACHS ETF TR' ≠ GS).
    """
    if not name:
        return None
    hit = _BY_NORMALIZED.get(normalize(name))
    if hit:
        return hit
    hit = _BY_CORE.get(core(name))
    if hit and hit[1] != FUND_SECTOR and (_tokens(name) | _tokens(title_class)) & _FUND_TOKENS:
        return None
    return hit


def lookup_holding(holding: dict) -> tuple[str, str] | None:
    """13F 보유 한 줄(sec13f 저장 형태) → (티커, 섹터). 모르면 None. 규칙은 classify_holding입니다."""
    return classify_holding(holding)[0]


def classify_holding(holding: dict) -> tuple[tuple[str, str] | None, str | None]:
    """
    13F 보유 한 줄 → ((티커, 섹터) 또는 None, 일부러 뺀 종류 또는 None).

    lookup()에 클래스(titleOfClass)를 함께 넘기고, 회사 이름이 같아도 주가와 다른 증권은 처음부터 덮지 않습니다.
      - 채권(NOTE_DEBT): 원금(PRN, 저장본의 shares가 None)이거나 클래스에 _DEBT_TOKENS가 있거나 CUSIP 발행 번호가 지분
        증권이 아닌(_is_equity_cusip) 행. 마지막 검사는 사채를 클래스 'COM'·주식 수로 공시한 행(피셔의 FORD MTR CO
        345370CZ1) 때문입니다 — 클래스·PRN만 보면 포드 주가로 계산됐습니다(QA-123).
      - 보통주가 아닌 증권(NOTE_NON_COMMON): 우선주(의무전환 우선주 '… MAND CONV'·예탁주 'DEP SHS' 포함)·워런트·권리·지분 유닛
        (_is_non_common_class). 예전에는 같은 회사 보통주 티커에 붙여 보통주 가격으로 근사했는데, 근사라는 표시도 없었습니다
        (QA-127). 펀드 이름('SPDR S&P 500 ETF TR' 'TR UNIT')은 ETF 그대로 찾습니다.
    종류는 회사 이름이 매핑표(펀드 제외)에 있는데 뺀 행에만 붙입니다 — 이름을 모르는 행은 어차피 '매핑표에 없음'이라,
    매핑표에 회사를 더해도 덮이지 않는다는 것을 미커버 이유로 구분해 보여 주려는 것입니다.
    """
    name = holding.get("name") or ""
    cls = holding.get("class") or ""
    company = lookup(name)   # 클래스 없이 — 이름이 매핑표의 회사(펀드 제외)를 가리키는지
    known = company is not None and company[1] != FUND_SECTOR
    if _is_debt(holding):
        return None, NOTE_DEBT if known else None
    if known and _is_non_common_class(cls):
        return None, NOTE_NON_COMMON
    return lookup(name, cls), None


def _is_debt(holding: dict) -> bool:
    """채권 원금(PRN)·채무증권 클래스·지분 증권이 아닌 CUSIP 중 하나면 True."""
    if "shares" in holding and holding["shares"] is None:
        return True
    if _tokens(holding.get("class")) & _DEBT_TOKENS:
        return True
    return not _is_equity_cusip(holding.get("cusip"))


def _is_non_common_class(cls: str) -> bool:
    """
    클래스 표기가 우선주·워런트·권리·지분 유닛이면 True(규칙 1~5는 위 토큰 정의의 주석). 숫자에 붙은 표기('7.25%CNV')도
    보도록 영숫자 밖에서 끊습니다. 전환·예탁 표기(4·5)는 ADR·보통주 클래스 표기가 함께 있으면 보지 않습니다.
    """
    text = cls.upper()
    words = set(re.split(r"[^A-Z0-9]+", text)) - {""}
    if words & _NON_COMMON_TOKENS or _WARRANT_CLASS.search(text) or _EQUITY_UNIT_CLASS.search(text):
        return True
    if words & _RECEIPT_OR_CLASS_TOKENS:
        return False
    if words & _CONVERSION_TOKENS:
        return True
    return "CON" in words and bool(words & _SERIES_TOKENS or _COUPON.search(text))


def _is_equity_cusip(cusip: object) -> bool:
    """
    CUSIP 7~8번째 글자(발행 번호)가 숫자면 지분 증권, 영문자가 섞이면 채권 등입니다 — 백엔드 13F 집계(Sec13FService,
    QA-113)와 같은 규칙입니다. CUSIP을 모르거나 8자보다 짧으면 판단하지 않고 True(예전처럼 이름으로 찾음)입니다.
    iShares iBonds처럼 발행 번호에 영문자를 쓰는 ETF도 있지만, 펀드라 어차피 운영회사 티커에 붙이지 않습니다.
    """
    text = str(cusip or "").strip()
    if len(text) < 8:
        return True
    issue = text[6:8]
    return issue.isascii() and issue.isdigit()


def attach_tickers(quarters: list[dict]) -> int:
    """
    13F 분기 목록의 보유마다 ``ticker``(모르면 None)를 붙입니다. 붙인(찾은) 수를 돌려줍니다.

    백엔드 위험 분석은 이 값을 먼저 씁니다. 예전에는 백엔드가 이름을 매핑표 키와 정확히 비교만 해서
    'NVIDIA CORPORATION'처럼 표기가 다른 최대 보유 종목이 '매핑표에 없음'으로 빠졌습니다(QA-087).
    매칭 규칙을 수집기 한 곳(이 모듈)에만 두려고 저장할 때 붙입니다. None도 적어 두는 이유: 백엔드가 '수집기가
    모른다고 정한 것'(펀드·채권·우선주 등)과 '티커를 붙이기 전 옛 저장본'을 구분해, 옛 저장본만 이름으로 다시 찾습니다.
    회사 이름은 매핑표에 있는데 일부러 뺀 보유에는 ``tickerNote``(NOTE_DEBT·NOTE_NON_COMMON)를 적어, 백엔드 미커버 목록이
    '매핑표에 없음' 대신 그 이유를 보이게 합니다(QA-127). 이어받은 분기도 다시 붙이므로, 표시가 없어진 보유에서는 지웁니다.
    """
    found = 0
    for quarter in quarters:
        for holding in quarter.get("holdings") or []:
            if not isinstance(holding, dict):
                continue
            hit, note = classify_holding(holding)
            holding["ticker"] = hit[0] if hit else None
            if note:
                holding["tickerNote"] = note
            else:
                holding.pop("tickerNote", None)
            found += hit is not None
    return found


def all_tickers() -> tuple[str, ...]:
    """매핑된 종목 + 벤치마크 + 판정 성과용 국내 자산 (중복 제거, 정렬)."""
    tickers = {ticker for ticker, _ in EQUITY_MAP.values()}
    tickers.update(BENCHMARKS.keys())
    tickers.update(TRACK_RECORD_TICKERS.keys())
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
