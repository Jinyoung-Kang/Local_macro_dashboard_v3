"""
tests/test_equities.py
13F 종목명 → 티커 매핑의 규칙.

고정하는 규칙
  1. 모르는 이름은 **추측하지 않는다** (None). 비슷하다고 아무 티커나 붙이면
     엉뚱한 회사의 가격으로 위험을 계산하게 됩니다.
  2. 법인격 표기가 달라도 같은 회사로 찾는다 (BERKSHIRE HATHAWAY INC DEL).
  3. 섹터 이름은 섹터 로테이션 화면과 **같은 한국어**를 쓴다.
  4. 전부 실패하면 기존 저장본을 덮어쓰지 않는다.
"""
from __future__ import annotations

import pytest

from app import catalog, equities, indicators, tasks


def test_unknown_names_are_not_guessed():
    # 이 셋은 매핑표에 없습니다. 비슷한 이름으로 끌어다 붙이면 안 됩니다.
    assert equities.lookup("어떤 한국 회사") is None
    assert equities.lookup("SOME OBSCURE HOLDINGS LLC") is None
    assert equities.lookup("") is None
    assert equities.lookup(None) is None


def test_corporate_suffix_variants_resolve_to_the_same_company():
    # 기관마다 표기가 갈립니다. 같은 회사로 찾아야 합니다.
    assert equities.lookup("BERKSHIRE HATHAWAY INC DEL") == ("BRK-B", "금융")
    assert equities.lookup("BERKSHIRE HATHAWAY INC") == ("BRK-B", "금융")
    assert equities.lookup("berkshire hathaway") == ("BRK-B", "금융")
    # 앰퍼샌드가 든 이름도 깨지지 않아야 합니다.
    assert equities.lookup("JOHNSON & JOHNSON") == ("JNJ", "헬스케어")
    assert equities.lookup("LILLY ELI & CO") == ("LLY", "헬스케어")


def test_sectors_match_the_rotation_screen():
    """
    섹터 이름이 갈리면 같은 대시보드 안에서 "정보기술"과 "IT"가 따로 놉니다.
    """
    rotation = set(indicators.ROTATION_SECTORS.keys())
    used = {sector for _, sector in equities.EQUITY_MAP.values()}
    # ETF·펀드는 섹터가 아니라 자산 유형이라 로테이션 목록에 없습니다.
    assert used - {"ETF·펀드"} <= rotation


def test_every_mapped_ticker_is_collected():
    """매핑만 해 두고 가격을 안 받으면 화면에서 조용히 빠집니다."""
    collected = set(equities.all_tickers())
    mapped = {ticker for ticker, _ in equities.EQUITY_MAP.values()}
    assert mapped <= collected
    # 벤치마크도 함께 받아야 베타·추적오차를 낼 수 있습니다.
    assert set(equities.BENCHMARKS) <= collected
    # 국면 판정 성과의 국내 자산(KOSPI 200 ETF)도 같은 저장본에 받습니다.
    assert "069500.KS" in collected


def test_equity_task_stores_prices_with_the_mapping(store, monkeypatch):
    monkeypatch.setattr(
        tasks.sector_service, "collect_daily_closes",
        lambda tickers, period="5y": {
            "tickers": {
                "AAPL": {"dates": ["2026-09-10", "2026-09-11"], "close": [220.0, 225.0]},
                "SPY": {"dates": ["2026-09-10", "2026-09-11"], "close": [600.0, 604.0]},
            },
            "error": None,
        },
    )

    detail = tasks.task_equity_history()

    assert "2/" in detail
    payload = store.read_snapshot(catalog.SNAP_EQUITY_HISTORY).payload

    assert payload["tickers"]["AAPL"]["close"][-1] == pytest.approx(225.0)
    # 매핑표가 가격과 같은 저장본에 실려야 백엔드가 따로 들고 있지 않습니다.
    assert payload["nameMap"]["APPLE INC"] == {"ticker": "AAPL", "sector": "정보기술"}
    assert "SPY" in payload["benchmarks"]


def test_equity_task_keeps_previous_snapshot_when_everything_fails(store, monkeypatch):
    store.put_snapshot(catalog.SNAP_EQUITY_HISTORY, {"tickers": {"AAPL": {}}})

    monkeypatch.setattr(
        tasks.sector_service, "collect_daily_closes",
        lambda tickers, period="5y": {"tickers": {}, "error": "Yahoo 차단"},
    )

    with pytest.raises(tasks.EmptyResult) as caught:
        tasks.task_equity_history()

    assert "Yahoo 차단" in str(caught.value)
    assert "AAPL" in store.read_snapshot(catalog.SNAP_EQUITY_HISTORY).payload["tickers"]


