"""
app/http.py
공용 HTTP 세션 (커넥션 풀 + 재시도).

구버전 services/http_client.py와 같은 목적입니다. 요청마다 새 Session을 만들면
TCP/TLS 핸드셰이크를 매번 반복하고, 레이트리밋 대응(재시도)도 제각각이 됩니다.
"""
from __future__ import annotations

import logging
import threading
import time
from http.cookiejar import DefaultCookiePolicy

import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

logger = logging.getLogger(__name__)

BROWSER_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/124.0.0.0 Safari/537.36"
    ),
    "Accept-Language": "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
}

# ==============================================================================
# SEC EDGAR User-Agent
# ==============================================================================
# SEC는 연락처가 포함된 User-Agent를 **의무**로 요구합니다(미준수 시 403).
#
# 이 값은 코드에 두지 않습니다. 연락처는 사람마다 다르고, 남의 이메일이 기본값으로
# 박혀 있으면 (a) 본인 것을 넣을 이유가 사라지고 (b) SEC 입장에서는 정체를 숨긴
# 요청이 됩니다. .env의 SEC_USER_AGENT로만 받습니다.
#   open -e .env  →  SEC_USER_AGENT=your-name@example.com
#
# 미설정이면 SecUserAgentMissing을 던져 13F 수집만 멈춥니다. 예시 주소로 조용히
# 요청을 보내면 403이 났을 때 원인을 찾기 어렵기 때문입니다.
_UA_PRODUCT = "LocalMacroDashboard/2.0"


_ENV_HINT = "(open -e .env → SEC_USER_AGENT=your-name@example.com → make up)"

# .env에 안내 문구를 지우지 않고 그대로 둔 경우를 걸러 냅니다.
_PLACEHOLDERS = (
    "이메일", "본인이메일", "your-name@example.com", "your-email",
    "youremail", "example@example.com", "name@example.com",
)


class SecUserAgentInvalid(RuntimeError):
    """SEC_USER_AGENT 값이 쓸 수 없는 상태입니다."""


class SecUserAgentMissing(SecUserAgentInvalid):
    """SEC_USER_AGENT가 비어 있습니다."""

    def __init__(self) -> None:
        super().__init__(
            "SEC_USER_AGENT가 설정되지 않았습니다. SEC EDGAR는 연락처 없는 요청을 "
            f"403으로 막습니다. .env에 본인 이메일을 넣으세요 {_ENV_HINT}"
        )


def sec_user_agent() -> str:
    """
    SEC EDGAR에 보낼 User-Agent.

    .env에 이메일만 적어도 되도록, 연락처 형태가 아니면 SEC가 요구하는
    "제품명 (contact: 연락처)" 형태로 감싸 줍니다.

    값이 잘못돼 있으면 **요청을 보내기 전에** 멈춥니다. 특히 한글이 섞이면
    requests가 헤더를 latin-1로 인코딩하다가 이렇게 터집니다.

        UnicodeEncodeError: 'latin-1' codec can't encode characters …

    이 메시지만 보고 .env를 고쳐야 한다는 걸 알아채기는 어렵습니다.
    """
    from . import settings  # 순환 import 방지를 위해 함수 안에서 가져옵니다.

    configured = settings.sec_user_agent()
    if not configured:
        raise SecUserAgentMissing()

    # HTTP 헤더는 latin-1로만 보낼 수 있습니다. 한글 주소는 애초에 불가능합니다.
    try:
        configured.encode("latin-1")
    except UnicodeEncodeError:
        raise SecUserAgentInvalid(
            f"SEC_USER_AGENT에 영문/숫자가 아닌 문자가 있습니다: {configured!r}. "
            "HTTP 헤더는 한글을 담을 수 없습니다. 실제 이메일 주소를 "
            f"영문 그대로 적어 주세요 {_ENV_HINT}"
        ) from None

    lowered = configured.lower()
    if any(token in lowered for token in _PLACEHOLDERS):
        raise SecUserAgentInvalid(
            f"SEC_USER_AGENT가 예시 값 그대로입니다: {configured!r}. "
            "SEC는 연락이 닿지 않는 요청을 차단합니다. 실제 이메일로 바꿔 주세요 "
            f"{_ENV_HINT}"
        )

    if "@" not in configured:
        raise SecUserAgentInvalid(
            f"SEC_USER_AGENT에 이메일 주소가 없습니다: {configured!r}. "
            f"SEC는 연락처를 요구합니다 {_ENV_HINT}"
        )

    if "(" not in configured:
        # 이메일만 적어 둔 경우(구버전 secrets.toml 형식)
        return f"{_UA_PRODUCT} (contact: {configured})"
    return configured


