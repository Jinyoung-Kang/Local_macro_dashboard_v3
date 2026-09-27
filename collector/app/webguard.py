"""
app/webguard.py
브라우저에서 온 교차 출처 요청을 받지 않습니다.

[왜 필요한가]
수집기는 127.0.0.1에만 열려 있지만, 사용자가 켜 둔 **브라우저**는 그 주소에 닿습니다.
다른 사이트의 페이지가 `fetch(..., {mode: "no-cors"})`로 POST를 보내면 응답은 못 읽어도
요청은 실행됩니다(사전 확인 없는 '단순 요청'). 토큰(COLLECTOR_API_TOKEN)이 비어 있는
기본 상태에서는 `/maintenance/purge`로 다시 받을 수 없는 누적 이력을 지울 수 있었습니다.

[어떻게 가려내나]
수집기를 부르는 쪽은 백엔드(RestClient)·make(curl)·헬스체크뿐이고, 이들은 브라우저가
붙이는 헤더를 보내지 않습니다.
  1) Sec-Fetch-Site — 요즘 브라우저는 모든 요청에 붙입니다. `none`(주소창 직접 입력·북마크)과
     `same-origin`(수집기 자신의 /docs)만 통과시킵니다. `same-site`도 막습니다 — localhost의
     다른 포트(다른 로컬 앱)가 같은 '사이트'로 취급되기 때문입니다.
  2) Origin — Sec-Fetch-Site를 모르는 옛 브라우저도 POST에는 Origin을 붙입니다. 자기 자신과
     다르면(`null` 포함) 막습니다.

주의사항 — 토큰과 별개로 동작합니다. 토큰을 설정했어도 브라우저발 요청은 막습니다.
"""
from __future__ import annotations

import logging

from starlette.datastructures import Headers
from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Receive, Scope, Send

logger = logging.getLogger(__name__)

_ALLOWED_FETCH_SITES = frozenset({"none", "same-origin"})

REJECT_MESSAGE = (
    "브라우저에서 온 교차 출처 요청은 받지 않습니다. 수집기는 백엔드와 make(curl)만 호출합니다."
)


def is_cross_site_browser_request(headers: Headers, scheme: str) -> bool:
    """
    브라우저가 다른 출처의 페이지에서 보낸 요청인지.

    :param headers: 요청 헤더
    :param scheme: 요청 스킴(http/https) — Origin과 자기 자신을 비교할 때 씁니다
    :returns: 막아야 하면 True. 브라우저 헤더가 없으면(백엔드·curl) False
    """
    site = headers.get("sec-fetch-site")
    if site is not None:
        return site.strip().lower() not in _ALLOWED_FETCH_SITES

    origin = headers.get("origin")
    if origin is None:
        return False
    own = f"{scheme}://{headers.get('host', '')}"
    return origin.strip().rstrip("/").lower() != own.lower()


class CrossSiteRequestGuard:
    """
    교차 출처 브라우저 요청을 403으로 끝내는 ASGI 미들웨어.

    BaseHTTPMiddleware 대신 순수 ASGI로 둡니다. 응답 본문을 감싸지 않아 스트리밍·
    백그라운드 작업에 끼어들지 않습니다.
    """

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] == "http":
            headers = Headers(scope=scope)
            if is_cross_site_browser_request(headers, scope.get("scheme", "http")):
                logger.warning(
                    "교차 출처 브라우저 요청 거부: %s %s (Origin=%s, Sec-Fetch-Site=%s)",
                    scope.get("method"), scope.get("path"),
                    headers.get("origin"), headers.get("sec-fetch-site"),
                )
                response = JSONResponse(status_code=403, content={"detail": REJECT_MESSAGE})
                await response(scope, receive, send)
                return
        await self.app(scope, receive, send)