# ---- QA-087: 13F 이름 변형(NVIDIA CORPORATION 등)이 위험 분석에서 '매핑표에 없음'으로 빠지던 문제 ---------------
def test_QA087_펀드_ETF_신탁은_법인격을_떼도_운영회사에_붙지_않는다():
    """
    'ETF'·'TR'은 법인격 표기와 함께 떼어 내는 토큰이라, 'GOLDMAN SACHS ETF TR'(골드만삭스 운용 ETF 신탁)이
    핵심 이름 'GOLDMAN SACHS'로 골드만삭스 주식(GS)에 붙었습니다. 펀드의 가격을 운영회사 주가로 계산하게 됩니다.
    """
    assert equities.lookup("GOLDMAN SACHS ETF TR") is None
    assert equities.lookup("MORGAN STANLEY ETF TR") is None
    assert equities.lookup("BLACKROCK ETF TR") is None
    # 매핑표에 그대로 있는 ETF 이름과 운영회사 이름 변형은 그대로 찾습니다.
    assert equities.lookup("SPDR S&P 500 ETF TR") == ("SPY", "ETF·펀드")
    assert equities.lookup("GOLDMAN SACHS GROUP INC") == ("GS", "금융")
    assert equities.lookup("NVIDIA CORPORATION") == ("NVDA", "정보기술")
    assert equities.lookup("NVIDIA CORP COM") == ("NVDA", "정보기술")


def _holding(name, cls="COM", shares=10.0, weight=10.0):
    return {"name": name, "class": cls, "cusip": "000000000", "value": weight, "shares": shares, "weight": weight}


def test_QA087_13F를_저장할_때_보유마다_두_단계_매칭으로_티커를_붙인다(store, monkeypatch):
    """
    백엔드 위험 분석은 이름을 매핑표 키와 정확히 비교만 해, 'NVIDIA CORPORATION'(국민연금 등 최대 보유)이 '매핑표에 없음'으로
    빠졌습니다. 문서의 두 단계 매칭(equities.lookup)은 테스트에서만 불렸습니다. 수집기가 13F를 저장할 때 보유마다 티커를
    붙이고(모르면 None), 펀드·ETF·신탁과 채권(PRN·노트) 행은 운영회사 주가로 덮지 않습니다.
    """
    import copy

    holdings = [
        _holding("NVIDIA CORPORATION"),
        _holding("NVIDIA CORP COM"),
        _holding("APPLE INC"),
        _holding("GOLDMAN SACHS ETF TR", cls="ACCESS TREASURY"),
        _holding("MORGAN STANLEY", cls="COM NEW"),
        _holding("FORD MTR CO", cls="NOTE 3/1", shares=None),        # 전환사채(PRN) — 주식 가격으로 계산하지 않음
        _holding("BOOKING HOLDINGS INC", cls="DBCV 0.750% 5/0"),
        _holding("SOME OBSCURE HOLDINGS LLC"),
    ]
    monkeypatch.setattr(tasks.filings.http, "sec_user_agent", lambda: "test-agent")
    monkeypatch.setattr(tasks.filings.sec_service, "collect_13f", lambda cik, quarters, previous_quarters=None: {
        "cik": cik, "error": None,
        "quarters": [{"filingDate": "2026-08-14", "reportDate": "2026-06-30", "totalValue": 80.0,
                      "holdings": copy.deepcopy(holdings)}],
    })

    tasks.task_sec_13f()

    cik = indicators.INSTITUTIONS[0]["cik"]
    for quarters in (catalog.MAX_TRACKED_QUARTERS, 1):
        saved = store.read_snapshot(catalog.snap_sec_13f(cik, quarters)).payload["quarters"][0]["holdings"]
        assert all("ticker" in h for h in saved), "티커를 정했다는 표시(모르면 None)가 모든 보유에 있어야 합니다"
        assert {h["name"]: h["ticker"] for h in saved} == {
            "NVIDIA CORPORATION": "NVDA",
            "NVIDIA CORP COM": "NVDA",
            "APPLE INC": "AAPL",
            "GOLDMAN SACHS ETF TR": None,
            "MORGAN STANLEY": "MS",
            "FORD MTR CO": None,
            "BOOKING HOLDINGS INC": None,
            "SOME OBSCURE HOLDINGS LLC": None,
        }