def sec_headers() -> dict:
    return {
        "User-Agent": sec_user_agent(),
        "Accept-Encoding": "gzip, deflate",
    }

_lock = threading.Lock()
_sessions: dict[str, requests.Session] = {}


def _build_session(
    name: str,
    *,
    headers: dict,
    total_retries: int,
    backoff: float,
    pool_size: int,
) -> requests.Session:
    session = requests.Session()
    retry = Retry(
        total=total_retries,
        backoff_factor=backoff,
        status_forcelist=(403, 408, 429, 500, 502, 503, 504),
        allowed_methods=("GET", "POST"),
        raise_on_status=False,
    )
    adapter = HTTPAdapter(
        max_retries=retry,
        pool_connections=pool_size,
        pool_maxsize=pool_size,
    )
    session.mount("https://", adapter)
    session.mount("http://", adapter)
    session.headers.update(headers)
    logger.debug("HTTP 세션 생성: %s", name)
    return session


def get_session() -> requests.Session:
    """일반 웹 소스(Daum/Naver/TradingView/CFTC)용 공용 세션."""
    return _get("default", headers=BROWSER_HEADERS, retries=2, backoff=0.5, pool=16)


def get_fred_session() -> requests.Session:
    """
    FRED 전용 세션.

    FRED 웹 CSV는 기본 User-Agent로 요청하면 403을 주는 경우가 있어
    브라우저 헤더를 붙입니다.
    """
    return _get("fred", headers=BROWSER_HEADERS, retries=3, backoff=0.8, pool=8)


def get_sec_session() -> requests.Session:
    """
    SEC EDGAR 전용 세션 (연락처 포함 UA + 넉넉한 재시도).

    UA가 없으면 여기서 SecUserAgentMissing이 납니다. 세션을 만들어 두고
    나중에 403을 받는 것보다, 요청 전에 설정 누락을 말하는 쪽이 낫습니다.
    """
    headers = sec_headers()
    session = _get("sec", headers=headers, retries=5, backoff=1.5, pool=10)
    # .env를 고치고 컨테이너만 재시작한 경우에도 새 UA가 반영되도록 맞춰 둡니다.
    if session.headers.get("User-Agent") != headers["User-Agent"]:
        session.headers.update(headers)
    return session


def get_api_session(name: str) -> requests.Session:
    """
    증권사 Open API(토스·KIS·LS)용 세션 — 연결(TCP·TLS)만 재사용합니다.

    예전에는 호출마다 requests.get/post로 새로 연결했습니다. 아래 두 가지는 그때와 같습니다.
      - 재시도하지 않습니다. 이 API들은 부르는 쪽이 401(토큰 재발급)·429(Retry-After 대기)·
        403(허용 IP)을 직접 처리합니다. 공용 세션처럼 어댑터가 403·429를 다시 보내면 그
        처리와 겹치고, 토큰 발급 POST가 두 번 나가 먼저 받은 토큰이 무효가 될 수 있습니다.
      - 쿠키를 저장하지 않습니다. 이전 응답의 쿠키가 다음 요청에 실리지 않습니다.
    인증 헤더는 요청마다 넘깁니다(세션에 두지 않음).

    :param name: 클라이언트 이름 (toss / kis / ls). 이름마다 세션이 하나입니다
    """
    with _lock:
        session = _sessions.get(name)
        if session is None:
            session = requests.Session()
            session.cookies.set_policy(DefaultCookiePolicy(allowed_domains=[]))
            adapter = HTTPAdapter(pool_connections=4, pool_maxsize=8)  # max_retries 기본값 0
            session.mount("https://", adapter)
            session.mount("http://", adapter)
            _sessions[name] = session
            logger.debug("HTTP 세션 생성: %s", name)
        return session


