"""
tests/test_verify_readings.py
교차 검증용 읽기(GET /verify/readings)의 응답 모양을 고정합니다.

[왜 필요한가]
같은 수치를 여러 출처(KRX·KIS·토스·yfinance·Daum)에서 읽어 원자료 그대로 백엔드에 넘기고,
판정은 백엔드가 합니다. 백엔드는 이 필드 이름과 ok·value·asOf·detail 모양에 기대므로,
읽기 코드를 main.py에서 옮기더라도 모양이 바뀌면 안 됩니다. 외부 호출은 모두 가짜로 바꿉니다.
"""
from __future__ import annotations

from datetime import datetime

import pytest

from app import main, settings
from app.services import kis, krx, market, radar, toss


@pytest.fixture()
def fakes(monkeypatch):
    """외부를 부르지 않는 가짜 출처. 테스트마다 필요한 것만 바꿔 씁니다."""
    monkeypatch.setattr(main, "API_TOKEN", "")
    monkeypatch.setattr(settings, "krx_key", lambda: "krx-key")
    monkeypatch.setattr(kis, "has_credentials", lambda: True)
    monkeypatch.setattr(toss, "has_credentials", lambda: False)
    monkeypatch.setattr(kis, "fetch_kospi200_futures", lambda: {"ok": True, "value": 350.25})
    monkeypatch.setattr(kis, "fetch_index_close",
                        lambda code=kis.INDEX_CODE_KOSPI200: {"ok": True, "value": {"2001": 351.0, "0001": 2600.5}[code]})
    monkeypatch.setattr(toss, "verification_reading", lambda fetch, label: {"label": label, "result": fetch()})
    monkeypatch.setattr(toss, "fetch_index_daily_close", lambda symbol="KOSPI": f"{symbol}-close")
    monkeypatch.setattr(toss, "fetch_usdkrw_mid", lambda: "usdkrw-mid")
    monkeypatch.setattr(radar, "is_regular_session", lambda now=None: True)
    calls: list[str] = []

    def krx_close(date_str):
        calls.append(date_str)
        return "345.67" if len(calls) == 2 else None      # 오늘은 없고 하루 전 확정치가 있음
    monkeypatch.setattr(krx, "fetch_kospi200_index_close", krx_close)

    def ticker(symbol, period="1mo"):
        assert period == "5d"
        return {"points": [{"date": "2026-09-24", "close": 340.0},
                           {"date": "2026-09-25T00:00:00", "close": 341.5},
                           {"date": "2026-09-26", "close": None}]}
    monkeypatch.setattr(market, "collect_ticker", ticker)

    def kis_ranking(date_str, market_, investor, trade_type, n):
        return [{"name": "삼성전자", "code": "005930", "netAmountEok": 1234.5}]
    monkeypatch.setattr(kis, "fetch_deal_ranking", kis_ranking)

    def daum_ranking(date_str, market_, investor, trade_type, n, mode):
        assert mode == "TODAY"
        return []
    monkeypatch.setattr(radar, "fetch_daum_ranking", daum_ranking)
    return calls


def _read(**kwargs):
    params = {"market": "KOSPI", "investor": "외국인", "tradeType": "순매수", "x_service_token": None}
    params.update(kwargs)
    return main.verification_readings(**params)


def test_모든_출처를_원자료_그대로_담는다(fakes):
    out = _read()

    assert list(out) == ["checkedAt", "keys", "kisFutures", "kisIndex", "krxIndex", "yfinanceIndex",
                         "rankingTop", "tossKospi", "kisKospi", "yfinanceKospi", "tossUsdKrw"]
    datetime.fromisoformat(out["checkedAt"])            # ISO 시각
    assert out["keys"] == {"krx": True, "kis": True, "toss": False}
    assert out["kisFutures"] == {"ok": True, "value": 350.25}
    assert out["kisIndex"] == {"ok": True, "value": 351.0}          # 기본값 KOSPI200
    assert out["kisKospi"] == {"ok": True, "value": 2600.5}         # 코스피 종합
    assert out["tossKospi"] == {"label": "코스피 일봉", "result": "KOSPI-close"}
    assert out["tossUsdKrw"] == {"label": "환율", "result": "usdkrw-mid"}


def test_KRX_확정치는_최근_영업일을_거슬러_찾고_기준일을_asOf로_준다(fakes):
    out = _read()["krxIndex"]

    assert len(fakes) == 2                               # 오늘 → 하루 전에서 찾음
    asked = datetime.strptime(fakes[1], "%Y%m%d").date().isoformat()
    assert out == {"ok": True, "value": 345.67, "asOf": asked, "detail": f"기준일 {asked}"}


def test_KRX_키가_없거나_7일_안에_없으면_실패로_적는다(fakes, monkeypatch):
    monkeypatch.setattr(settings, "krx_key", lambda: "")
    assert _read()["krxIndex"] == {"ok": False, "value": None, "detail": "KRX api_key가 없습니다."}
    assert fakes == []                                   # 키가 없으면 부르지도 않음

    monkeypatch.setattr(settings, "krx_key", lambda: "krx-key")
    monkeypatch.setattr(krx, "fetch_kospi200_index_close", lambda d: fakes.append(d))
    assert _read()["krxIndex"] == {"ok": False, "value": None, "detail": "최근 7일 안에 확정 지수가 없습니다."}
    assert len(fakes) == 7


def test_yfinance는_종가가_있는_마지막_점과_기준일을_준다(fakes, monkeypatch):
    out = _read()
    assert out["yfinanceIndex"] == {"ok": True, "value": 341.5, "asOf": "2026-09-25",
                                    "detail": "^KS200 (참고, 기준일 2026-09-25)"}
    assert out["yfinanceKospi"]["detail"] == "^KS11 (참고, 기준일 2026-09-25)"

    monkeypatch.setattr(market, "collect_ticker", lambda s, p="1mo": {"points": [{"close": 0}], "error": "HTTP 429"})
    assert _read()["yfinanceIndex"] == {"ok": False, "value": None, "detail": "^KS200 조회 실패 — HTTP 429"}

    monkeypatch.setattr(market, "collect_ticker", lambda s, p="1mo": {"points": []})
    assert _read()["yfinanceIndex"]["detail"] == "^KS200 조회 실패"

    monkeypatch.setattr(market, "collect_ticker", lambda s, p="1mo": {"points": [{"close": 10.0}]})
    assert _read()["yfinanceIndex"] == {"ok": True, "value": 10.0, "asOf": None, "detail": "^KS200 (참고)"}


def test_수급_1위는_출처별로_읽고_실패_사유를_남긴다(fakes, monkeypatch):
    top = _read()["rankingTop"]
    assert top == {
        "isRegularSession": True,
        "kis": {"ok": True, "source": "KIS 장중 가집계", "name": "삼성전자", "code": "005930", "value": 1234.5},
        "daum": {"ok": False, "source": "Daum (화면이 쓰는 값)", "detail": "빈 결과"},
    }

    def boom(*args):
        raise RuntimeError("x" * 300)
    monkeypatch.setattr(kis, "fetch_deal_ranking", boom)
    kis_top = _read()["rankingTop"]["kis"]
    assert kis_top == {"ok": False, "source": "KIS 장중 가집계", "detail": "x" * 200}   # 200자로 자름


def test_토큰을_켜면_검사한다(fakes, monkeypatch):
    monkeypatch.setattr(main, "API_TOKEN", "secret")
    with pytest.raises(main.HTTPException) as caught:
        _read(x_service_token="wrong")
    assert caught.value.status_code == 401
