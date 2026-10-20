"""
tests/test_sec13f_merge.py
그룹이 13F-NT(신고 위임)로 바꾸고 자회사들이 보유를 나눠 신고하는 기관(뱅가드)을 합산하는 규칙(QA-140).

2026-10-20 EDGAR 확인: Vanguard Group(0000102909)은 2026-03-31 분기부터 13F-NT만 내고, 보유는 Vanguard Capital
Management(0002100119, $4.68조)와 Vanguard Portfolio Management(0002100121, $2.22조)가 따로 신고합니다. 합계 $6.90조가
그룹의 2025-12-31 공시와 같습니다. 수집기는 그룹 CIK만 받아 화면의 뱅가드가 2025-12-31에 멈춰 있었습니다.

외부 네트워크를 쓰지 않습니다(collect_13f를 대체합니다). PostgreSQL이 필요합니다(store 픽스처).
"""
from __future__ import annotations

import pytest

from app import catalog, indicators, tasks

GROUP, CAPITAL, PORTFOLIO = "0000102909", "0002100119", "0002100121"


def _holding(cusip: str, name: str, value: float, shares: float | None = 100.0, cls: str = "COM") -> dict:
    return {"name": name, "cusip": cusip, "class": cls, "value": value, "shares": shares, "weight": 0.0}


def _quarter(report: str, filed: str, *holdings: dict) -> dict:
    total = sum(h["value"] for h in holdings)
    return {"filingDate": filed, "reportDate": report, "totalValue": total,
            "holdings": [{**h, "weight": h["value"] / total * 100} for h in holdings]}


GROUP_Q4 = _quarter("2025-12-31", "2026-01-29",
                    _holding("67066G104", "NVIDIA CORPORATION", 600.0), _holding("037833100", "APPLE INC", 400.0))


def _vanguard_only(monkeypatch, responses: dict):
    """기관 목록을 카탈로그의 뱅가드 하나로 줄이고, CIK마다 정해 둔 응답을 돌려줍니다."""
    monkeypatch.setenv("SEC_USER_AGENT", "test@example.com")
    vanguard = next(inst for inst in indicators.INSTITUTIONS if inst["key"] == "vanguard")
    monkeypatch.setattr(tasks.indicators, "INSTITUTIONS", [vanguard], raising=False)
    asked: list[str] = []

    def fake_collect(cik, quarters, previous_quarters=None):
        asked.append(cik)
        return responses.get(cik) or {"cik": cik, "error": "응답 없음", "quarters": None}

    monkeypatch.setattr(tasks.sec_service, "collect_13f", fake_collect)
    return asked


def _ok(cik: str, *quarters: dict) -> dict:
    return {"cik": cik, "error": None, "quarters": list(quarters)}


def test_QA140_그룹_이후_분기는_두_자회사_보유를_증권별로_합친다(store, monkeypatch):
    asked = _vanguard_only(monkeypatch, {
        GROUP: _ok(GROUP, GROUP_Q4),
        CAPITAL: _ok(CAPITAL, _quarter("2026-06-30", "2026-08-13",
                                       _holding("67066G104", "NVIDIA CORP", 500.0, 50.0),
                                       _holding("037833100", "APPLE INC", 300.0))),
        PORTFOLIO: _ok(PORTFOLIO, _quarter("2026-06-30", "2026-08-12",
                                           _holding("67066G104", "NVIDIA CORPORATION", 200.0, 20.0),
                                           _holding("594918104", "MICROSOFT CORP", 100.0))),
    })

    tasks.task_sec_13f()

    assert sorted(asked) == [GROUP, CAPITAL, PORTFOLIO]
    quarters = store.read_snapshot(catalog.snap_sec_13f(GROUP, 8)).payload["quarters"]
    assert [q["reportDate"] for q in quarters] == ["2026-06-30", "2025-12-31"]
    latest = quarters[0]
    by_cusip = {h["cusip"]: h for h in latest["holdings"]}
    assert by_cusip["67066G104"]["value"] == pytest.approx(700.0)        # 두 자회사 합
    assert by_cusip["67066G104"]["shares"] == pytest.approx(70.0)
    assert by_cusip["67066G104"]["weight"] == pytest.approx(700.0 / 1100.0 * 100)
    assert latest["totalValue"] == pytest.approx(1100.0)
    assert [h["cusip"] for h in latest["holdings"]] == ["67066G104", "037833100", "594918104"]   # 평가액 큰 순
    assert {f["cik"] for f in latest["filers"]} == {CAPITAL, PORTFOLIO}
    assert latest["filingDate"] == "2026-08-13"
    # q1(최신 한 분기)도 합친 분기입니다.
    assert store.read_snapshot(catalog.snap_sec_13f(GROUP, 1)).payload["quarters"][0]["reportDate"] == "2026-06-30"


def test_QA140_한_자회사에만_있는_분기는_합치지_않는다(store, monkeypatch):
    # 2026-09-30 분기를 Capital만 냈습니다 — 그 분기를 합치면 뱅가드 전체가 아니라 일부입니다.
    _vanguard_only(monkeypatch, {
        GROUP: _ok(GROUP, GROUP_Q4),
        CAPITAL: _ok(CAPITAL,
                     _quarter("2026-09-30", "2026-11-12", _holding("037833100", "APPLE INC", 300.0)),
                     _quarter("2026-06-30", "2026-08-13", _holding("037833100", "APPLE INC", 300.0))),
        PORTFOLIO: _ok(PORTFOLIO, _quarter("2026-06-30", "2026-08-12", _holding("037833100", "APPLE INC", 100.0))),
    })

    tasks.task_sec_13f()

    quarters = store.read_snapshot(catalog.snap_sec_13f(GROUP, 8)).payload["quarters"]
    assert [q["reportDate"] for q in quarters] == ["2026-06-30", "2025-12-31"]


def test_QA140_자회사_수집이_실패하면_직전_저장본을_유지한다(store, monkeypatch):
    kept = {"cik": GROUP, "error": None, "quarters": [
        _quarter("2026-06-30", "2026-08-13", _holding("037833100", "APPLE INC", 400.0)), GROUP_Q4]}
    store.put_snapshot(catalog.snap_sec_13f(GROUP, 8), kept)
    _vanguard_only(monkeypatch, {
        GROUP: _ok(GROUP, GROUP_Q4),
        CAPITAL: _ok(CAPITAL, _quarter("2026-06-30", "2026-08-13", _holding("037833100", "APPLE INC", 300.0))),
        # Portfolio는 응답 없음
    })

    with pytest.raises(tasks.EmptyResult):
        tasks.task_sec_13f()

    quarters = store.read_snapshot(catalog.snap_sec_13f(GROUP, 8)).payload["quarters"]
    assert quarters[0]["reportDate"] == "2026-06-30", "그룹 공시만으로 덮어 2025-12-31로 되돌아가면 안 됩니다"
