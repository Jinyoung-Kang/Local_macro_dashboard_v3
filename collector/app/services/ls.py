"""
app/services/ls.py
LS증권 OPEN API 통신.

[진단은 세 단계를 구분합니다 — 조치가 서로 다르기 때문입니다]
  no_keys  : 키 미등록          → 키를 설정
  network  : 서버에 닿지 못함    → 망/포트 확인 (키와 무관)
  rejected : 서버가 키를 거절    → 앱키·시크릿, "Open API" 사용등록 확인

구버전은 망 오류(ConnectionError)까지 "앱키/시크릿이 유효하지 않다"고
표시해서, 멀쩡한 키를 계속 의심하게 만들었습니다.

[포트 주의]
문서에는 오랫동안 :8080이 적혀 있었지만 서버가 그 포트를 더 이상 열어두지
않습니다(31ms 즉시 refused = 방화벽 드롭이 아니라 닫힌 포트). 표준 443을
먼저 쓰고 8080은 보조로만 남깁니다. 토큰을 받아 낸 바로 그 주소로 TR도
보냅니다 — 포트가 갈리면 토큰이 통하지 않습니다.
"""
from __future__ import annotations

import logging
import threading
import time
from zoneinfo import ZoneInfo

import requests

from .. import http, settings
from .. import kst

logger = logging.getLogger(__name__)

KST = ZoneInfo("Asia/Seoul")

FAIL_NO_KEYS = "no_keys"
FAIL_NETWORK = "network"
FAIL_REJECTED = "rejected"
FAIL_BAD_RESPONSE = "bad_response"

_TOKEN_TTL_SECONDS = 5 * 60 * 60

_lock = threading.Lock()
_token_cache: dict[str, tuple[str, float]] = {}
_resolved_base_url = ""

INVESTOR_CODES = {
    "외국인": "1", "기관": "2", "개인": "3",
    "투신": "4", "금융투자": "5", "연기금": "7",
}


def _http() -> requests.Session:
    """이 클라이언트 전용 세션 — 연결만 재사용합니다(재시도·쿠키 없음, app/http.py)."""
    return http.get_api_session("ls")


def has_credentials() -> bool:
    app_key, app_secret = settings.ls_credentials()
    return bool(app_key and app_secret)


def base_urls() -> list[str]:
    override = settings.ls_base_url_override()
    if override:
        return [override.rstrip("/")]
    if _resolved_base_url:
        others = [
            url for url in (settings.LS_BASE_URL, *settings.LS_ALT_BASE_URLS)
            if url != _resolved_base_url
        ]
        return [_resolved_base_url, *others]
    return [settings.LS_BASE_URL, *settings.LS_ALT_BASE_URLS]


def request_token() -> tuple[str, str, str]:
    """
    토큰을 실제로 발급받습니다(캐시 없음).

    반환: (토큰, 실패 사유, 실패 종류). 성공하면 ("eyJ...", "", "").
    """
    global _resolved_base_url

    app_key, app_secret = settings.ls_credentials()
    if not app_key or not app_secret:
        return "", "[ls] app_key / app_secret이 설정되지 않았습니다.", FAIL_NO_KEYS

    payload = {
        "grant_type": "client_credentials",
        "appkey": app_key,
        "appsecretkey": app_secret,
        "scope": "oob",
    }
    headers = {"Content-Type": "application/x-www-form-urlencoded"}

    network_errors: list[str] = []

    for base in base_urls():
        try:
            res = _http().post(
                f"{base}/oauth2/token", headers=headers, data=payload, timeout=10
            )
        except Exception as exc:  # noqa: BLE001
            logger.warning("LS 토큰 발급 통신 실패 (%s): %s", base, exc)
            network_errors.append(f"{base} → {type(exc).__name__}")
            continue

        # 응답이 왔다는 것은 주소·포트가 맞다는 뜻입니다. 상태코드와 무관하게
        # 이 주소를 확정하고 이후 TR도 같은 주소로 보냅니다.
        _resolved_base_url = base

        if res.status_code == 200:
            try:
                token = res.json().get("access_token", "")
            except ValueError:
                return "", "토큰 응답을 JSON으로 읽지 못했습니다.", FAIL_BAD_RESPONSE
            if token:
                return token, "", ""
            return "", "응답에 access_token이 없습니다.", FAIL_BAD_RESPONSE

        detail = (res.text or "")[:200]
        logger.warning("LS 토큰 발급 거절 (%s): %s", res.status_code, detail)
        return "", f"HTTP {res.status_code} — {detail}", FAIL_REJECTED

    return "", " / ".join(network_errors) or "시도할 주소 없음", FAIL_NETWORK