# ---- QA-123: 클래스를 'COM'·주식 수로 공시한 사채가 보통주 티커에 붙던 문제 ---------------------------------------------
def test_QA123_COM으로_공시된_사채는_CUSIP_발행_번호로_걸러_보통주_티커에_붙지_않는다():
    """
    일부 기관(피셔)은 전환사채를 클래스 'COM'·주식 수(SH)로 공시합니다. 백업(2026-10-12)의 FORD MTR CO 345370CZ1이 그 예로,
    다른 기관은 같은 CUSIP을 'NOTE 3/1'·원금(PRN)으로 공시합니다. 클래스·PRN만 보면 이 행이 포드 주가(F)로 계산됩니다.
    백엔드 13F 집계(QA-113)와 같은 규칙으로 CUSIP 7~8번째 글자(발행 번호)가 숫자가 아니면 지분 증권이 아니라고 봅니다.
    """
    bond = {"name": "FORD MTR CO", "class": "COM", "cusip": "345370CZ1", "value": 19.0, "shares": 19000.0, "weight": 0.0}
    common = {"name": "FORD MTR CO", "class": "COM", "cusip": "345370860", "value": 100.0, "shares": 10.0, "weight": 1.0}

    assert equities.lookup_holding(bond) is None, "사채 행을 포드 주가로 덮으면 안 됩니다"
    assert equities.lookup_holding(common) == ("F", "임의소비재"), "같은 회사의 보통주는 그대로 찾습니다"
    assert equities.lookup_holding({**common, "cusip": None}) == ("F", "임의소비재"), "CUSIP을 모르면 예전처럼 이름으로 찾습니다"



# ---- QA-127: 우선주·워런트·유닛이 같은 회사 보통주 티커로 근사되던 문제 ------------------------------------------------
# 백업(2026-10-12)의 실제 행입니다(블랙록·국민연금 최신 분기). 회사 이름은 매핑표에 있고, 클래스 표기가 보통주가 아님을 말합니다.
_NON_COMMON_ROWS = [
    ("BOEING CO", "DEP CONV PFD A", "097023204"),                     # 예탁 전환 우선주
    ("WELLS FARGO & CO", "PERP PFD CNV A", "949746804"),              # 영구 전환 우선주
    ("ORACLE CORP", "6.5 DEP CUM SR D", "68389X204"),                 # 누적 우선주(예탁)
    ("HEWLETT PACKARD ENTERPRISE C", "7.625 MAND CONV", "42824C208"),  # 의무전환 우선주
    ("NEXTERA ENERGY INC", "UNIT 02/15/2029", "65339F655"),           # 지분 유닛(주식 매수 계약 + 사채)
    ("SOUTHERN CO", "UNIT 12/15/2028", "842587842"),
    ("OCCIDENTAL PETE CORP", "*W EXP 08/03/202", "674599162"),        # 워런트
    ("OCCIDENTAL PETE CORP", "W EXP 08/03/202", "674599162"),         # 같은 워런트의 별표 없는 표기(국민연금)
]


@pytest.mark.parametrize(("name", "cls", "cusip"), _NON_COMMON_ROWS)
def test_QA127_우선주_워런트_유닛은_보통주_티커로_근사하지_않는다(name, cls, cusip):
    """
    보통주가 아닌 증권을 같은 회사 보통주 가격으로 계산하면서 근사라는 표시(isProxy)도 없었습니다. 영구 우선주는 채권처럼,
    워런트는 레버리지로 움직여 보통주 가격과 다릅니다. 채권(QA-123)처럼 티커를 붙이지 않고 미커버로 둡니다.
    """
    holding = {"name": name, "class": cls, "cusip": cusip, "value": 1.0, "shares": 100.0, "weight": 0.01}
    assert equities.lookup(name) is not None, "회사 이름은 매핑표에 있습니다 — 클래스 표기 때문에 빠져야 합니다"
    assert equities.lookup_holding(holding) is None


# 보통주(ADR·클래스·새 표기)와 ETF 신탁 단위는 그대로 찾아야 합니다. 'COM W/I'(발행일 거래 보통주) 표기만 백업에 없는 예시입니다.
_COMMON_ROWS = [
    ("SPDR S&P 500 ETF TR", "TR UNIT", "78462F103", "SPY"),              # ETF 신탁 단위 — 'UNIT'이어도 펀드
    ("INVESCO QQQ TR", "UNIT SER 1", "46090E103", "QQQ"),
    ("TAIWAN SEMICONDUCTOR MFG LTD", "SPONSORED ADS", "874039100", "TSM"),  # 보통주 예탁증서
    ("BERKSHIRE HATHAWAY INC DEL", "CL B NEW", "084670702", "BRK-B"),
    ("CITIGROUP INC", "COM NEW", "172967424", "C"),
    ("ALPHABET INC", "CAP STK CL C", "02079K107", "GOOGL"),
    ("BOEING CO", "COM", "097023105", "BA"),                              # 우선주를 낸 회사의 보통주
    ("NEXTERA ENERGY INC", "COM", "65339F101", "NEE"),
    ("OCCIDENTAL PETE CORP", "COM", "674599105", "OXY"),
    ("DOW INC", "COM W/I", "260557103", "DOW"),                          # 'W/I'의 W는 워런트가 아님
]


