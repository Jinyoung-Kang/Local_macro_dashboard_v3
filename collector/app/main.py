"""
app/main.py
수집기 FastAPI 애플리케이션.

[역할 분담]
  수집기(Python)  : 외부 소스 수집·파싱·적재, 그리고 키가 필요한 실시간 조회
  백엔드(Java)    : 저장본 읽기·분석·캐시·인증·화면용 API
  프런트(Next.js) : 표시

구버전의 `python collector.py --loop` / `--status` / `--task` / `--verify`는
각각 스케줄러와 아래 엔드포인트로 옮겼습니다.

  --loop            → 상주 스케줄러 (COLLECTOR_SCHEDULER=true)
  --only fast       → POST /collect?group=fast
  --task krx_futures→ POST /collect/task/krx_futures
  --status          → GET  /status
  --history         → GET  /task-history
  --verify          → GET  /verify/* (판정은 백엔드가 합니다)
  --purge-days      → POST /maintenance/purge?days=400
"""
from __future__ import annotations

import hmac
import logging
import os
import re
from contextlib import asynccontextmanager
from datetime import date, datetime
from typing import Any
from zoneinfo import ZoneInfo

from apscheduler.schedulers.background import BackgroundScheduler
from fastapi import BackgroundTasks, FastAPI, Header, HTTPException, Query
from starlette.middleware.trustedhost import TrustedHostMiddleware

from . import catalog, indicators, krcalendar, logredact, settings, store, tasks, verification, webguard
from .services import (
    kis as kis_service,
    krx as krx_service,
    ls as ls_service,
    market as market_service,
    radar as radar_service,
    toss as toss_service,
)

logging.basicConfig(
    level=os.environ.get("LOG_LEVEL", "INFO").upper(),
    format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
    datefmt="%H:%M:%S",
)

# basicConfig 직후에 답니다. 이 뒤로 찍히는 모든 로그에서 API 키 같은
# 비밀값이 가려집니다 (서드파티 라이브러리가 찍는 것까지 포함).
logredact.install()

logger = logging.getLogger("collector")

KST = ZoneInfo("Asia/Seoul")

scheduler: BackgroundScheduler | None = None

# 내부 서비스 간 호출용 토큰. make setup이 무작위 값으로 채웁니다.
# 비어 있으면 기동하지 않습니다 — COLLECTOR_ALLOW_NO_TOKEN=1을 명시했을 때만 경고하고 기동합니다.
API_TOKEN = os.environ.get("COLLECTOR_API_TOKEN", "")
ALLOW_NO_TOKEN = os.environ.get("COLLECTOR_ALLOW_NO_TOKEN", "").lower() in ("1", "true", "yes")

# Host 헤더 허용 목록. 수집기는 127.0.0.1에만 열려 있지만, DNS 리바인딩(공격자 도메인이 나중에
# 127.0.0.1을 가리킴)으로 브라우저가 '같은 출처' 요청을 보내게 할 수 있습니다. 그 요청의 Host는
# 공격자 도메인이므로 여기서 걸립니다. 백엔드는 compose 서비스 이름(collector)으로 부릅니다.
ALLOWED_HOSTS = [
    h.strip() for h in os.environ.get(
        "COLLECTOR_ALLOWED_HOSTS", "localhost,127.0.0.1,[::1],collector,testserver"
    ).split(",") if h.strip()
]


def check_token_configured() -> None:
    """토큰이 비어 있으면 기동을 거부합니다(명시적으로 허용했을 때만 경고로 끝냄)."""
    if API_TOKEN:
        return
    if ALLOW_NO_TOKEN:
        logger.warning(
            "COLLECTOR_API_TOKEN이 비어 있어 수집기가 인증 없이 요청을 받습니다 "
            "(COLLECTOR_ALLOW_NO_TOKEN=1). 로컬 개발용이 아니라면 make setup으로 토큰을 만드세요."
        )
        return
    raise RuntimeError(
        "COLLECTOR_API_TOKEN이 비어 있습니다. make setup이 .env에 토큰을 만들어 줍니다. "
        "인증 없이 띄우려면 COLLECTOR_ALLOW_NO_TOKEN=1을 명시하세요."
    )