def get_access_token() -> str:
    """성공한 토큰만 캐시합니다(구버전은 실패한 빈 문자열까지 5시간 캐시했습니다)."""
    app_key, app_secret = settings.ls_credentials()
    if not app_key or not app_secret:
        return ""

    cache_key = f"{app_key[:8]}:{app_secret[:8]}"
    with _lock:
        cached = _token_cache.get(cache_key)
        if cached and cached[1] > time.time():
            return cached[0]

    token, _, _ = request_token()
    if token:
        with _lock:
            _token_cache[cache_key] = (token, time.time() + _TOKEN_TTL_SECONDS)
    return token


def call_api(tr_cd: str, tr_url: str, body: dict) -> dict:
    app_key, app_secret = settings.ls_credentials()
    if not app_key or not app_secret:
        return {"rsp_msg": "[ls] app_key / app_secret이 설정되지 않았습니다."}

    token = get_access_token()
    if not token:
        return {"rsp_msg": "LS OAuth2 토큰 발급 실패"}

    base = _resolved_base_url or base_urls()[0]
    headers = {
        "Content-Type": "application/json; charset=utf-8",
        "authorization": f"Bearer {token}",
        "tr_cd": tr_cd,
        "tr_cont": "N",
        "tr_cont_key": "",
        "mac_address": "",
    }

    try:
        res = _http().post(f"{base}{tr_url}", headers=headers, json=body, timeout=10)
    except Exception as exc:  # noqa: BLE001
        logger.warning("LS TR (%s) 호출 실패: %s", tr_cd, exc)
        return {"rsp_msg": f"서버 통신 예외: {exc}"}

    if res.status_code == 200:
        try:
            return res.json()
        except ValueError:
            return {"rsp_msg": "응답을 JSON으로 읽지 못했습니다."}

    try:
        message = res.json().get("rsp_msg", f"HTTP {res.status_code}")
    except ValueError:
        message = f"HTTP {res.status_code}"
    return {"rsp_msg": message, "httpStatus": res.status_code}


def fetch_deal_ranking(
    target_date: str,
    market: str,
    investor: str,
    trade_type: str,
    top_n: int,
) -> list[dict]:
    """
    투자자별 매매 상위 종목.

    t1664(/stock/investor)를 먼저 호출합니다. t1452(/stock/market-sum)는 현재
    HTTP 404이지만 LS가 되살릴 수 있어 보조로 남깁니다. 매번 실패하는 호출을
    앞에 두면 응답만 느려지므로 순서가 중요합니다.
    """
    market_code = "1" if ("KOSPI" in market.upper() or "코스피" in market) else "2"
    order_code = "1" if trade_type == "순매수" else "2"
    investor_code = INVESTOR_CODES.get(investor, "1")

    rows = _call_t1664(market_code, investor_code, order_code, top_n)
    if not rows:
        rows = _call_t1452(market_code, order_code, top_n, investor)

    records: list[dict] = []
    collected_at = kst.stamp()

    for index, row in enumerate(rows[:top_n], start=1):
        code = str(row.get("shcode", "")).strip().zfill(6)
        name = str(row.get("hname", "")).strip()
        if not code or not name:
            continue

        price = _to_float(row.get("price"))
        change_pct = _to_float(row.get("diff"))
        value = _to_float(row.get("svalue")) or _to_float(row.get("forval")) or 0.0
        volume = _to_float(row.get("svolume")) or _to_float(row.get("volume")) or 0.0

        if not value and not volume:
            continue

        net_eok = round(value / 100.0, 1) if value else round(volume * price / 1e8, 1)
        if trade_type == "순매도" and net_eok > 0:
            net_eok = -net_eok

        records.append({
            "rank": index,
            "code": code,
            "name": name,
            "price": price,
            "changePct": change_pct,
            "netAmountEok": net_eok,
            "collectedAt": collected_at,
            "source": f"LS 증권사 API ({target_date})",
        })

    return records