@pytest.mark.parametrize(("name", "cls", "cusip", "ticker"), _COMMON_ROWS)
def test_QA127_보통주_ADR_ETF_단위는_그대로_티커를_찾는다(name, cls, cusip, ticker):
    holding = {"name": name, "class": cls, "cusip": cusip, "value": 1.0, "shares": 100.0, "weight": 1.0}
    hit = equities.lookup_holding(holding)
    assert hit is not None and hit[0] == ticker


# 조합(MLP)의 보통 단위와 '단위'로 공시되는 ADR. 지금 매핑표에는 없어 이 테스트에서만 매핑표에 넣습니다(백업의 실제 행).
_COMMON_UNIT_ROWS = [
    ("MPLX LP", "COM UNIT REP LTD", "55336V100", ("MPLX", "에너지")),
    ("KIMBELL RTY PARTNERS LP", "UNIT", "49435R102", ("KRP", "에너지")),
    ("ICAHN ENTERPRISES LP", "DEPOSITARY UNIT", "451100101", ("IEP", "산업재")),
    ("FOMENTO ECONOMICO MEXICANO S", "SPON ADR UNITS", "344419106", ("FMX", "필수소비재")),
]


@pytest.mark.parametrize(("name", "cls", "cusip", "mapped"), _COMMON_UNIT_ROWS)
def test_QA127_조합의_보통_단위와_단위_ADR은_보통주로_본다(monkeypatch, name, cls, cusip, mapped):
    monkeypatch.setitem(equities._BY_NORMALIZED, equities.normalize(name), mapped)
    holding = {"name": name, "class": cls, "cusip": cusip, "value": 1.0, "shares": 100.0, "weight": 1.0}
    assert equities.lookup_holding(holding) == mapped


def test_QA127_저장할_때_일부러_뺀_보유에는_이유를_적는다():
    """
    백엔드 미커버 목록은 티커가 없으면 이유를 가리지 않고 '매핑표에 없음'이라고 적었습니다. 채권과 우선주는 매핑표에 회사가
    있어도 일부러 뺀 것이라, 수집기가 그 종류(``tickerNote``: 'debt'·'non_common')를 적고 백엔드는 옮기기만 합니다.
    이름을 모르는 행(펀드 포함)은 종류 없이 둡니다 — 이유는 그대로 '매핑표에 없음'입니다.
    이전 실행이 적은 표시는 규칙이 바뀌어 티커가 붙으면 지웁니다(이어받은 분기도 다시 붙이기 때문).
    """
    def row(name, cls, cusip, shares=100.0, **extra):
        return {"name": name, "class": cls, "cusip": cusip, "value": 1.0, "shares": shares, "weight": 1.0, **extra}

    quarters = [{"holdings": [
        row("BOEING CO", "COM", "097023105"),
        row("BOEING CO", "DEP CONV PFD A", "097023204"),
        row("FORD MTR CO", "COM", "345370CZ1"),                         # 'COM'으로 공시된 사채(QA-123)
        row("FORD MTR CO", "NOTE 3/1", "345370CZ1", shares=None),        # 원금(PRN)
        row("SOME OBSCURE HOLDINGS LLC", "NOTE 1/1", "000000AA0", shares=None),  # 이름을 모르는 채권 — 종류 없이 '매핑표에 없음'
        row("GOLDMAN SACHS ETF TR", "ACCES TREASURY", "381430529"),      # 펀드 — 매핑표에 없는 것으로 둠
        row("SOME OBSCURE HOLDINGS LLC", "COM", "000000000"),
        row("APPLE INC", "COM", "037833100", tickerNote="debt"),        # 예전 실행이 남긴 표시
    ]}]

    equities.attach_tickers(quarters)

    saved = [(h["name"], h["class"], h["ticker"], h.get("tickerNote")) for h in quarters[0]["holdings"]]
    assert saved == [
        ("BOEING CO", "COM", "BA", None),
        ("BOEING CO", "DEP CONV PFD A", None, "non_common"),
        ("FORD MTR CO", "COM", None, "debt"),
        ("FORD MTR CO", "NOTE 3/1", None, "debt"),
        ("SOME OBSCURE HOLDINGS LLC", "NOTE 1/1", None, None),
        ("GOLDMAN SACHS ETF TR", "ACCES TREASURY", None, None),
        ("SOME OBSCURE HOLDINGS LLC", "COM", None, None),
        ("APPLE INC", "COM", "AAPL", None),
    ]
    assert "tickerNote" not in quarters[0]["holdings"][0], "표시가 없는 보유에는 필드를 두지 않습니다"
