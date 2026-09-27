"""
app/logredact.py
로그에 비밀값이 새어 나가지 않게 가립니다.

<b>실제로 겪은 일</b> — FRED 호출이 느려 urllib3가 재시도 경고를 찍었는데,
그 경고에 **요청 URL 전체**가 들어 있었습니다.

    WARNING urllib3.connectionpool: Retrying (...) after connection broken by
    'ReadTimeoutError(...)': /fred/series/observations?series_id=WALCL
    &api_key=deadbeefdeadbeefdeadbeefdeadbeef&file_type=json&...

API 키가 그대로 노출됩니다. `make logs`를 뜨는 사람, 로그를 붙여 넣는 곳,
로그 수집기 어디에나 남습니다.

우리 코드만 조심해서는 막을 수 없습니다. URL을 찍는 쪽이 서드파티
라이브러리이기 때문입니다. 그래서 **로깅 계층에서** 가립니다 — 어떤 코드가
찍든 한 곳에서 걸립니다.
"""
from __future__ import annotations

import logging
import re

# 이름만 봐도 비밀인 쿼리 파라미터들. 값이 무엇이든 가립니다(대소문자 무시).
#
# 국내 공공 API는 키 파라미터 이름이 제각각이라 기관별로 적어 둡니다.
#   serviceKey            공공데이터포털(apis.data.go.kr) 전부
#   crtfc_key             금융감독원 Open DART
#   apiKey                KOSIS
#   key / KEY             V-World, 한국부동산원 R-ONE
#   consumer_key·secret,  SGIS (인증 → accessToken 발급)
#   accessToken
#   confmKey              도로명주소
#   client_secret         토스증권 토큰 발급 (secret 앞의 '_' 때문에 secret 규칙에 걸리지 않음)
# 주의사항 — 새 API를 붙일 때 키 파라미터 이름이 여기 없으면 로그에 그대로
# 찍힙니다. tests/test_log_redaction.py에 표본을 함께 추가하세요.
_SECRET_PARAMS = (
    "api_key", "apikey", "auth_key", "authkey", "token", "access_token",
    "appkey", "app_key", "appsecret", "app_secret", "secret", "password",
    "servicekey", "crtfc_key", "key", "consumer_key", "consumer_secret",
    "accesstoken", "confmkey", "client_secret",
)

_QUERY_PATTERN = re.compile(
    r"(?i)\b(" + "|".join(_SECRET_PARAMS) + r")=([^&\s\"'<>()]+)"
)

# "Authorization: Bearer xxx" 같은 헤더 표기도 가립니다.
_BEARER_PATTERN = re.compile(r"(?i)(bearer\s+)([A-Za-z0-9._\-]{8,})")

_MASK = "***redacted***"


def redact(text: str) -> str:
    """문자열에서 비밀값으로 보이는 부분을 가립니다."""
    text = _QUERY_PATTERN.sub(lambda m: f"{m.group(1)}={_MASK}", text)
    return _BEARER_PATTERN.sub(lambda m: f"{m.group(1)}{_MASK}", text)


def _might_contain_secret(text: str) -> bool:
    """
    정규식을 돌릴 가치가 있는지 싸게 걸러 냅니다.

    로그 한 줄마다 정규식 두 개를 돌리는 것은 낭비입니다. 다만 이 검사가
    좁으면 진짜 비밀이 새 나가므로, 가릴 수 있는 형태는 모두 통과시킵니다.
    """
    return "=" in text or "earer" in text


class RedactingFilter(logging.Filter):
    """
    모든 로그 레코드를 지나가며 비밀값을 가립니다.

    메시지와 인자를 각각 손봅니다. urllib3처럼 %s 인자에 URL을 담는 경우가
    있어 둘 다 확인해야 합니다.
    """

    def filter(self, record: logging.LogRecord) -> bool:
        if isinstance(record.msg, str) and _might_contain_secret(record.msg):
            record.msg = redact(record.msg)

        if record.args:
            if isinstance(record.args, dict):
                record.args = {
                    key: redact(value) if isinstance(value, str) else value
                    for key, value in record.args.items()
                }
            elif isinstance(record.args, tuple):
                record.args = tuple(
                    redact(value) if isinstance(value, str) else value
                    for value in record.args
                )
        return True


class SafeFormatFilter(logging.Filter):
    """
    형식이 깨진 로그 한 줄이 traceback 40줄로 번지지 않게 합니다.

    <b>실제로 겪은 일</b> — pykrx가 내부에서 이렇게 찍습니다.

        logging.info(args, kwargs)      # pykrx/website/comm/util.py

    메시지 자리에 튜플, 인자 자리에 dict가 들어갑니다. 로깅이 출력 직전에
    ``str(msg) % args``를 하다 TypeError를 내고, 파이썬은 그 실패를 다시
    "--- Logging error ---" + 전체 traceback으로 찍습니다. 수급 레이더가
    폴백 날짜를 훑으면 이 덩어리가 날짜마다 반복돼, `make logs`가 실제
    오류를 찾을 수 없는 상태가 됐습니다.

    라이브러리 호출을 우리가 고칠 수는 없으니 출력 직전에 안전한 한 줄로
    바꿉니다. 내용은 버리지 않습니다 — 무엇을 찍으려 했는지는 남깁니다.
    """

    def filter(self, record: logging.LogRecord) -> bool:
        if not record.args:
            return True
        try:
            record.getMessage()
        except (TypeError, ValueError):
            record.msg = f"{record.msg!r} (인자: {record.args!r})"
            record.args = ()
        return True


class LibraryChatterFilter(logging.Filter):
    """
    pykrx가 루트 로거에 직접 찍는 INFO 잡음을 걷어냅니다.

    pykrx는 자기 로거를 쓰지 않고 ``logging.info(...)``로 루트에 찍기 때문에
    로거 이름으로는 수위를 조절할 수 없습니다. 그래서 레코드가 어느 파일에서
    왔는지로 거릅니다.

    버려도 되는 이유 — 우리 코드가 같은 상황을 더 분명하게 남깁니다
    ("PyKrx 빈 결과 (20260917): 휴장일이거나 데이터 미제공"). 경고·오류는
    그대로 통과시킵니다.
    """

    def filter(self, record: logging.LogRecord) -> bool:
        if record.levelno >= logging.WARNING:
            return True
        return "/pykrx/" not in (record.pathname or "")


def install() -> None:
    """
    루트 핸들러 전부에 필터를 답니다.

    로거가 아니라 **핸들러**에 다는 이유: 필터는 로거 계층을 따라 전파되지
    않습니다. 핸들러에 달면 어느 로거에서 온 레코드든 출력 직전에 걸립니다.

    순서가 있습니다. 형식을 먼저 안전하게 만들고(SafeFormat), 그다음 비밀값을
    가립니다(Redacting). 반대로 달면 깨진 레코드를 가리려다 같은 자리에서
    다시 터집니다.
    """
    filters = [SafeFormatFilter(), LibraryChatterFilter(), RedactingFilter()]
    root = logging.getLogger()
    for handler in root.handlers:
        for log_filter in filters:
            handler.addFilter(log_filter)
