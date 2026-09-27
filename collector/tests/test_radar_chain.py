"""
tests/test_radar_chain.py
수급 레이더 폴백 체인 회귀 테스트.

체인: KIS(장중) → Daum → Naver → LS → 토스(공식) → PyKrx → 누적 이력
외부 네트워크를 쓰지 않습니다(각 단계를 대체합니다).
"""
from __future__ import annotations

from datetime import date, datetime
from zoneinfo import ZoneInfo

import pytest

from app.services import radar

KST = ZoneInfo("Asia/Seoul")


@pytest.fixture(autouse=True)
def silence_sources(monkeypatch):
    """기본적으로 모든 외부 단계를 '빈 결과'로 만들어 둡니다."""
    monkeypatch.setattr(radar.kis, "fetch_deal_ranking", lambda *a, **k: [])
    monkeypatch.setattr(radar, "fetch_daum_ranking", lambda *a, **k: [])
    monkeypatch.setattr(radar, "fetch_naver_ranking", lambda *a, **k: [])
    monkeypatch.setattr(radar.ls, "fetch_deal_ranking", lambda *a, **k: [])
    monkeypatch.setattr(radar, "fetch_toss_ranking", lambda *a, **k: [])
    monkeypatch.setattr(radar, "fetch_pykrx_ranking", lambda *a, **k: [])
    monkeypatch.setattr(radar, "read_from_history", lambda *a, **k: None)


def _rows(source: str):
    return [{
        "rank": 1, "code": "005930", "name": "삼성전자",
        "price": 71000.0, "changePct": 1.2, "netAmountEok": 1200.5,
        "source": source, "collectedAt": "2026-09-11 10:00:00 KST",
    }]


def test_kis_wins_during_regular_session(monkeypatch):
    monkeypatch.setattr(radar, "is_regular_session", lambda now=None: True)
    monkeypatch.setattr(
        radar.kis, "fetch_deal_ranking", lambda *a, **k: _rows("KIS 장중 가집계")
    )
    called = {"daum": False}

    def daum(*args, **kwargs):
        called["daum"] = True
        return _rows("Daum")

    monkeypatch.setattr(radar, "fetch_daum_ranking", daum)

    result = radar.collect_radar_ranking(datetime.now(KST).date())

    assert result["sourceKind"] == "kis"
    assert called["daum"] is False, "앞 단계가 성공하면 뒤는 호출되지 않아야 합니다"


def test_kis_is_skipped_outside_regular_session(monkeypatch):
    monkeypatch.setattr(radar, "is_regular_session", lambda now=None: False)
    called = {"kis": False}

    def kis_call(*args, **kwargs):
        called["kis"] = True
        return _rows("KIS")

    monkeypatch.setattr(radar.kis, "fetch_deal_ranking", kis_call)
    monkeypatch.setattr(radar, "fetch_daum_ranking", lambda *a, **k: _rows("Daum API"))

    result = radar.collect_radar_ranking(datetime.now(KST).date())

    assert called["kis"] is False, "가집계 TR은 장중 전용입니다"
    assert result["sourceKind"] == "daum"


def test_ls_runs_only_after_kis_daum_naver_failed(monkeypatch):
    monkeypatch.setattr(radar, "is_regular_session", lambda now=None: True)
    monkeypatch.setattr(
        radar.ls, "fetch_deal_ranking", lambda *a, **k: _rows("LS 증권사 API")
    )

    result = radar.collect_radar_ranking(datetime.now(KST).date())

    assert result["sourceKind"] == "ls"


def test_history_fallback_is_marked_and_dated(monkeypatch):
    """
    외부 소스가 전멸하면 수집기가 쌓아 둔 이력으로 대체하되, 화면이
    "지금 시점의 수급이 아니다"라고 경고할 수 있도록 표시해야 합니다.
    """
    monkeypatch.setattr(
        radar, "read_from_history",
        lambda *a, **k: {
            "source": "누적 이력 (수집기가 2026-09-10에 저장한 값 · 외부 소스 전부 실패)",
            "sourceKind": "history",
            "isHistorical": True,
            "historyDate": "2026-09-10",
            "rows": _rows("누적 이력"),
        },
    )

    result = radar.collect_radar_ranking(date(2026, 9, 11))

    assert result["isHistorical"] is True
    assert result["historyDate"] == "2026-09-10"
    assert "누적 이력" in result["source"]


def test_total_failure_returns_empty_not_fabricated():
    result = radar.collect_radar_ranking(date(2026, 9, 11))

    assert result["rows"] == []
    assert result["sourceKind"] == "none"


