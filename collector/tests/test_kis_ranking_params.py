"""
tests/test_kis_ranking_params.py
KIS 장중 가집계(FHPTJ04400000) 요청 파라미터를 공식 명세에 고정합니다(QA-074).

공식 예제(koreainvestment/open-trading-api, examples_llm/domestic_stock/foreign_institution_total)의 명세:
  FID_INPUT_ISCD         0000 전체 · 0001 코스피 · 1001 코스닥
  FID_DIV_CLS_CODE       0 수량정렬 · 1 금액정렬
  FID_RANK_SORT_CLS_CODE 0 순매수상위 · 1 순매도상위
  FID_ETC_CLS_CODE       0 전체 · 1 외국인 · 2 기관계 · 3 기타
예전에는 KOSPI에 0000(전체 시장)을 보내 KOSDAQ 종목이 KOSPI 레이더에 섞였고, 수량순·전체 투자자로 받았으며,
기관에는 명세에 없는 정렬 코드 2·3을 보냈습니다. 응답 행에는 시장 필드가 없어 받은 뒤에는 거를 수 없습니다.
네트워크를 쓰지 않습니다(call_api를 흉내 냅니다). 실제 응답 확인은 사용자 승인 하의 1회 호출로 미룹니다.
"""
from __future__ import annotations

import pytest

from app.services import kis, radar


@pytest.fixture()
def calls(monkeypatch):
    recorded: list[dict] = []

    def fake_call_api(**kw):
        recorded.append(kw["params"])
        return {"rt_cd": "0", "output": [{
            "stck_shrn_iscd": "005930", "hts_kor_isnm": "삼성전자", "stck_prpr": "70000",
            "frgn_ntby_tr_pbmn": "30000", "orgn_ntby_tr_pbmn": "10000",
            "ivtr_ntby_tr_pbmn": "5000",
        }]}

    monkeypatch.setattr(kis, "call_api", fake_call_api)
    return recorded


@pytest.mark.parametrize("market, investor, trade, iscd, rank_sort, etc_cls", [
    ("KOSPI", "외국인", "순매수", "0001", "0", "1"),
    ("KOSPI", "기관", "순매수", "0001", "0", "2"),
    ("KOSPI", "외국인", "순매도", "0001", "1", "1"),
    ("KOSDAQ", "기관", "순매도", "1001", "1", "2"),
])
def test_QA074_가집계_요청이_공식_명세의_시장_금액정렬_투자자_코드를_쓴다(
    calls, market, investor, trade, iscd, rank_sort, etc_cls,
):
    kis.fetch_deal_ranking("20261019", market, investor, trade, 30)

    assert calls, "KIS를 부르지 않았습니다"
    assert calls[0] == {
        "FID_COND_MRKT_DIV_CODE": "V",
        "FID_COND_SCR_DIV_CODE": "16449",
        "FID_INPUT_ISCD": iscd,
        "FID_DIV_CLS_CODE": "1",        # 금액정렬 — 화면은 순매수 금액(억) 순위
        "FID_RANK_SORT_CLS_CODE": rank_sort,
        "FID_ETC_CLS_CODE": etc_cls,
    }


def test_QA074_투자자_구분_코드가_없는_투자주체는_부르지_않고_사유를_남긴다(calls):
    """명세의 투자자 구분은 외국인(1)·기관계(2)뿐입니다. 투신 등은 '전체 투자자' 순위를 받아 투신 금액으로 다시 거르게 됩니다."""
    assert kis.fetch_deal_ranking("20261019", "KOSPI", "투신", "순매수", 30) == []
    assert calls == []
    assert any("KIS" in reason and "투신" in reason for reason in radar.diagnose_sources("투신"))
