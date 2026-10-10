"""
tests/test_failure_reasons.py
"수집은 성공, 데이터는 0건"일 때 **왜**가 남는지 고정합니다.

이 테스트가 있는 이유: 운영 중에 `make status`가 "0/12 시리즈"라고만 말해
무엇을 고쳐야 할지 알 수 없었던 일이 실제로 있었습니다. 사유 문자열은
로그가 아니라 **화면에 노출되는 값**이므로 계약으로 고정합니다.
"""
from __future__ import annotations

from pathlib import Path

import pytest

from app import http, settings
from app.services import fred, sector
from app.tasks import _reason_suffix


# ==============================================================================
# 사유 문자열 조립
# ==============================================================================
def test_사유가_없으면_빈_문자열():
    """성공했을 때 "(사유: )" 같은 빈 껍데기가 붙으면 안 됩니다."""
    assert _reason_suffix([]) == ""
    assert _reason_suffix([None, None]) == ""


def test_중복_사유는_한_번만():
    """12개 시리즈가 같은 이유로 실패하면 같은 문장이 12번 붙습니다."""
    suffix = _reason_suffix(["CSV HTTP 403", "CSV HTTP 403", "CSV HTTP 403"])
    assert suffix.count("CSV HTTP 403") == 1


def test_사유는_최대_두_개까지만():
    """detail은 화면 한 줄입니다. 전부 붙이면 읽을 수 없게 됩니다."""
    suffix = _reason_suffix(["A", "B", "C", "D"])
    assert "A" in suffix and "B" in suffix
    assert "C" not in suffix and "D" not in suffix


# ==============================================================================
# FRED — 403은 "키를 넣으면 우회된다"까지 말해야 조치로 이어집니다
# ==============================================================================
class _Res:
    def __init__(self, status_code: int, text: str = ""):
        self.status_code = status_code
        self.text = text

    def json(self):
        raise ValueError("not json")


def test_FRED_CSV_403_사유에_키_안내가_포함된다(monkeypatch):
    monkeypatch.setattr(settings, "fred_key", lambda: "")

    class _Session:
        def get(self, *a, **kw):
            return _Res(403, "Forbidden")

    monkeypatch.setattr(fred, "get_fred_session", lambda: _Session())

    points, reason = fred.collect_series_with_reason("DGS10")
    assert points == []
    assert "403" in reason
    assert "FRED_API_KEY" in reason, "무엇을 하면 풀리는지가 사유에 있어야 합니다"


def test_FRED_실패해도_가짜값을_만들지_않는다(monkeypatch):
    monkeypatch.setattr(settings, "fred_key", lambda: "")

    class _Session:
        def get(self, *a, **kw):
            raise ConnectionError("차단")

    monkeypatch.setattr(fred, "get_fred_session", lambda: _Session())
    points, reason = fred.collect_series_with_reason("DGS10")
    assert points == []          # ← 이 줄이 이 프로젝트의 핵심 규칙입니다
    assert reason


# ==============================================================================
# 섹터 ETF — yfinance는 예외 없이 빈 프레임을 주는 경우가 있습니다
# ==============================================================================
def test_yfinance가_조용히_비면_버전을_사유에_남긴다(monkeypatch):
    """
    예외가 없으니 로그에도 아무것도 안 남습니다. 이럴 때 라이브러리 버전이
    사유에 있어야 "낡은 pin이 원인"이라는 판단이 가능합니다.
    """
    import pandas as pd

    monkeypatch.setattr(sector.yf, "download", lambda *a, **kw: pd.DataFrame())
    monkeypatch.setattr(sector, "_fetch_single", lambda symbol, period: None)

    result = sector.collect_etf_history(("XLK", "XLF"))
    assert result["tickers"] == {}
    assert result["error"]
    assert "yfinance" in result["error"]


def test_티커가_없으면_요청_자체를_사유로(monkeypatch):
    result = sector.collect_etf_history(())
    assert result["tickers"] == {}
    assert "티커" in result["error"]


# ==============================================================================
# SEC User-Agent — 연락처가 없으면 EDGAR가 403을 줍니다
# ==============================================================================
@pytest.fixture(autouse=True)
def _clear_secret_cache():
    settings._load_secrets_file.cache_clear()
    yield
    settings._load_secrets_file.cache_clear()


def test_이메일만_넣으면_SEC_형식으로_감싼다(monkeypatch):
    monkeypatch.setenv("SEC_USER_AGENT", "me@example.com")
    ua = http.sec_user_agent()
    assert "me@example.com" in ua
    assert ua.startswith("LocalMacroDashboard/"), "SEC는 '이름 연락처' 형태를 요구합니다"


def test_완전한_UA를_넣으면_그대로_쓴다(monkeypatch):
    monkeypatch.setenv("SEC_USER_AGENT", "MyResearch/1.0 (contact: me@example.com)")
    assert http.sec_user_agent() == "MyResearch/1.0 (contact: me@example.com)"


def test_미설정이면_조용히_넘어가지_않고_설정을_요구한다(monkeypatch):
    """
    예시 이메일로 폴백하면 안 됩니다. SEC 입장에서는 정체를 숨긴 요청이고,
    403이 났을 때 "내 설정이 비었다"를 알아챌 방법이 사라집니다.
    """
    monkeypatch.delenv("SEC_USER_AGENT", raising=False)
    monkeypatch.delenv("sec.user_agent", raising=False)

    with pytest.raises(http.SecUserAgentMissing) as exc:
        http.sec_user_agent()

    message = str(exc.value)
    assert "SEC_USER_AGENT" in message
    assert ".env" in message, "어디를 고쳐야 하는지가 메시지에 있어야 합니다"


