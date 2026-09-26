"""
app/services/toss.py
토스증권 Open API — 연결 진단 + 국내 투자자별 매매동향.

쓰는 곳
  - 🔌 외부 API 연결 테스트 (진단·환율·지수)
  - 📡 수급 레이더 / 🇰🇷 국내 파생: 투자자별 매매 (공식 스펙 1.2.13부터 제공)
      GET /api/v1/market-indicators/{KOSPI|KOSDAQ}/investor-trading  시장 전체 매매**대금**(원)
      GET /api/v1/stocks/{symbol}/investor-trading                    종목별 매매**거래량**(주)

토스 API에 **없는 것** — 선물·옵션·미결제약정. 파생 수치는 계속 KRX·Daum에서 받습니다.
(공식 스펙 1.2.17의 시장 지표 심볼 카탈로그는 KOSPI·KOSDAQ·국채 6종뿐입니다.)

주의사항
  - client 하나에 유효한 토큰은 1개이고 재발급하면 이전 토큰이 즉시 무효가 됩니다
    (공식 스펙). 그래서 토큰은 이 모듈 한 곳에서만 발급·캐시하고, 401을 받으면
    한 번만 재발급해 다시 시도합니다. 같은 키를 다른 프로그램에서도 쓰면 서로의
    토큰을 무효화합니다.
  - 금액·수량은 정밀도 보존을 위해 **문자열 정수**로 옵니다("-291850").
  - 당일 잠정 기록은 개인·기관 세부·기타법인이 null입니다. **null을 0으로 바꾸지
    않습니다** — "개인 순매수 0"은 "모름"과 정반대의 신호입니다.
  - 오류 문구에 client_secret·토큰이 섞이지 않게 합니다(토큰은 헤더로만 보냄).
"""
from __future__ import annotations

import logging
import threading
import time

import requests

from .. import settings

logger = logging.getLogger(__name__)

BASE_URL = "https://openapi.tossinvest.com"
AUTH_URL = f"{BASE_URL}/oauth2/token"

_lock = threading.Lock()
# 발급 자체를 한 번에 하나로 묶습니다. client당 유효 토큰이 1개라, 두 곳이 동시에 발급하면
# 먼저 받은 쪽 토큰이 즉시 무효가 됩니다(연결 테스트와 수집 태스크가 겹칠 때).
_issue_lock = threading.Lock()
_token_cache: tuple[str, float] | None = None


def has_credentials() -> bool:
    client_id, client_secret = settings.toss_credentials()
    return bool(client_id and client_secret)


def get_access_token() -> tuple[str | None, str | None]:
    """(토큰, 오류 메시지). 성공한 토큰만 캐시합니다."""
    with _lock:
        if _token_cache and _token_cache[1] > time.time():
            return _token_cache[0], None

    with _issue_lock:
        # 기다리는 동안 다른 쪽이 이미 발급했으면 그 토큰을 씁니다.
        with _lock:
            if _token_cache and _token_cache[1] > time.time():
                return _token_cache[0], None
        return _issue_token()


def _issue_token() -> tuple[str | None, str | None]:
    global _token_cache
    client_id, client_secret = settings.toss_credentials()
    if not client_id or not client_secret:
        return None, "[toss] client_id / client_secret이 설정되지 않았습니다."

    try:
        res = requests.post(
            AUTH_URL,
            data={
                "grant_type": "client_credentials",
                "client_id": client_id,
                "client_secret": client_secret,
            },
            headers={"Content-Type": "application/x-www-form-urlencoded"},
            timeout=8,
        )
        res.raise_for_status()
        payload = res.json()
    except requests.HTTPError as exc:
        status = exc.response.status_code if exc.response is not None else "?"
        body = exc.response.text[:300] if exc.response is not None else ""
        return None, f"HTTP {status} 오류: {body}"
    except Exception as exc:  # noqa: BLE001
        return None, f"토큰 발급 실패: {exc}"

    token = payload.get("access_token")
    if not token:
        return None, f"토큰 응답에 access_token이 없습니다: {payload}"

    expires_in = int(payload.get("expires_in", 3600))
    with _lock:
        _token_cache = (token, time.time() + max(60, expires_in - 60))
    return token, None