def _get(
    name: str, *, headers: dict, retries: int, backoff: float, pool: int
) -> requests.Session:
    with _lock:
        session = _sessions.get(name)
        if session is None:
            session = _build_session(
                name,
                headers=headers,
                total_retries=retries,
                backoff=backoff,
                pool_size=pool,
            )
            _sessions[name] = session
        return session


# ==============================================================================
# 실패 사유 문자열
# ==============================================================================
# requests 예외를 그대로 문자열로 만들면 urllib3 스택이 통째로 들어옵니다.
#   "HTTPSConnectionPool(host='fred.stlouisfed.org', port=443): Max retries
#    exceeded with url: /graph/fredgraph.csv?id=DGS2 (Caused by ProxyError(…"
# 이걸 120자로 자르면 괄호가 짝이 안 맞는 채로 끊겨 더 읽기 어려워집니다.
# 사유는 로그가 아니라 **화면에 뜨는 한 줄**이므로, 조치로 이어지는 문장으로
# 압축합니다.
_ERROR_HINTS: tuple[tuple[str, str], ...] = (
    ("proxy", "프록시가 연결을 막았습니다 (회사망·보안 프로그램·Docker 프록시 설정 확인)"),
    ("timed out", "응답이 없어 시간이 초과됐습니다"),
    ("timeout", "응답이 없어 시간이 초과됐습니다"),
    ("ssl", "TLS 검증에 실패했습니다"),
    ("name or service not known", "도메인 이름을 찾지 못했습니다 (DNS)"),
    ("nodename nor servname", "도메인 이름을 찾지 못했습니다 (DNS)"),
    ("connection refused", "서버가 연결을 거부했습니다"),
    ("max retries exceeded", "재시도를 모두 소진했습니다 (연결 불가)"),
)


def brief_error(exc: BaseException, *, limit: int = 110) -> str:
    """예외를 '무엇을 해야 하는지'가 보이는 한 줄로 줄입니다."""
    text = str(exc)
    lowered = text.lower()
    for needle, hint in _ERROR_HINTS:
        if needle in lowered:
            return f"{type(exc).__name__} — {hint}"

    collapsed = " ".join(text.split())
    if len(collapsed) > limit:
        # 자를 때 괄호가 열린 채 끝나지 않도록 마지막 '(' 이후를 버립니다.
        collapsed = collapsed[:limit]
        opened = collapsed.rfind("(")
        if opened > limit // 2 and collapsed.count("(") > collapsed.count(")"):
            collapsed = collapsed[:opened]
        collapsed = collapsed.rstrip(" ,;:(") + "…"
    return f"{type(exc).__name__}: {collapsed}" if collapsed else type(exc).__name__


def close_all() -> None:
    with _lock:
        for session in _sessions.values():
            session.close()
        _sessions.clear()


# ==============================================================================
# SEC 레이트 리미터 (초당 요청 한도 준수 + 병렬 수집 허용)
# ==============================================================================
# SEC EDGAR는 초당 10요청을 넘기면 차단합니다. 요청 사이에 sleep을 넣는 방식은
# 호출을 **직렬화**해서 기관 12곳 × 8분기 = 약 200요청이 한 줄로 늘어섭니다.
# 토큰 버킷은 "전체 합계가 초당 N건"만 지키므로 병렬 수집이 가능합니다.
# 한도는 10이 아니라 8로 둡니다(버스트·시계 오차 여유분).
_SEC_MAX_RPS = 8.0
_sec_lock = threading.Lock()
_sec_next_slot = [0.0]


def sec_rate_limit() -> None:
    interval = 1.0 / _SEC_MAX_RPS
    with _sec_lock:
        now = time.monotonic()
        slot = max(now, _sec_next_slot[0])
        _sec_next_slot[0] = slot + interval
    wait = slot - time.monotonic()
    if wait > 0:
        time.sleep(wait)
