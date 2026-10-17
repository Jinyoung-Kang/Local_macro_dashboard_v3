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
