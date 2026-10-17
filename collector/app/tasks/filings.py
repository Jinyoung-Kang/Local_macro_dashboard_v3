"""
app/tasks/filings.py
weekly 그룹 — SEC 13F 기관 포트폴리오.
"""
from __future__ import annotations

import logging
from concurrent.futures import ThreadPoolExecutor

from .. import catalog, equities, http, indicators, store
from ..services import (
    sec13f as sec_service,
)

from .base import EmptyResult

logger = logging.getLogger(__name__)


# ==============================================================================
# weekly
# ==============================================================================
def task_sec_13f() -> str:
    """
    SEC 13F — 전체 수집에서 가장 오래 걸리는 작업.

    [최적화 2가지 — 구버전과 동일]
    1) q1은 q8의 부분집합입니다(공시를 최신순으로 훑어 앞에서 자릅니다).
       q8만 수집하고 q1은 잘라서 저장합니다 (수집 24건 → 12건).
    2) SEC 한도는 토큰 버킷이 전역으로 지키므로 기관을 병렬 처리해도
       합계 한도를 넘지 않습니다.

    저장할 때 보유마다 티커(``ticker``, 모르면 None)를 붙입니다 — 매핑표(equities)의 두 단계 매칭.
    이어받은(isStale) 분기도 함께 붙이므로 매핑표에 이름을 더하면 다음 실행부터 모든 분기에 반영됩니다(QA-087).
    """
    # 설정이 없으면 기관 12곳에 같은 실패를 12번 만들지 않고 여기서 한 번 말합니다.
    try:
        http.sec_user_agent()
    except http.SecUserAgentInvalid as exc:
        raise EmptyResult(f"0/{len(indicators.INSTITUTIONS)} 기관 — {exc}") from exc

    quarters = catalog.MAX_TRACKED_QUARTERS
    targets = [(inst["name"], inst["cik"]) for inst in indicators.INSTITUTIONS]

    ok = 0
    saved = 0
    errors: list[str] = []

    def collect(item):
        name, cik = item
        # 받지 못한 공시는 직전 저장본의 같은 공시로 잇습니다(QA-031).
        previous = store.read_snapshot(catalog.snap_sec_13f(cik, quarters))
        previous_quarters = (previous.payload or {}).get("quarters") if previous else None
        return name, cik, sec_service.collect_13f(cik, quarters, previous_quarters=previous_quarters)

    with ThreadPoolExecutor(max_workers=4) as pool:
        for name, cik, payload in pool.map(collect, targets):
            if not payload.get("quarters"):
                logger.info("13F 빈 결과(저장본 유지): %s (%s)", name, payload.get("error"))
                errors.append(f"{name}: {payload.get('error') or '빈 결과'}")
                continue

            equities.attach_tickers(payload["quarters"])
            store.put_snapshot(catalog.snap_sec_13f(cik, quarters), payload)
            saved += 1

            # q1은 q8의 첫 분기와 같은 데이터입니다. 재수집하지 않습니다.
            store.put_snapshot(
                catalog.snap_sec_13f(cik, 1),
                {**payload, "quarters": payload["quarters"][:1]},
            )
            saved += 1
            ok += 1

    if not ok:
        raise EmptyResult(
            f"0/{len(targets)} 기관 — 기존 저장본 유지"
            + (f" ({errors[0]})" if errors else "")
        )

    detail = f"{ok}/{len(targets)} 기관, 스냅샷 {saved}건 (q{quarters} 수집 → q1 유도)"
    if errors:
        detail += f", 실패 {len(errors)}곳"
    return detail