def test_connection() -> dict:
    token, error = get_access_token()
    if not token:
        return {"ok": False, "stage": "token", "message": error}

    try:
        res = requests.get(
            f"{BASE_URL}/api/v1/exchange-rate",
            headers={"Authorization": f"Bearer {token}"},
            params={"baseCurrency": "USD", "quoteCurrency": "KRW"},
            timeout=8,
        )
    except requests.Timeout:
        return {"ok": False, "stage": "network", "message": "요청 시간 초과(8초)"}
    except requests.ConnectionError as exc:
        return {"ok": False, "stage": "network", "message": f"연결 실패: {exc}"}

    if res.status_code == 403:
        return {
            "ok": False, "stage": "forbidden",
            "message": "HTTP 403 — 허용 IP 목록에 현재 IP가 없을 수 있습니다.",
        }
    if res.status_code == 400:
        return {
            "ok": False, "stage": "bad_request",
            "message": f"HTTP 400 — 요청 파라미터 오류: {res.text[:200]}",
        }
    if res.status_code != 200:
        return {
            "ok": False, "stage": "http_error",
            "message": f"HTTP {res.status_code}: {res.text[:200]}",
        }

    try:
        data = res.json()
    except ValueError:
        return {"ok": False, "stage": "bad_response", "message": "JSON 해석 실패"}

    return {
        "ok": True, "stage": "ok",
        "message": "연결 성공",
        "sample": data,
    }


def get_exchange_rate(base: str = "USD", quote: str = "KRW") -> dict:
    token, error = get_access_token()
    if not token:
        return {"ok": False, "error": error}

    try:
        res = requests.get(
            f"{BASE_URL}/api/v1/exchange-rate",
            headers={"Authorization": f"Bearer {token}"},
            params={"baseCurrency": base, "quoteCurrency": quote},
            timeout=8,
        )
        res.raise_for_status()
        return {"ok": True, "data": res.json()}
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "error": str(exc)[:300]}


def get_index_prices(symbols: list[str]) -> dict:
    token, error = get_access_token()
    if not token:
        return {"ok": False, "error": error}

    try:
        res = requests.get(
            f"{BASE_URL}/api/v1/market-indicators/prices",
            headers={"Authorization": f"Bearer {token}"},
            params={"symbols": ",".join(symbols)},
            timeout=8,
        )
        res.raise_for_status()
        return {"ok": True, "data": res.json()}
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "error": str(exc)[:300]}


# ==============================================================================
# 공통 GET — 401 재발급·429 대기
# ==============================================================================
class TossError(RuntimeError):
    """토스 API 호출 실패. 메시지에는 비밀값이 없습니다(상태 화면에 그대로 나갑니다)."""


class TossMissingKey(TossError):
    """TOSS_CLIENT_ID / TOSS_CLIENT_SECRET 미설정."""


class TossForbidden(TossError):
    """403 — 허용 IP 목록 문제일 가능성이 높습니다. 같은 실행에서 더 부르지 않습니다."""


def _invalidate_token(failed: str | None = None) -> None:
    """
    캐시한 토큰을 버립니다.

    :param failed: 401을 받은 토큰. 주면 **그 토큰일 때만** 버립니다 — 다른 쪽이 방금
                   재발급한 새 토큰까지 버리면 서로 무효화를 반복합니다
    """
    global _token_cache
    with _lock:
        if failed is None or (_token_cache and _token_cache[0] == failed):
            _token_cache = None


def _error_text(res: requests.Response) -> str:
    try:
        err = (res.json() or {}).get("error") or {}
        code, message = err.get("code") or "", err.get("message") or ""
        return f"HTTP {res.status_code} {code} {message}".strip()
    except ValueError:
        return f"HTTP {res.status_code}"


def api_get(path: str, params: dict | None = None, timeout: float = 10.0) -> dict | list:
    """
    인증이 필요한 GET 한 번. 응답 envelope의 ``result``를 돌려줍니다.

    :raises TossMissingKey: 키 미설정 (호출하지 않음)
    :raises TossForbidden: 403
    :raises TossError: 그 밖의 실패
    """
    if not has_credentials():
        raise TossMissingKey("TOSS_CLIENT_ID / TOSS_CLIENT_SECRET이 설정되지 않았습니다")

    for attempt in range(3):
        token, error = get_access_token()
        if not token:
            raise TossError(f"토큰 발급 실패 — {error}")
        try:
            res = requests.get(
                f"{BASE_URL}{path}", headers={"Authorization": f"Bearer {token}"},
                params=params, timeout=timeout,
            )
        except requests.RequestException as exc:
            raise TossError(f"연결 실패: {type(exc).__name__}") from None

        if res.status_code == 401 and attempt == 0:
            _invalidate_token(token)  # 다른 곳에서 재발급해 무효가 됐을 수 있습니다 — 한 번만 다시
            continue
        if res.status_code == 429 and attempt < 2:
            try:
                wait = float(res.headers.get("Retry-After", "1"))
            except ValueError:
                wait = 1.0
            time.sleep(min(max(wait, 0.5), 5.0))
            continue
        if res.status_code == 403:
            raise TossForbidden(f"{_error_text(res)} — 토스 개발자 콘솔의 허용 IP에 이 맥의 공인 IP가 있는지 확인하세요")
        if res.status_code != 200:
            raise TossError(_error_text(res))
        try:
            return (res.json() or {}).get("result")
        except ValueError:
            raise TossError("JSON이 아닌 응답") from None
    raise TossError("재시도 한도 초과 (401/429)")