def test_past_date_skips_current_only_sources(monkeypatch):
    """
    Naver/Daum/KIS는 과거 날짜 조회를 지원하지 않습니다. 과거 조회에서
    이들을 부르면 '오늘 값'을 과거 날짜 데이터로 착각하게 됩니다.
    """
    monkeypatch.setattr(radar, "is_regular_session", lambda now=None: True)
    called = {"daum": 0, "naver": 0, "pykrx": 0}

    def daum(*args, **kwargs):
        called["daum"] += 1
        return []

    def naver(*args, **kwargs):
        called["naver"] += 1
        return []

    def pykrx(*args, **kwargs):
        called["pykrx"] += 1
        return _rows("PyKrx")

    monkeypatch.setattr(radar, "fetch_daum_ranking", daum)
    monkeypatch.setattr(radar, "fetch_naver_ranking", naver)
    monkeypatch.setattr(radar, "fetch_pykrx_ranking", pykrx)

    result = radar.collect_radar_ranking(date(2020, 1, 6))    # 과거 평일

    assert called["daum"] == 0
    assert called["naver"] == 0
    assert called["pykrx"] >= 1
    assert result["sourceKind"] == "pykrx"


def test_latest_completed_session_handles_weekend():
    saturday = datetime(2026, 9, 12, 8, 0, tzinfo=KST)   # 토요일
    assert radar.latest_completed_session(saturday) == "20260911"

    weekday_morning = datetime(2026, 9, 11, 7, 0, tzinfo=KST)  # 금요일 장 시작 전
    assert radar.latest_completed_session(weekday_morning) == "20260910"

    weekday_open = datetime(2026, 9, 11, 10, 0, tzinfo=KST)
    assert radar.latest_completed_session(weekday_open) == "20260911"


def test_regular_session_boundaries():
    assert radar.is_regular_session(datetime(2026, 9, 11, 9, 0, tzinfo=KST)) is True
    assert radar.is_regular_session(datetime(2026, 9, 11, 15, 29, tzinfo=KST)) is True
    assert radar.is_regular_session(datetime(2026, 9, 11, 15, 30, tzinfo=KST)) is False
    assert radar.is_regular_session(datetime(2026, 9, 12, 10, 0, tzinfo=KST)) is False


def test_rank_orders_by_amount_and_renumbers():
    records = [
        {"code": "A", "netAmountEok": 10.0},
        {"code": "B", "netAmountEok": 50.0},
        {"code": "C", "netAmountEok": 30.0},
    ]
    ranked = radar._rank(list(records), "순매수", 3)
    assert [r["code"] for r in ranked] == ["B", "C", "A"]
    assert [r["rank"] for r in ranked] == [1, 2, 3]

    sells = [
        {"code": "A", "netAmountEok": -10.0},
        {"code": "B", "netAmountEok": -50.0},
    ]
    ranked_sells = radar._rank(list(sells), "순매도", 2)
    assert [r["code"] for r in ranked_sells] == ["B", "A"]


# ==============================================================================
# 실패 사유 안내
# ==============================================================================
def test_소스가_모두_없는_조합은_이유를_돌려준다():
    """
    화면에 "수집기 상태를 확인하세요"만 뜨면, 수집기가 멀쩡한 경우에도
    그쪽을 보게 됩니다. 실제 원인은 대개 '이 소스는 이 투자주체를 원래
    안 준다'입니다.
    """
    reasons = radar.diagnose_sources("개인")

    assert any("Daum" in r and "개인" in r for r in reasons)
    assert any("LS" in r for r in reasons)
    assert any("pykrx" in r.lower() or "KRX" in r for r in reasons)


def test_조사가_받침에_맞게_붙는다():
    """'개인'를 → '개인'은 …처럼 읽히는 문장을 막습니다."""
    assert radar._object_particle("개인") == "을"      # 받침 ㄴ
    assert radar._object_particle("금융투자") == "를"   # 받침 없음
    assert radar._object_particle("연기금") == "을"     # 받침 ㅁ
    assert radar._object_particle("") == "를"


def test_toss_runs_after_ls_and_before_pykrx(monkeypatch):
    monkeypatch.setattr(radar, "is_regular_session", lambda now=None: False)
    order: list[str] = []

    def ls_call(*args, **kwargs):
        order.append("ls")
        return []

    def toss_call(*args, **kwargs):
        order.append("toss")
        return _rows("토스증권 공식 — 거래대금 상위 100종목 중 순위")

    def pykrx_call(*args, **kwargs):
        order.append("pykrx")
        return _rows("PyKrx")

    monkeypatch.setattr(radar.ls, "fetch_deal_ranking", ls_call)
    monkeypatch.setattr(radar, "fetch_toss_ranking", toss_call)
    monkeypatch.setattr(radar, "fetch_pykrx_ranking", pykrx_call)

    result = radar.collect_radar_ranking(datetime.now(KST).date(), investor="연기금")

    assert result["sourceKind"] == "toss"
    assert order == ["ls", "toss"], "토스가 성공하면 PyKrx는 부르지 않습니다"