@asynccontextmanager
async def lifespan(app: FastAPI):
    check_token_configured()
    store.get_pool()
    if os.environ.get("COLLECTOR_INIT_SCHEMA", "true").lower() in ("1", "true", "yes"):
        store.init_schema()

    try:
        removed = store.purge_retired_datasets()
        if removed:
            logger.info("없앤 기능의 누적 데이터 %d행 정리", removed)
    except Exception as exc:  # noqa: BLE001
        logger.warning("없앤 기능의 데이터 정리 실패: %s", exc)

    # 비정상 종료로 'running'에 남아 있던 기록을 먼저 정리합니다.
    try:
        store.mark_stale_runs_interrupted()
    except Exception as exc:  # noqa: BLE001
        logger.warning("오래된 실행 기록 정리 실패: %s", exc)

    # 거래일 판정이 공휴일을 알도록 저장된 공휴일을 올립니다(없으면 주말만 건너뜀).
    loaded = krcalendar.load_from_store()
    logger.info("공휴일 %d일 로드 — 거래일 판정에 사용", loaded)

    global scheduler
    if settings.scheduler_enabled():
        scheduler = BackgroundScheduler(timezone="UTC")
        for group in ("fast", "slow", "weekly"):
            seconds = settings.interval_seconds(group)
            scheduler.add_job(
                _run_group_job,
                "interval",
                seconds=seconds,
                args=[group],
                id=f"collect-{group}",
                max_instances=1,
                coalesce=True,
                # 기동 직후 한 번씩 돌립니다. 싼 것부터(fast → slow → weekly)
                # 처리해야 가장 자주 보는 데이터가 먼저 채워집니다.
                next_run_time=datetime.now(tz=ZoneInfo("UTC")),
            )
            logger.info("스케줄 등록: %s 주기 %d초", group, seconds)
        scheduler.start()
    else:
        logger.info("스케줄러 비활성화 — REST 요청으로만 수집합니다.")

    yield

    if scheduler:
        scheduler.shutdown(wait=False)
    store.close_pool()


app = FastAPI(
    title="Local Macro Dashboard — Collector",
    version="2.0.0",
    description="외부 시장 데이터 수집기 (PostgreSQL 적재 + 실시간 조회 API)",
    lifespan=lifespan,
)

# 브라우저에서 온 교차 출처 요청은 토큰 유무와 관계없이 막습니다(webguard 설명 참고).
app.add_middleware(webguard.CrossSiteRequestGuard)
# 모르는 Host 헤더는 400 — DNS 리바인딩으로 '같은 출처'가 된 브라우저 요청을 막습니다.
app.add_middleware(TrustedHostMiddleware, allowed_hosts=ALLOWED_HOSTS)


def _run_group_job(group: str) -> None:
    try:
        tasks.run_group(group)
    except Exception as exc:  # noqa: BLE001
        logger.exception("스케줄 수집 실패 (%s): %s", group, exc)


def _check_token(token: str | None) -> None:
    """
    서비스 토큰을 검사합니다. 토큰을 설정하지 않았으면(로컬 기본값) 통과시킵니다.

    주의사항 — 비교는 hmac.compare_digest로 합니다. ``!=``는 앞에서부터 비교하다
    다른 글자에서 멈추므로 응답 시간 차이로 토큰을 한 글자씩 맞혀 볼 수 있습니다.
    """
    if API_TOKEN and not hmac.compare_digest(
        (token or "").encode("utf-8"), API_TOKEN.encode("utf-8")
    ):
        raise HTTPException(status_code=401, detail="유효하지 않은 서비스 토큰입니다.")


def _parse_date(value: str | None) -> date | None:
    """
    'YYYY-MM-DD'를 날짜로 바꿉니다.

    형식이 틀리면 ValueError가 그대로 올라가 500(서버 오류)이 됐습니다.
    잘못 보낸 쪽이 무엇을 고쳐야 하는지 알 수 있게 400으로 답합니다.
    """
    if not value:
        return None
    try:
        return date.fromisoformat(value)
    except ValueError:
        raise HTTPException(
            status_code=400,
            detail=f"날짜 형식이 올바르지 않습니다: {value} (예: 2026-09-18)",
        ) from None