def _call_t1664(market_code: str, investor_code: str, order_code: str, top_n: int):
    body = {
        "t1664InBlock": {
            "gubun1": market_code,
            "gubun2": investor_code,
            "gubun3": order_code,
            "cnt": top_n,
        }
    }
    try:
        res = call_api("t1664", "/stock/investor", body)
    except Exception as exc:  # noqa: BLE001
        logger.warning("LS t1664 호출 실패: %s", exc)
        return []
    return res.get("t1664OutBlock1") or []


def _call_t1452(market_code: str, order_code: str, top_n: int, investor: str):
    body = {
        "t1452InBlock": {
            "gubun": market_code,
            "jnilgubun": "1",
            "paygubun": "2",
            "ordergubun": order_code,
            "cnt": top_n,
        }
    }
    try:
        res = call_api("t1452", "/stock/market-sum", body)
    except Exception as exc:  # noqa: BLE001
        logger.warning("LS t1452 호출 실패: %s", exc)
        return []

    rows = res.get("t1452OutBlock1") or []
    if not rows and res.get("rsp_msg"):
        logger.info("LS t1452 응답: %s", res.get("rsp_msg"))
    return rows


def test_connection() -> dict:
    """
    연결 진단. 실패 종류를 반드시 구분합니다.

    'HTTP 4xx/5xx'는 "데이터 없음"과 달리 장 시간과 무관한 경로·권한 문제이며,
    진단이 이를 따로 표시해야 사용자가 헛다리를 짚지 않습니다.
    """
    if not has_credentials():
        return {
            "ok": False, "stage": FAIL_NO_KEYS,
            "message": "[ls] app_key가 없습니다. 키를 설정하세요.",
        }

    token, reason, kind = request_token()
    if not token:
        if kind == FAIL_NETWORK:
            return {
                "ok": False, "stage": FAIL_NETWORK,
                "message": (
                    "LS 서버에 접속하지 못했습니다 (키 문제가 아닙니다). "
                    f"시도: {reason}"
                ),
            }
        return {
            "ok": False, "stage": kind,
            "message": f"OAuth 토큰 발급 거절 — {reason}",
        }

    res = call_api(
        "t1664",
        "/stock/investor",
        {"t1664InBlock": {"gubun1": "1", "gubun2": "1", "gubun3": "1", "cnt": 5}},
    )
    rows = res.get("t1664OutBlock1") or []
    http_status = res.get("httpStatus")

    if http_status and http_status >= 400:
        return {
            "ok": False, "stage": "endpoint_error",
            "message": (
                f"인증은 성공했지만 TR 경로가 HTTP {http_status}입니다. "
                "장 시간과 무관한 경로·권한 문제입니다."
            ),
        }

    if rows:
        return {"ok": True, "stage": "ok", "message": f"인증 성공 · {len(rows)}건 수신"}

    return {
        "ok": True, "stage": "authenticated_empty",
        "message": (
            "인증 성공 · 조회 데이터 없음 (시세 TR은 정규장에만 데이터를 줍니다). "
            "평일 09:00~15:30에 다시 확인하세요."
        ),
    }


def _to_float(value) -> float:
    try:
        return float(str(value).replace(",", "").strip())
    except (TypeError, ValueError):
        return 0.0