def test_코드에_예시_이메일이_남아있지_않다():
    """하드코딩된 연락처가 다시 들어오는 것을 막습니다."""
    source = Path(http.__file__).read_text(encoding="utf-8")
    assert "research@example.com" not in source


def test_구버전_secrets_키_경로도_읽는다(monkeypatch, tmp_path):
    """구버전 .streamlit/secrets.toml의 [sec] user_agent 와 같은 키 경로."""
    secrets = tmp_path / "secrets.toml"
    secrets.write_text('[sec]\nuser_agent = "old@example.com"\n', encoding="utf-8")
    monkeypatch.delenv("SEC_USER_AGENT", raising=False)
    monkeypatch.setattr(settings, "_SECRETS_PATH", secrets)
    settings._load_secrets_file.cache_clear()
    assert settings.sec_user_agent() == "old@example.com"


# ==============================================================================
# 예외 문자열 압축 — 사유는 로그가 아니라 화면 한 줄입니다
# ==============================================================================
def test_프록시_오류는_조치_문장으로_바뀐다():
    import requests

    exc = requests.exceptions.ProxyError(
        "HTTPSConnectionPool(host='fred.stlouisfed.org', port=443): Max retries "
        "exceeded with url: /graph/fredgraph.csv?id=DGS2 (Caused by ProxyError("
        "'Unable to connect to proxy', OSError('Tunnel connection failed: 403')))"
    )
    brief = http.brief_error(exc)
    assert "프록시" in brief
    assert "Max retries exceeded" not in brief, "urllib3 스택을 그대로 노출하면 안 됩니다"


def test_긴_메시지를_잘라도_괄호가_열린_채로_끝나지_않는다():
    """
    실제로 겪은 문제입니다. 120자에서 자르니 "…id=DGS2 (" 로 끝나
    뒤 내용이 잘린 것인지 원래 그런 것인지 알 수 없었습니다.
    """
    exc = RuntimeError("정상 문구 " + "가" * 80 + " (여기부터 잘릴 괄호 " + "나" * 80)
    brief = http.brief_error(exc)
    assert brief.count("(") == brief.count(")"), brief


def test_짧은_메시지는_그대로_둔다():
    assert http.brief_error(ValueError("키 없음")) == "ValueError: 키 없음"


# ==============================================================================
# 사유가 아예 없던 두 작업 (fed_liquidity · krx_futures)
# ==============================================================================
def test_순유동성_실패시_어느_시계열이_왜_비었는지_남는다(monkeypatch):
    from app.services import liquidity

    monkeypatch.setattr(
        liquidity.fred, "collect_series_with_reason",
        lambda sid, years: ([], "CSV HTTP 403"),
    )
    payload = liquidity.collect_fed_liquidity(1)
    assert payload["rows"] == []
    assert "403" in payload["error"]
    assert "WALCL" in payload["error"] or "RRPONTSYD" in payload["error"]


def test_KRX_추정치도_실패하면_사유를_남긴다(monkeypatch):
    from app.services import krx

    class _Ticker:
        def __init__(self, symbol):
            pass

        def history(self, period):
            raise ConnectionError("connection refused")

    monkeypatch.setattr(krx.yf, "Ticker", _Ticker)
    payload = krx._fallback_from_kodex(40)
    assert payload["rows"] == []
    assert payload["error"]
    assert "거부" in payload["error"] or "refused" in payload["error"].lower()


def test_한글_이메일은_요청_전에_막는다(monkeypatch):
    """
    .env에 한글이 들어가면 requests가 헤더를 latin-1로 인코딩하다가
    UnicodeEncodeError를 냅니다. 그 메시지만 보고 .env를 고쳐야 한다는 걸
    알아채기는 어렵습니다. 요청을 보내기 전에 무엇을 고칠지 말해야 합니다.
    """
    monkeypatch.setenv("SEC_USER_AGENT", "이메일@이메일.com")

    with pytest.raises(http.SecUserAgentInvalid) as exc:
        http.sec_user_agent()

    message = str(exc.value)
    assert "SEC_USER_AGENT" in message
    assert ".env" in message


def test_예시_값을_그대로_두면_막는다(monkeypatch):
    monkeypatch.setenv("SEC_USER_AGENT", "your-name@example.com")

    with pytest.raises(http.SecUserAgentInvalid) as exc:
        http.sec_user_agent()

    assert "예시" in str(exc.value)


def test_이메일이_아니면_막는다(monkeypatch):
    monkeypatch.setenv("SEC_USER_AGENT", "hong")

    with pytest.raises(http.SecUserAgentInvalid):
        http.sec_user_agent()


def test_통과한_값은_헤더로_인코딩된다(monkeypatch):
    """검증을 통과했다면 requests가 실제로 보낼 수 있어야 합니다."""
    monkeypatch.setenv("SEC_USER_AGENT", "hong@example.com")

    ua = http.sec_user_agent()
    ua.encode("latin-1")          # 여기서 터지면 검증이 헛돈 것입니다
    assert "hong@example.com" in ua


def test_미설정_오류도_같은_계열로_잡힌다(monkeypatch):
    """호출부가 예외를 하나만 잡아도 되도록 상속 관계를 고정합니다."""
    monkeypatch.delenv("SEC_USER_AGENT", raising=False)

    assert issubclass(http.SecUserAgentMissing, http.SecUserAgentInvalid)
    with pytest.raises(http.SecUserAgentInvalid):
        http.sec_user_agent()