# ==============================================================================
# 상태
# ==============================================================================
@app.get("/health")
def health() -> dict:
    return {"status": "ok", "schedulerEnabled": settings.scheduler_enabled()}


@app.get("/status")
def status() -> dict:
    """구버전 `collector.py --status`에 해당합니다."""
    stats = store.store_stats(task_names=[task.name for task in tasks.ALL_TASKS])
    stats["keys"] = {
        "fred": bool(settings.fred_key()),
        "krx": bool(settings.krx_key()),
        "kis": kis_service.has_credentials(),
        "ls": ls_service.has_credentials(),
        "toss": toss_service.has_credentials(),
        "dataGoKr": bool(settings.data_go_kr_key()),
        "dart": bool(settings.dart_key()),
    }
    stats["intervals"] = {
        group: settings.interval_seconds(group)
        for group in ("fast", "slow", "weekly")
    }
    return stats


@app.get("/tasks")
def list_tasks() -> dict:
    return {
        "tasks": [
            {"name": t.name, "speed": t.speed, "description": t.description}
            for t in tasks.ALL_TASKS
        ]
    }


@app.get("/task-history")
def task_history(
    task: str | None = None,
    limit: int = Query(40, ge=1, le=200),
) -> dict:
    rows = store.read_task_history(task, limit)
    return {"history": [store._serialize_task(row) for row in rows]}


# ==============================================================================
# 수집 실행
# ==============================================================================
@app.post("/collect")
def collect(
    group: str = Query("all", pattern="^(fast|slow|weekly|all)$"),
    wait: bool = Query(True, description="false면 백그라운드로 실행하고 즉시 응답"),
    background: BackgroundTasks = None,          # type: ignore[assignment]
    x_service_token: str | None = Header(default=None),
) -> dict:
    _check_token(x_service_token)

    if wait:
        return tasks.run_group(group)

    background.add_task(tasks.run_group, group)
    return {"group": group, "accepted": True}


@app.post("/collect/task/{task_name}")
def collect_task(
    task_name: str,
    wait: bool = Query(True, description="false면 백그라운드로 실행하고 즉시 응답"),
    background: BackgroundTasks = None,          # type: ignore[assignment]
    x_service_token: str | None = Header(default=None),
) -> dict:
    """
    태스크 1건을 실행합니다.

    wait=false는 **화면이 수집을 기다리지 않게** 하려고 있습니다. 저장본이
    이미 있는데 조금 오래된 경우, 백엔드가 이 호출이 끝나기를 기다리면 화면이
    그만큼 멈춥니다. 실제로 sec_13f 한 건에 31.8초, fred_series에 11.5초
    동안 페이지가 붙잡혔습니다.
    """
    _check_token(x_service_token)
    if task_name not in tasks.TASKS_BY_NAME:
        raise HTTPException(
            status_code=404,
            detail=f"알 수 없는 태스크: {task_name} (가능: {sorted(tasks.TASKS_BY_NAME)})",
        )

    if not wait:
        background.add_task(tasks.run_group, task_name=task_name)
        return {"task": task_name, "accepted": True}

    return tasks.run_group(task_name=task_name)


@app.post("/refresh")
def refresh(
    scope: str = "global",
    x_service_token: str | None = Header(default=None),
) -> dict:
    """
    수동 새로고침 기준 시각을 갱신합니다.

    저장본을 지우지 않습니다 — 수집이 실패하면 보여 줄 값이 아예 없어지기
    때문입니다. "이 시각 이전 저장본은 낡은 것으로 본다"는 기준만 세웁니다.
    """
    _check_token(x_service_token)
    requested_at = store.request_refresh(scope)
    return {"scope": scope, "requestedAt": requested_at.isoformat()}


@app.post("/maintenance/purge")
def purge(
    days: int = Query(400, ge=30),
    x_service_token: str | None = Header(default=None),
) -> dict:
    _check_token(x_service_token)
    return store.purge_older_than(days)


