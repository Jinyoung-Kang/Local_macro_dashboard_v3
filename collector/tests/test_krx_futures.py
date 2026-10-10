"""
tests/test_krx_futures.py
KRX 선물 파생 계산 회귀 테스트.

2026-09-11에 실제로 났던 사고를 고정합니다: KRX 응답에 FLUC_RT가 없으면
파서가 0.0으로 메웠고, 화면이 매일 "+0.00%"를 보여줬으며, 국면 판정이
`등락률 >= 0`을 쓰기 때문에 **하락한 날에도 '신규 롱'(강세)** 으로 뒤집혔습니다.
"""
from __future__ import annotations

import pytest

from app.services import krx


def _rows(*closes, open_interest=None):
    rows = []
    for index, close in enumerate(closes):
        rows.append({
            "date": f"2026-09-{index + 1:02d}",
            "futuresClose": close,
            "changePct": None,
            "changePctReported": None,
            "volume": 100.0,
            "openInterest": (open_interest[index] if open_interest else 300000.0),
            "oiChange": None,
            "theoryPrice": None,
            "marketBasis": None,
            "contractName": "KOSPI 200 F 202609",
            "marketPhase": krx.PHASE_UNKNOWN,
            "cotOiIndex": None,
        })
    return rows


def test_change_pct_is_computed_from_closes():
    rows = _rows(1112.00, 1088.30)
    krx._derive_series(rows)

    # 1112.00 → 1088.30 = -2.13%
    assert rows[1]["changePct"] == pytest.approx(-2.1312, abs=1e-3)


def test_falling_day_is_never_labelled_long_accumulation():
    rows = _rows(1112.00, 1088.30, open_interest=[300000.0, 310000.0])
    krx._derive_series(rows)

    assert rows[1]["marketPhase"] == "신규 숏 (Short Accumulation)"


def test_missing_change_pct_yields_unknown_phase():
    assert krx.diagnose_phase(None, 1000.0) == krx.PHASE_UNKNOWN
    assert krx.diagnose_phase(1.2, None) == krx.PHASE_UNKNOWN


def test_phase_matrix():
    assert krx.diagnose_phase(1.0, 100.0) == "신규 롱 (Long Accumulation)"
    assert krx.diagnose_phase(1.0, -100.0) == "숏 커버링 (Short Covering)"
    assert krx.diagnose_phase(-1.0, 100.0) == "신규 숏 (Short Accumulation)"
    assert krx.diagnose_phase(-1.0, -100.0) == "롱 청산 (Long Liquidation)"


def test_first_row_uses_reported_value_when_available():
    rows = _rows(1100.0)
    rows[0]["changePctReported"] = 0.55
    krx._derive_series(rows)

    assert rows[0]["changePct"] == pytest.approx(0.55)


def test_reported_value_is_kept_for_cross_checking():
    rows = _rows(1000.0, 1010.0)
    rows[1]["changePctReported"] = 1.0
    krx._derive_series(rows)

    assert rows[1]["changePct"] == pytest.approx(1.0)
    assert rows[1]["changePctReported"] == pytest.approx(1.0)


def test_cot_oi_index_is_percentile_within_window():
    rows = _rows(1000.0, 1005.0, 1010.0, open_interest=[100.0, 200.0, 300.0])
    krx._derive_series(rows)

    assert rows[0]["cotOiIndex"] == pytest.approx(0.0)
    assert rows[2]["cotOiIndex"] == pytest.approx(100.0)


def test_parse_day_returns_none_when_change_field_absent():
    """FLUC_RT가 없으면 None으로 둡니다. 0.0으로 메우지 않습니다."""
    parsed = krx._parse_futures_day("20260911", [
        {"ISU_NM": "코스피200 F 202609", "TDD_CLSPRC": "1,088.30",
         "ACC_TRDVOL": "120,000", "ACC_OPNINT_QTY": "310,000"},
    ])

    assert parsed["reportedPct"] is None
    assert parsed["close"] == pytest.approx(1088.30)


def test_parse_day_picks_most_traded_contract_and_skips_other_products():
    rows = [
        {"ISU_NM": "미니 코스피200 F 202609", "TDD_CLSPRC": "1,088.00",
         "ACC_TRDVOL": "900,000"},
        {"ISU_NM": "코스피200 F 202612", "TDD_CLSPRC": "1,090.00",
         "ACC_TRDVOL": "1,000"},
        {"ISU_NM": "코스피200 F 202609", "TDD_CLSPRC": "1,088.30",
         "ACC_TRDVOL": "500,000"},
    ]
    parsed = krx._parse_futures_day("20260911", rows)

    assert parsed["close"] == pytest.approx(1088.30)   # 미니는 제외
    assert "미니" not in parsed["contractName"]