# ==============================================================================
# 투자자별 매매 — 정규화
# ==============================================================================
MARKET_SYMBOLS = ("KOSPI", "KOSDAQ")
INVESTORS = ("foreigner", "institution", "individual", "otherCorporation")
INSTITUTION_BREAKDOWN = (
    "pensionFund", "financialInvestment", "trust", "privateEquityFund",
    "insurance", "bank", "otherFinancialInstitution",
)


def _int(value) -> int | None:
    """문자열 정수 → int. 없거나 숫자가 아니면 None(0으로 바꾸지 않음)."""
    if value is None:
        return None
    try:
        return int(str(value).strip())
    except (TypeError, ValueError):
        return None


def _float(value) -> float | None:
    if value is None:
        return None
    try:
        return float(str(value).strip())
    except (TypeError, ValueError):
        return None


def _amounts(node) -> dict | None:
    """{buyAmount, sellAmount} → {buy, sell, net}. 노드가 없으면 None."""
    if not isinstance(node, dict):
        return None
    buy, sell = _int(node.get("buyAmount")), _int(node.get("sellAmount"))
    return {"buy": buy, "sell": sell, "net": buy - sell if buy is not None and sell is not None else None}


def _volumes(node) -> dict | None:
    """{buyVolume, sellVolume, netBuyVolume} → {buy, sell, net}. 노드가 없으면 None."""
    if not isinstance(node, dict):
        return None
    buy, sell = _int(node.get("buyVolume")), _int(node.get("sellVolume"))
    net = _int(node.get("netBuyVolume"))
    if net is None and buy is not None and sell is not None:
        net = buy - sell
    return {"buy": buy, "sell": sell, "net": net}


def normalize_market_record(rec: dict) -> dict:
    """시장 투자자별 매매대금 기록 한 건 (금액 단위: 원)."""
    institution = rec.get("institution") or {}
    breakdown = institution.get("breakdown") if isinstance(institution, dict) else None
    return {
        "date": str(rec.get("date") or "")[:10] or None,
        "updatedAt": rec.get("updatedAt"),
        "investors": {key: _amounts(rec.get(key)) for key in INVESTORS},
        "breakdown": (
            {key: _amounts(breakdown.get(key)) for key in INSTITUTION_BREAKDOWN}
            if isinstance(breakdown, dict) else None
        ),
    }


def normalize_stock_record(rec: dict) -> dict:
    """종목 투자자별 매매동향 기록 한 건 (수량 단위: 주)."""
    institution = rec.get("institution") or {}
    breakdown = institution.get("breakdown") if isinstance(institution, dict) else None
    holding = rec.get("foreignerHolding")
    return {
        "date": str(rec.get("date") or "")[:10] or None,
        "updatedAt": rec.get("updatedAt"),
        "investors": {key: _volumes(rec.get(key)) for key in INVESTORS},
        "breakdown": (
            {key: _volumes(breakdown.get(key)) for key in INSTITUTION_BREAKDOWN}
            if isinstance(breakdown, dict) else None
        ),
        "foreignerHoldingRate": _float(holding.get("holdingRate")) if isinstance(holding, dict) else None,
    }


def fetch_market_investor_trading(symbol: str, count: int = 30, interval: str = "1d") -> list[dict]:
    """코스피·코스닥 전체의 투자자별 매매대금 (최신순)."""
    if symbol not in MARKET_SYMBOLS:
        raise ValueError(f"지원하지 않는 시장: {symbol}")
    result = api_get(
        f"/api/v1/market-indicators/{symbol}/investor-trading",
        {"interval": interval, "count": min(max(count, 1), 100)},
    ) or {}
    return [normalize_market_record(r) for r in (result.get("records") or []) if isinstance(r, dict)]


def fetch_stock_investor_trading(code: str, count: int = 20) -> list[dict]:
    """국내 종목 하나의 일별 투자자별 매매동향 (최신순)."""
    result = api_get(
        f"/api/v1/stocks/{code}/investor-trading", {"count": min(max(count, 1), 100)},
    ) or {}
    return [normalize_stock_record(r) for r in (result.get("records") or []) if isinstance(r, dict)]