# ==============================================================================
# 실시간 조회 (백엔드가 auto 모드에서 호출)
# ==============================================================================
@app.get("/live/radar")
def live_radar(
    market: str = "KOSPI",
    investor: str = "외국인",
    tradeType: str = "순매수",
    topN: int = Query(30, ge=5, le=100),
    intervalType: str = Query("TODAY", pattern="^(TODAY|DAYS_5|DAYS_20)$"),
    targetDate: str | None = None,
    x_service_token: str | None = Header(default=None),
) -> dict:
    """
    수급 랭킹을 지금 수집합니다 (폴백 체인 전체를 탑니다).

    저장도 함께 합니다. 화면이 기다린 수집 결과를 버리면 다음 사용자가 또
    기다리게 되기 때문입니다.
    """
    _check_token(x_service_token)
    market = _normalize_market(market)
    requested = _parse_date(targetDate)
    day = requested or datetime.now(KST).date()
    result = radar_service.collect_radar_ranking(
        day, market, investor, tradeType, topN, intervalType
    )

    # 저장은 스케줄러와 같은 조건(오늘·상위 30)일 때만 합니다. 백엔드는 topN≠30이나
    # 과거 날짜 요청이면 저장본을 건너뛰지만, 그 결과를 기본 이름에 저장하면 다음
    # 15분 동안 5행짜리나 과거 랭킹이 '현재 랭킹'으로 나가고 이력에도 쌓입니다.
    is_default_query = requested is None and topN == RADAR_DEFAULT_TOP_N
    if result.get("rows") and is_default_query:
        store.put_snapshot(
            catalog.snap_radar_scanner(market, investor, tradeType, intervalType),
            result,
        )
        if not result.get("isHistorical"):
            radar_service.accumulate_history(
                result["rows"], market, investor, tradeType, intervalType
            )
    return result


RADAR_DEFAULT_TOP_N = 30
RADAR_MARKETS = ("KOSPI", "KOSDAQ")


def _normalize_market(market: str) -> str:
    """
    시장 이름을 KOSPI/KOSDAQ로 맞춥니다. 소문자나 한글이 그대로 저장본 이름·이력 payload에
    들어가면 같은 시장이 다른 키로 갈라져 이력 조회가 빗나갑니다.
    """
    text = (market or "").strip().upper()
    if "코스닥" in text or text == "KOSDAQ":
        return "KOSDAQ"
    if "코스피" in text or text == "KOSPI":
        return "KOSPI"
    raise HTTPException(status_code=400, detail=f"market은 {' 또는 '.join(RADAR_MARKETS)}여야 합니다: {market!r}")


_TICKER_SYMBOL = re.compile(r"^[A-Za-z0-9^=.\-]{1,24}$")


@app.get("/live/ticker/{symbol:path}")
def live_ticker(
    symbol: str,
    period: str = "1mo",
    x_service_token: str | None = Header(default=None),
) -> dict:
    """저장 대상이 아닌 개별 티커 차트(단일 지표 조회 화면)용. 심볼은 야후 형식(^VIX, ZT=F, BRK-B)만."""
    _check_token(x_service_token)
    if not _TICKER_SYMBOL.match(symbol):
        raise HTTPException(status_code=400, detail="심볼 형식이 올바르지 않습니다 (영문·숫자·^=.- 24자 이내).")
    return market_service.collect_ticker(symbol, period)


@app.get("/live/daum-intraday")
def live_daum_intraday(
    minutes: int = Query(30, ge=5, le=180),
    x_service_token: str | None = Header(default=None),
) -> dict:
    """장중 선물 수급 가속도. 1분 단위로 변하므로 저장하지 않습니다."""
    _check_token(x_service_token)
    return krx_service.collect_daum_intraday_acceleration(minutes)


@app.get("/live/radar-history-dates")
def radar_history_dates(
    x_service_token: str | None = Header(default=None),
) -> dict:
    _check_token(x_service_token)
    return {"dates": store.list_observation_dates(catalog.OBS_RADAR)}


@app.get("/live/radar-history")
def radar_history(
    market: str | None = None,
    investor: str | None = None,
    tradeType: str | None = None,
    obsDate: str | None = None,
    startDate: str | None = None,
    x_service_token: str | None = Header(default=None),
) -> dict:
    _check_token(x_service_token)
    filters: dict[str, str] = {}
    if market:
        filters["market"] = market
    if investor:
        filters["investor"] = investor
    if tradeType:
        filters["tradeType"] = tradeType

    return {
        "rows": store.read_observations(
            catalog.OBS_RADAR,
            obs_date=obsDate,
            start_date=startDate,
            filters=filters or None,
        )
    }