def test_fallback_marks_estimate_and_refuses_to_invent_open_interest(monkeypatch):
    """
    KODEX 200 기반 폴백은 **가격 추정치**입니다. 미결제약정·베이시스는
    만들어내지 않습니다(구버전은 sin/linspace로 합성했습니다).
    """
    import pandas as pd

    class FakeTicker:
        def __init__(self, symbol):
            self.symbol = symbol

        def history(self, period):
            index = pd.date_range("2026-09-01", periods=10, freq="B")
            return pd.DataFrame({"Close": [36000.0 + i * 10 for i in range(10)]}, index=index)

    monkeypatch.setattr(krx.yf, "Ticker", FakeTicker)

    payload = krx._fallback_from_kodex(5)

    assert payload["isEstimated"] is True
    assert payload["rows"], "추정치 행이 있어야 합니다"
    for row in payload["rows"]:
        assert row["openInterest"] is None
        assert row["marketBasis"] is None
        assert row["marketPhase"] == krx.PHASE_UNKNOWN
        assert "추정" in row["contractName"]


def test_daum_trend_is_contract_based_only():
    """
    Daum은 금액(억원)을 제공하지 않습니다. 계약수 기준만 다루며 단위를
    명시합니다(예전 '금액' 모드는 값을 1억으로 나눠 전부 0이 됐습니다).
    """
    payload = krx._empty_trend()
    assert payload["measure"] == "CONTRACT"
    assert payload["unit"] == "계약"


def test_missing_volume_or_open_interest_is_unknown_not_zero():
    """
    KRX가 필드 이름을 바꾸거나 빈 값을 주면 0.0으로 메워졌고, 그 0.0이 40일 이력 위에
    upsert돼 미결제약정 이력이 0으로 덮였습니다. 0.0 - 직전 OI = 음수 → "롱 청산"처럼
    국면까지 지어냈습니다. 모르면 None이고 국면은 '판정 불가'입니다.
    """
    parsed = krx._parse_futures_day("20260911", [
        {"ISU_NM": "코스피200 F 202609", "TDD_CLSPRC": "1,088.30", "ACC_TRDVOL": ""},
    ])
    assert parsed["volume"] is None
    assert parsed["openInterest"] is None

    rows = _rows(1100.0, 1088.3, open_interest=[300000.0, None])
    krx._derive_series(rows)
    assert rows[1]["oiChange"] is None
    assert rows[1]["marketPhase"] == krx.PHASE_UNKNOWN
    assert rows[1]["cotOiIndex"] is None


@pytest.mark.xfail(strict=True, reason="QA-035: 만기 롤오버 날 서로 다른 계약끼리 OI 변화를 계산")
def test_QA035_근월물이_바뀐_날은_미결제약정_변화를_모른다():
    """
    근월물은 그날 거래량 1위로 고릅니다. 만기 무렵 거래가 다음 월물로 옮겨 가면 9월물 OI와 12월물 OI의 차를 '변화'로
    계산해 수십만 계약이 청산된 것처럼 보이고, 국면이 거짓으로 판정됐습니다. 등락률은 KRX가 그 계약 기준으로 보고한 값을 씁니다.
    """
    rows = _rows(1112.00, 1120.00, open_interest=[300000.0, 150000.0])
    rows[1]["contractName"] = "KOSPI 200 F 202612"
    rows[1]["changePctReported"] = 0.35

    krx._derive_series(rows)

    assert rows[1]["oiChange"] is None
    assert rows[1]["changePct"] == pytest.approx(0.35)
    assert rows[1]["marketPhase"] == krx.PHASE_UNKNOWN


@pytest.mark.xfail(strict=True, reason="QA-035: KRX 일시 실패 때 확정 저장본을 추정치로 덮음")
def test_QA035_KRX가_잠깐_실패해도_하루_안의_확정치를_추정치로_덮지_않는다(store, monkeypatch):
    from app import catalog, tasks

    confirmed = {"isEstimated": False, "rows": _rows(1112.0, 1120.0)}
    store.put_snapshot(catalog.SNAP_KRX_FUTURES, confirmed, status="ok")
    monkeypatch.setattr(tasks.krx_service, "collect_futures_history",
                        lambda days: {"isEstimated": True, "rows": _rows(1100.0)})

    with pytest.raises(tasks.EmptyResult):
        tasks.task_krx_futures()

    assert store.read_snapshot(catalog.SNAP_KRX_FUTURES).payload["isEstimated"] is False
