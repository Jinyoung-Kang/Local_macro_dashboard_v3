"""
수집기 설정과 외부 API 키 로더.

키는 **환경변수 + 선택적 TOML 파일** 두 경로에서 읽습니다.

**키가 없어도 수집기는 정상 기동해야 합니다.** 키가 필요한 소스만 건너뛰고,
무엇이 왜 빠졌는지 /status가 알려 줍니다. 어떤 키가 없으면 무엇이 안 되는지는
README의 "설정" 표에 있습니다.
"""
from __future__ import annotations

import logging
import os
import tomllib
from functools import lru_cache
from pathlib import Path

logger = logging.getLogger(__name__)

# secrets.toml 경로. 없으면 환경변수만으로 동작합니다.
_SECRETS_PATH = Path(
    os.environ.get("MACRO_SECRETS_FILE", "/run/secrets/secrets.toml")
)


@lru_cache(maxsize=1)
def _load_secrets_file() -> dict:
    """secrets.toml을 한 번만 읽습니다. 없거나 깨졌으면 빈 dict."""
    if not _SECRETS_PATH.exists():
        return {}
    try:
        with _SECRETS_PATH.open("rb") as fp:
            return tomllib.load(fp)
    except Exception as exc:  # noqa: BLE001
        logger.warning("secrets 파일을 읽지 못했습니다 (%s): %s", _SECRETS_PATH, exc)
        return {}


def get_secret(key_path: str, default: str = "") -> str:
    """
    'fred.api_key' 같은 점 표기로 값을 찾습니다.

    탐색 순서
      1) 환경변수 FRED_API_KEY (점 → 밑줄, 대문자)
      2) 환경변수 key_path 그대로
      3) secrets.toml의 [fred] api_key

    컨테이너 환경에서는 환경변수가 1차 수단이라 TOML 파일보다 먼저 봅니다.

    :returns: 값의 앞뒤 공백을 제거한 문자열. 어디에도 없으면 빈 문자열
    """
    env_name = key_path.replace(".", "_").upper()
    for candidate in (env_name, key_path):
        value = os.environ.get(candidate)
        if value and value.strip():
            return value.strip()

    node: object = _load_secrets_file()
    for part in key_path.split("."):
        if isinstance(node, dict) and part in node:
            node = node[part]
        else:
            return default

    return str(node).strip() if node is not None else default


# ==============================================================================
# 데이터베이스 / 서비스 설정
# ==============================================================================
def database_url() -> str:
    """psycopg 연결 문자열."""
    return os.environ.get(
        "DATABASE_URL",
        "postgresql://macro:macro@localhost:5432/macrodash",
    )


def scheduler_enabled() -> bool:
    """
    상주 스케줄러 사용 여부.

    끄면 수집기는
    REST 요청(POST /collect)으로만 동작합니다.
    """
    return os.environ.get("COLLECTOR_SCHEDULER", "true").lower() in (
        "1", "true", "yes", "on",
    )


def interval_seconds(group: str) -> int:
    """작업군별 수집 주기 (기본 5분 / 1시간 / 12시간)."""
    defaults = {"fast": 5 * 60, "slow": 60 * 60, "weekly": 12 * 60 * 60}
    env_key = f"COLLECTOR_{group.upper()}_INTERVAL"
    try:
        return max(30, int(os.environ.get(env_key, defaults[group])))
    except (TypeError, ValueError):
        return defaults[group]


RUN_LOG_RETENTION_DAYS = 90


def run_log_retention_days() -> int:
    """
    수집 실행 기록 보존 기간(일). 기본 90일, 최소 7일.

    오류 모음 화면은 최근 24시간, 실행 이력 화면은 최근 200건까지만 봅니다. 잘못된 값이나
    너무 짧은 값으로 방금 기록까지 지우지 않도록 7일 밑으로는 내리지 않습니다.
    """
    try:
        return max(7, int(os.environ.get("COLLECTOR_RUN_LOG_RETENTION_DAYS", RUN_LOG_RETENTION_DAYS)))
    except (TypeError, ValueError):
        return RUN_LOG_RETENTION_DAYS


# ==============================================================================
# 외부 API 키 (없으면 빈 문자열)
# ==============================================================================
def fred_key() -> str:
    return get_secret("fred.api_key") or get_secret("FRED_API_KEY")


def krx_key() -> str:
    return (
        get_secret("krx.api_key")
        or get_secret("krx.auth_key")
        or get_secret("KRX_AUTH_KEY")
    )


def kis_credentials() -> tuple[str, str]:
    return get_secret("kis.app_key"), get_secret("kis.app_secret")


def ls_credentials() -> tuple[str, str]:
    return get_secret("ls.app_key"), get_secret("ls.app_secret")


def ls_base_url_override() -> str:
    return get_secret("ls.base_url")


def toss_credentials() -> tuple[str, str]:
    return get_secret("toss.client_id"), get_secret("toss.client_secret")


# ---- 국내 공공 API --------------------------------------------------------------
# 공공데이터포털(data.go.kr)은 계정 하나에 인증키 하나이고, 활용 신청한 서비스마다
# 같은 키를 씁니다. 그래서 특일정보·주식시세가 한 값을 공유합니다.
def data_go_kr_key() -> str:
    """
    공공데이터포털 일반 인증키.

    Encoding 키(``%``가 들어 있음)와 Decoding 키 어느 쪽을 넣어도 됩니다.
    보내기 직전에 ``publicapi.encoded_key``가 한 가지 형태로 맞춥니다.

    :returns: 키 문자열. 없으면 빈 문자열이고 해당 수집만 멈춥니다
    """
    # 환경변수 DATA_GO_KR_SERVICE_KEY 또는 secrets.toml [data_go_kr] service_key
    return get_secret("data_go_kr.service_key")


def dart_key() -> str:
    """금융감독원 Open DART 인증키(40자리). 없으면 빈 문자열."""
    # 환경변수 DART_API_KEY 또는 secrets.toml [dart] api_key
    return get_secret("dart.api_key")


def sec_user_agent() -> str:
    """
    SEC EDGAR가 요구하는 연락처 포함 User-Agent.

    SEC는 "Declare your user agent in request headers"를 의무로 두고, 연락처가
    없거나 예시 값이면 403을 돌려줍니다. 키가 아니라 **연락 가능한 이메일**입니다.

    ``sec.user_agent``(TOML)와 ``SEC_USER_AGENT``(환경변수) 둘 다 받습니다.

    :returns: User-Agent 문자열. 설정하지 않았으면 빈 문자열이고 13F 수집만 멈춥니다
    """
    return get_secret("sec.user_agent") or get_secret("SEC_USER_AGENT")


KRX_BASE_URL = "https://data-dbg.krx.co.kr/svc/apis"
FRED_API_BASE = "https://api.stlouisfed.org/fred"
FRED_CSV_BASE = "https://fred.stlouisfed.org/graph/fredgraph.csv"
KIS_BASE_URL = "https://openapi.koreainvestment.com:9443"

# LS OPEN API 주소.
# 문서에는 오랫동안 :8080이 적혀 있었지만 서버가 그 포트를 더 이상 열어두지
# 않습니다(즉시 connection refused). 표준 443을 먼저 쓰고 8080은 보조로만
# 남깁니다.
LS_BASE_URL = "https://openapi.ls-sec.co.kr"
LS_ALT_BASE_URLS = ("https://openapi.ls-sec.co.kr:8080",)