# ==============================================================================
# 교차 검증용 읽기 (판정은 백엔드가 합니다) — 읽는 코드는 verification.py
# ==============================================================================
@app.get("/verify/readings")
def verification_readings(
    market: str = "KOSPI",
    investor: str = "외국인",
    tradeType: str = "순매수",
    x_service_token: str | None = Header(default=None),
) -> dict:
    """같은 수치를 서로 다른 출처에서 읽어 원자료 그대로 돌려줍니다(verification.readings)."""
    _check_token(x_service_token)
    return verification.readings(market, investor, tradeType)


# ==============================================================================
# 연결 진단
# ==============================================================================
@app.get("/diagnostics/connections")
def diagnostics(
    x_service_token: str | None = Header(default=None),
) -> dict:
    """
    수급 레이더 폴백 체인의 소스별 연결 상태 (KIS·LS·Daum·Naver·토스·PyKrx).

    진단은 **화면이 실제로 쓰는 경로**를 그대로 호출합니다. 진단이 다른
    경로를 보면 "진단은 정상인데 화면은 빈" 상황을 설명할 수 없습니다.
    """
    _check_token(x_service_token)
    return {
        "checkedAt": datetime.now(KST).isoformat(),
        "sources": {
            "kis": kis_service.test_connection(),
            "ls": ls_service.test_connection(),
            "daum": radar_service.test_daum_connection(),
            "naver": radar_service.test_naver_connection(),
            "toss": radar_service.test_toss_connection(),
            "pykrx": radar_service.test_pykrx_connection(),
        },
    }


@app.get("/diagnostics/public-apis")
def public_api_diagnostics(
    x_service_token: str | None = Header(default=None),
) -> dict:
    """
    국내 공공 API(공공데이터포털·DART) 연결 진단. API마다 1회씩 호출합니다.

    결과에 키는 들어 있지 않습니다(설정 여부만 true/false).
    """
    _check_token(x_service_token)
    from .services import publicprobe
    return publicprobe.run()


@app.get("/diagnostics/toss")
def toss_diagnostics(
    x_service_token: str | None = Header(default=None),
) -> dict:
    _check_token(x_service_token)
    return toss_service.test_connection()


@app.get("/toss/exchange-rate")
def toss_exchange_rate(
    base: str = "USD",
    quote: str = "KRW",
    x_service_token: str | None = Header(default=None),
) -> dict:
    _check_token(x_service_token)
    return toss_service.get_exchange_rate(base, quote)


@app.get("/toss/indices")
def toss_indices(
    symbols: str = Query(..., description="쉼표로 구분"),
    x_service_token: str | None = Header(default=None),
) -> dict:
    _check_token(x_service_token)
    return toss_service.get_index_prices([s.strip() for s in symbols.split(",") if s.strip()])


# ==============================================================================
# 카탈로그 (백엔드/프런트가 같은 정의를 쓰도록 노출)
# ==============================================================================
@app.get("/catalog")
def catalog_info() -> dict[str, Any]:
    return {
        "macroCategories": indicators.MACRO_CATEGORIES,
        "institutions": indicators.INSTITUTIONS,
        "sectorEtfs": indicators.SECTOR_ETFS,
        "assetClassEtfs": indicators.ASSET_CLASS_ETFS,
        "cotAssets": indicators.COT_ASSETS,
        "fredSeries": {
            "base": list(indicators.FRED_BASE_SERIES),
            "advanced": list(indicators.FRED_ADVANCED_SERIES),
        },
        "snapshots": {
            "macro": catalog.SNAP_MACRO_COLLECTED,
            "scraper": catalog.SNAP_SCRAPER_MARKETS,
            "liquidity": catalog.SNAP_FED_LIQUIDITY,
            "krxFutures": catalog.SNAP_KRX_FUTURES,
            "sector": catalog.SNAP_SECTOR_HISTORY,
            "cot": catalog.SNAP_COT_HISTORY,
        },
    }
