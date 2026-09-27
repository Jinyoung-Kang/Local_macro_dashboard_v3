"""
app/services/radar.py
국내 수급 레이더 — 무중단(fail-safe) 폴백 체인.

    KIS(장중 가집계) → Daum(API) → Naver(렌더링) → LS(OPEN API) → 토스(공식) → PyKrx → 누적 이력

앞쪽이 성공하면 뒤는 호출되지 않습니다.

[반드시 알아야 할 제약]
- Naver·Daum·KIS는 **과거 날짜 조회를 지원하지 않습니다.** "가장 최근에 끝난
  거래일"만 줍니다. 그래서 과거 조회는 PyKrx 하나에 기대고 있었는데, PyKrx는
  KRX 웹을 비공식으로 긁는 라이브러리라 KRX가 차단하면 통째로 멈춥니다.
- 그때 빈 화면을 보여주는 대신, **수집기가 쌓아 온 우리 자신의 이력**
  (observations)에서 가장 가까운 이전 거래일 랭킹을 꺼내 씁니다. 이 경우
  source에 "어느 날짜의 저장본인지"를 반드시 적어, 화면이 "지금 시점의
  수급이 아니다"라고 경고할 수 있게 합니다.
- 즉 **수집기를 꾸준히 돌리는 것이 곧 백업입니다.**

[종목코드는 항상 문자열]
"069500"을 숫자로 다루면 앞자리 0이 사라져 69500이 되고, 이후 모든 조회가
실패합니다. 이 파일은 코드를 문자열로만 다룹니다.
"""
from __future__ import annotations

import contextlib
import io
import logging
import os
import re
from datetime import date, datetime, time, timedelta
from zoneinfo import ZoneInfo

from bs4 import BeautifulSoup

from .. import catalog, store
from .. import kst
from ..http import get_session
from . import kis, ls, toss

logger = logging.getLogger(__name__)

KST = ZoneInfo("Asia/Seoul")

DAUM_RANKING_URL = "https://finance.daum.net/api/trend/investor_purchase/"
# 마지막 Naver 수집이 왜 비었는지. 진단 화면이 "빈 결과입니다"에서 끝나지
# 않도록, 실패한 단계를 그대로 들고 있습니다.
_NAVER_LAST_REASON: dict[str, str | None] = {"value": None}
_TOSS_LAST_REASON: dict[str, str | None] = {"value": None}

NAVER_RANKING_URL = "https://finance.naver.com/sise/sise_deal_rank_iframe.naver"

DAUM_INVESTOR_TYPES = {"외국인": "FOREIGN", "기관": "INSTITUTION"}

NAVER_INVESTOR_CODES = {
    "외국인": "9000", "기관": "7000", "개인": "8000",
    "연기금": "6000", "금융투자": "2000", "투신": "3000",
}

PYKRX_INVESTORS = {
    "외국인": "외국인", "기관": "기관합계", "연기금": "연기금",
    "금융투자": "금융투자", "투신": "투신", "개인": "개인",
}

INTERVAL_LABELS = {"TODAY": "당일", "DAYS_5": "5거래일", "DAYS_20": "20거래일"}

# pykrx는 선택 의존성입니다 (KRX가 차단하면 어차피 동작하지 않습니다).
#
# pykrx 1.2.x는 import 시점에 `print()`로 로그인 상태를 뱉습니다.
#
#     KRX 로그인 실패: KRX_ID 또는 KRX_PW 환경 변수가 설정되지 않았습니다.
#
# 수집기 로그의 첫 줄이 이것이면 우리 앱이 뭔가 실패한 것처럼 보입니다.
# 사실은 "선택 기능인 KRX 로그인을 쓰지 않는다"는 뜻일 뿐입니다. 삼켜 버리지
# 않고 **우리 로거로 옮겨** 뜻이 통하게 적습니다.
_pykrx_import_output = io.StringIO()
try:
    with contextlib.redirect_stdout(_pykrx_import_output):
        from pykrx import stock as pykrx_stock
    PYKRX_AVAILABLE = True
except Exception:  # noqa: BLE001
    pykrx_stock = None
    PYKRX_AVAILABLE = False

PYKRX_LOGGED_IN = bool(os.environ.get("KRX_ID") and os.environ.get("KRX_PW"))
if PYKRX_AVAILABLE and not PYKRX_LOGGED_IN:
    logger.info(
        "pykrx를 비로그인으로 씁니다. KRX가 비로그인 조회를 막으면 수급 레이더의 "
        "pykrx 단계만 건너뜁니다(KIS·Daum·Naver·누적 이력으로 대체). "
        "KRX 홈페이지 계정이 있으면 .env에 KRX_ID·KRX_PW를 넣어 쓸 수 있습니다."
    )


# ==============================================================================
# 1. 거래일 계산
# ==============================================================================
def latest_completed_session(now: datetime | None = None) -> str:
    """
    지금 이 시점에 Naver/Daum이 '현재 데이터'로 보여주는 거래일(YYYYMMDD).

    - 평일 09:00 이후: 오늘
    - 그 외(장 시작 전·주말): 가장 최근 평일
    """
    now = now or datetime.now(KST)
    if now.weekday() < 5 and now.time() >= time(9, 0):
        return now.strftime("%Y%m%d")

    day = now.date() - timedelta(days=1)
    while day.weekday() >= 5:
        day -= timedelta(days=1)
    return day.strftime("%Y%m%d")


def is_regular_session(now: datetime | None = None) -> bool:
    now = now or datetime.now(KST)
    return now.weekday() < 5 and time(9, 0) <= now.time() < time(15, 30)


# ==============================================================================
# 2. 폴백 체인
# ==============================================================================
def collect_radar_ranking(
    target_day: date,
    market: str = "KOSPI",
    investor: str = "외국인",
    trade_type: str = "순매수",
    top_n: int = 30,
    interval_type: str = "TODAY",
) -> dict:
    """
    수급 랭킹을 수집합니다.

    반환 계약(JSON):
    {
      "source": "KIS 장중 가집계 / ...",   # 어느 출처가 실제로 성공했는지
      "sourceKind": "kis|daum|naver|ls|pykrx|history|none",
      "isHistorical": bool,                # 누적 이력 대체 여부 (화면 경고용)
      "rows": [{"rank","code","name","price","changePct","netAmountEok",...}]
    }
    """
    now = datetime.now(KST)
    today_str = now.strftime("%Y%m%d")
    latest_str = latest_completed_session(now)
    live = is_regular_session(now)

    if interval_type != "TODAY":
        rows = fetch_daum_ranking(
            target_day.strftime("%Y%m%d"), market, investor, trade_type,
            top_n, interval_type,
        )
        if rows:
            return _result(rows, "daum")
        logger.warning(
            "Daum 기간별(%s) 조회 실패. 당일 데이터로 자동 전환합니다.", interval_type
        )

    cursor = target_day
    for _ in range(7):     # 최대 7영업일을 거슬러 올라갑니다
        date_str = cursor.strftime("%Y%m%d")
        is_today = date_str == today_str
        can_use_current_only_sources = (
            is_today or date_str == latest_str
        )

        if is_today and live:
            rows = kis.fetch_deal_ranking(date_str, market, investor, trade_type, top_n)
            if rows:
                return _result(rows, "kis")

        if can_use_current_only_sources:
            rows = fetch_daum_ranking(
                date_str, market, investor, trade_type, top_n, "TODAY"
            )
            if rows:
                return _result(rows, "daum")

            rows = fetch_naver_ranking(date_str, market, investor, trade_type, top_n)
            if rows:
                return _result(rows, "naver")

            # LS는 KIS/Daum/Naver가 모두 실패했을 때만 동원됩니다.
            # (구버전에서는 이 함수가 정의만 되고 **한 번도 호출되지 않아**,
            #  LS 키를 정확히 넣어도 화면이 달라지지 않았습니다.)
            rows = ls.fetch_deal_ranking(date_str, market, investor, trade_type, top_n)
            if rows:
                return _result(rows, "ls")

            # 토스는 투자자별 순위 API가 없어 "거래대금 상위 100종목 안의 순위"입니다.
            # 시장 전체 순위를 주는 앞 단계가 모두 실패했을 때만 씁니다(저장본만 읽음, 호출 없음).
            rows = fetch_toss_ranking(market, investor, trade_type, top_n)
            if rows:
                return _result(rows, "toss")
        else:
            logger.info(
                "과거 날짜(%s) 조회: 날짜 미지원 소스를 건너뛰고 PyKrx만 사용합니다.",
                date_str,
            )

        rows = fetch_pykrx_ranking(date_str, market, investor, trade_type, top_n)
        if rows:
            return _result(rows, "pykrx")

        cursor -= timedelta(days=1)

    history = read_from_history(target_day, market, investor, trade_type, top_n)
    if history:
        logger.info(
            "수급 레이더: 외부 소스가 모두 실패해 누적 이력으로 대체합니다 (%s행)",
            len(history["rows"]),
        )
        return history

    reasons = diagnose_sources(investor)
    logger.error(
        "수급 레이더 완전 실패: 날짜=%s, 시장=%s, 투자주체=%s, 방향=%s "
        "(pykrx=%s, 누적 이력에도 없음) — %s",
        target_day, market, investor, trade_type, PYKRX_AVAILABLE,
        " / ".join(reasons) or "사유 불명",
    )
    return {
        "source": None,
        "sourceKind": "none",
        "isHistorical": False,
        "rows": [],
        # 왜 실패했는지 화면까지 전달합니다.
        #
        # 예전에는 화면에 "수급 데이터를 얻지 못했습니다. 수집기 상태를
        # 확인하세요."만 떴습니다. 수집기는 멀쩡한데(다른 조합은 잘 나옴)
        # 그쪽을 보게 만들어, 실제 원인(소스별 제약·차단)에서 멀어졌습니다.
        "reasons": reasons,
    }


def _object_particle(word: str) -> str:
    """
    '을/를'을 고릅니다.

    화면에 그대로 나가는 문장입니다. "'개인'를 제공하지 않습니다"처럼 조사가
    틀리면, 읽는 사람은 문장이 아니라 그 어색함을 먼저 봅니다.
    한글 음절은 (코드포인트 - 0xAC00) % 28 로 받침 유무를 알 수 있습니다.
    """
    if not word:
        return "를"
    last = word[-1]
    if not ("가" <= last <= "힣"):
        return "를"
    has_final_consonant = (ord(last) - 0xAC00) % 28 != 0
    return "을" if has_final_consonant else "를"


def diagnose_sources(investor: str) -> list[str]:
    """
    폴백 체인이 전부 실패했을 때, 소스별로 '왜 못 줬는지'를 사람 말로 적습니다.

    호출 하나하나를 추적하는 대신 지금 상태를 보고 판정합니다. 실패 원인이
    대개 '이 소스는 이 투자주체를 원래 안 준다', '키가 없다', '서비스가
    없어졌다'처럼 **호출해 보지 않아도 아는 것**이기 때문입니다.
    """
    reasons: list[str] = []

    if investor not in DAUM_INVESTOR_TYPES:
        reasons.append(
            f"Daum은 '{investor}'{_object_particle(investor)} 제공하지 않습니다 "
            "(외국인·기관만)"
        )

    naver_reason = _NAVER_LAST_REASON["value"]
    if naver_reason and "410" in naver_reason:
        reasons.append(
            "Naver는 이 페이지를 폐지했습니다 (HTTP 410 — stock.naver.com으로 이전)"
        )
    elif naver_reason:
        reasons.append(f"Naver: {naver_reason}")

    toss_reason = _TOSS_LAST_REASON["value"]
    if toss_reason:
        reasons.append(f"토스: {toss_reason}")

    if not ls.has_credentials():
        reasons.append("LS는 키가 없어 건너뜁니다 (.env의 LS_APP_KEY/SECRET)")
    else:
        reasons.append(
            "LS는 인증이 거절됐습니다 — Open API 사용등록이 필요할 수 있습니다"
        )

    if not PYKRX_AVAILABLE:
        reasons.append("PyKrx가 설치돼 있지 않습니다")
    else:
        reasons.append("KRX(pykrx)가 데이터 대신 차단 응답을 주고 있습니다")

    return reasons


def _result(rows: list[dict], kind: str) -> dict:
    return {
        "source": rows[0].get("source") if rows else None,
        "sourceKind": kind,
        "isHistorical": False,
        "rows": rows,
    }


# ==============================================================================
# 3. 개별 소스
# ==============================================================================
def fetch_daum_ranking(
    target_date: str,
    market: str,
    investor: str,
    trade_type: str,
    top_n: int,
    interval_type: str = "TODAY",
) -> list[dict]:
    """
    Daum 금융 investor_purchase API (시장 전체 Top N).

    ⚠️ 기관합계의 기간별 누적(5일/20일) 응답은 검증되지 않았습니다. 잘못된
    기간 값을 보여주지 않도록 기관은 당일만 허용합니다.
    """
    investor_type = DAUM_INVESTOR_TYPES.get(investor)
    if investor_type is None:
        logger.warning("Daum 미지원 투자주체: %s (지원: 외국인, 기관)", investor)
        return []

    if interval_type not in INTERVAL_LABELS:
        interval_type = "TODAY"
    if investor == "기관" and interval_type != "TODAY":
        logger.warning("Daum 기관합계는 당일만 지원합니다. TODAY로 전환합니다.")
        interval_type = "TODAY"

    market_param = "KOSPI" if ("KOSPI" in market.upper() or "코스피" in market) else "KOSDAQ"

    params = {
        "buyFieldName": "straightPurchasePrice",
        "buyOrder": "desc",
        "sellFieldName": "straightPurchasePrice",
        "sellOrder": "asc",
        "limit": top_n,
        "market": market_param,
        "investorType": investor_type,
    }
    if interval_type != "TODAY":
        params["intervalType"] = interval_type

    headers = {
        "Accept": "application/json, text/javascript, */*; q=0.01",
        "Referer": (
            "https://finance.daum.net/domestic/influential_investors"
            f"?market={market_param}"
        ),
        "X-Requested-With": "XMLHttpRequest",
    }

    try:
        res = get_session().get(
            DAUM_RANKING_URL, headers=headers, params=params, timeout=10
        )
    except Exception as exc:  # noqa: BLE001
        logger.warning("Daum 랭킹 통신 실패 (%s/%s): %s", market_param, investor, exc)
        return []

    if res.status_code != 200:
        logger.warning("Daum 랭킹 HTTP 실패: %s", res.status_code)
        return []

    try:
        payload = res.json()
    except ValueError as exc:
        logger.warning("Daum 랭킹 JSON 해석 실패: %s", exc)
        return []

    data = payload.get("data") or {}
    rows = data.get("BUY" if trade_type == "순매수" else "SELL") or []
    if not rows:
        logger.warning(
            "Daum 랭킹 빈 결과: market=%s, investor=%s, direction=%s, interval=%s",
            market_param, investor, trade_type, interval_type,
        )
        return []

    from_date = payload.get("fromDate") or ""
    to_date = payload.get("toDate") or target_date
    period = (
        to_date if interval_type == "TODAY"
        else (f"{from_date}~{to_date}" if from_date and to_date else target_date)
    )
    source = (
        f"Daum API ({investor}, {INTERVAL_LABELS.get(interval_type, interval_type)}, {period})"
    )
    collected_at = kst.stamp()

    records: list[dict] = []
    for row in rows[:top_n]:
        if not isinstance(row, dict):
            continue

        raw_code = str(row.get("symbolCode", "")).strip()
        code = raw_code[1:] if raw_code.startswith("A") else raw_code
        name = str(row.get("name", "")).strip()
        if not code or not name:
            continue

        price = _to_float(row.get("tradePrice"))
        # Daum changeRate는 0.0238 = 2.38% 형태의 소수 비율입니다.
        change_pct = round(_to_float(row.get("changeRate")) * 100.0, 2)
        net_eok = round(_to_float(row.get("straightPurchasePrice")) / 100_000_000.0, 1)

        # 응답 부호가 방향과 어긋나는 경우를 방어합니다.
        if trade_type == "순매도" and net_eok > 0:
            net_eok = -abs(net_eok)
        if trade_type == "순매수" and net_eok < 0:
            net_eok = abs(net_eok)

        records.append({
            "code": code,
            "name": name,
            "price": price,
            "changePct": change_pct,
            "netAmountEok": net_eok,
            "source": source,
            "collectedAt": collected_at,
        })

    return _rank(records, trade_type, top_n)


def fetch_naver_ranking(
    target_date: str,
    market: str,
    investor: str,
    trade_type: str,
    top_n: int,
) -> list[dict]:
    """
    Naver 투자자별 매매상위 (iframe 페이지).

    ⚠️ 이 페이지는 "현재 시점"의 최신 순위만 제공합니다. 과거 날짜 조회에
    쓰면 안 됩니다(호출부가 그 조건을 지킵니다).

    구버전은 이 페이지를 헤드리스 Chromium으로 렌더링해 읽었습니다. iframe
    페이지 자체는 서버가 완성된 HTML을 주므로 렌더링 없이 파싱해도 같은
    결과가 나옵니다. 브라우저 의존성이 사라져 배포가 단순해집니다.
    """
    investor_code = NAVER_INVESTOR_CODES.get(investor)
    if investor_code is None:
        logger.warning("Naver 미지원 투자주체: %s", investor)
        return []

    params = {
        "sosok": "01" if ("KOSPI" in market.upper() or "코스피" in market) else "02",
        "investor_gubun": investor_code,
        "type": "buy" if trade_type == "순매수" else "sell",
    }

    try:
        res = get_session().get(
            NAVER_RANKING_URL,
            params=params,
            # iframe 페이지는 부모 문서에서 불리는 것을 전제로 합니다. Referer가
            # 없으면 네이버가 빈 페이지를 주는 경우가 있어 함께 보냅니다.
            headers={"Referer": "https://finance.naver.com/sise/sise_deal_rank.naver"},
            timeout=10,
        )
        res.encoding = res.apparent_encoding or "euc-kr"
        html = res.text
    except Exception as exc:  # noqa: BLE001
        logger.warning("Naver 랭킹 수집 실패: %s", exc)
        _NAVER_LAST_REASON["value"] = f"{type(exc).__name__}: {str(exc)[:120]}"
        return []

    if res.status_code != 200:
        _NAVER_LAST_REASON["value"] = f"HTTP {res.status_code}"
        return []

    soup = BeautifulSoup(html, "html.parser")
    tables = soup.find_all("table", {"class": "type_1"}) or soup.find_all("table")
    if not tables:
        # "표가 아예 없다"와 "표는 있는데 행이 없다"는 원인이 다릅니다.
        # 전자는 차단·JS 렌더링 요구, 후자는 휴장·구조 변경 쪽입니다.
        _NAVER_LAST_REASON["value"] = (
            f"응답에 표가 없습니다 (본문 {len(html):,}자). 차단이거나 "
            "이 페이지가 JS 렌더링을 요구하게 바뀐 경우입니다."
        )
        return []

    table = max(
        tables,
        key=lambda t: sum(
            1 for row in t.find_all("tr")
            if len(row.find_all("td")) >= 4 and row.find("a")
        ),
    )

    collected_at = kst.stamp()
    records: list[dict] = []

    for row in table.find_all("tr"):
        cols = row.find_all("td")
        if len(cols) < 4:
            continue
        anchor = cols[1].find("a")
        if not anchor:
            continue
        match = re.search(r"code=(\d+)", anchor.get("href", ""))
        if not match:
            continue

        price = _cell_to_float(cols[2])
        change_pct = _cell_to_float(cols[4]) if len(cols) > 4 else 0.0
        raw_amount = _cell_to_float(cols[7]) if len(cols) >= 8 else _cell_to_float(cols[3])
        net_eok = round(raw_amount / 100.0, 1) if abs(raw_amount) > 1000 else round(raw_amount, 1)
        if trade_type == "순매도":
            net_eok = -abs(net_eok)

        records.append({
            "code": match.group(1),
            "name": anchor.text.strip(),
            "price": price,
            "changePct": change_pct,
            "netAmountEok": net_eok,
            "source": f"Naver 투자자별 매매상위 ({target_date})",
            "collectedAt": collected_at,
        })
        if len(records) >= top_n:
            break

    if records:
        _NAVER_LAST_REASON["value"] = None
    else:
        _NAVER_LAST_REASON["value"] = (
            f"표 {len(tables)}개는 받았지만 종목 행이 없습니다 "
            "(휴장일이거나 표 구조가 바뀐 경우입니다)."
        )

    return _rank(records, trade_type, top_n)


# 토스 투자자 매매 레코드에서 각 투자주체가 있는 자리. 기관 세부 셋은 breakdown 안에 있습니다.
TOSS_INVESTOR_PATHS = {
    "외국인": ("investors", "foreigner"),
    "기관": ("investors", "institution"),
    "개인": ("investors", "individual"),
    "연기금": ("breakdown", "pensionFund"),
    "금융투자": ("breakdown", "financialInvestment"),
    "투신": ("breakdown", "trust"),
}
TOSS_UNIVERSE_MAX_AGE_HOURS = 26


def _toss_net(record: dict, investor: str) -> int | None:
    group, key = TOSS_INVESTOR_PATHS[investor]
    node = (record.get(group) or {}).get(key) if isinstance(record.get(group), dict) else None
    return node.get("net") if isinstance(node, dict) else None


def fetch_toss_ranking(market: str, investor: str, trade_type: str, top_n: int) -> list[dict]:
    """
    토스증권 공식 데이터로 만든 수급 순위 — **거래대금 상위 N종목 안에서만**.

    저장본(``toss_radar_universe`` 태스크가 1시간마다 받음)만 읽고 외부를 부르지 않습니다.

    규칙
      - 투자주체마다 값이 있는 가장 최근 날짜를 고르고, 종목들이 가장 많이 가진 날짜 하나로
        맞춥니다(당일 잠정치에는 개인·기관 세부가 없어 전 거래일 확정치가 됩니다).
        날짜가 다른 종목을 한 표에 섞지 않습니다.
      - 금액(억)은 **순매수 주식 수 × 현재가**로 추정한 값입니다. 토스 종목 매매동향은
        거래량(주)만 주고 거래대금은 주지 않습니다.
      - 출처 문구에 "거래대금 상위 N종목 한정"과 기준일·추정 여부를 반드시 적습니다.
    """
    _TOSS_LAST_REASON["value"] = None
    if investor not in TOSS_INVESTOR_PATHS:
        _TOSS_LAST_REASON["value"] = f"'{investor}'{_object_particle(investor)} 지원하지 않습니다"
        return []
    snap = store.read_snapshot(catalog.SNAP_TOSS_RADAR_UNIVERSE)
    if not snap or not snap.payload:
        _TOSS_LAST_REASON["value"] = (
            "저장본이 없습니다 (toss_radar_universe 태스크 — 키 미설정이거나 아직 수집 전)"
            if toss.has_credentials() else "키가 없어 건너뜁니다 (.env의 TOSS_CLIENT_ID/SECRET)"
        )
        return []
    if snap.age_seconds > TOSS_UNIVERSE_MAX_AGE_HOURS * 3600:
        _TOSS_LAST_REASON["value"] = f"저장본이 {int(snap.age_seconds // 3600)}시간 지나 쓰지 않습니다"
        return []

    picks: list[tuple[dict, dict, int]] = []
    for stock in snap.payload.get("stocks") or []:
        if stock.get("market") != market:
            continue
        for record in stock.get("records") or []:
            net = _toss_net(record, investor)
            if net is not None:
                picks.append((stock, record, net))
                break
    if not picks:
        _TOSS_LAST_REASON["value"] = f"{market} 종목에 '{investor}' 값이 없습니다"
        return []

    dates = [record.get("date") for _, record, _ in picks]
    target = max(set(dates), key=lambda d: (dates.count(d), d or ""))
    universe = int(snap.payload.get("universe") or 0)
    source = (
        f"토스증권 공식 — 거래대금 상위 {universe}종목 중 순위 (시장 전체 순위 아님) · "
        f"{target} 기준 · 금액 = 순매수 주식 수 × 현재가 (추정)"
    )
    records: list[dict] = []
    for stock, record, net in picks:
        if record.get("date") != target or not net:
            continue
        if (trade_type == "순매수") != (net > 0):
            continue
        price = stock.get("lastPrice")
        records.append({
            "code": str(stock.get("code")),
            "name": stock.get("name") or str(stock.get("code")),
            "price": price,
            "changePct": stock.get("changePct"),
            "netAmountEok": round(net * price / 1e8, 1) if price else None,
            "netVolume": net,
            "source": source,
            "collectedAt": kst.stamp(snap.collected_at) if snap.collected_at else None,
        })
    if not records:
        _TOSS_LAST_REASON["value"] = f"{target} 기준 {trade_type} 종목이 없습니다"
    return _rank(records, trade_type, top_n)


def test_toss_connection() -> dict:
    """진단 — 레이더가 쓰는 토스 경로를 실제로 부르고, 폴백 저장본의 상태도 함께 적습니다."""
    result = toss.test_connection_flows()
    snap = store.read_snapshot(catalog.SNAP_TOSS_RADAR_UNIVERSE)
    if snap and snap.payload:
        stocks = snap.payload.get("stocks") or []
        result["message"] += (
            f" · 폴백 저장본: 거래대금 상위 {len(stocks)}종목, "
            f"수집 {kst.stamp(snap.collected_at) if snap.collected_at else '시각 모름'}"
        )
    elif result.get("ok"):
        result["message"] += " · 폴백 저장본 없음 (toss_radar_universe 첫 실행 전)"
    return result


def fetch_pykrx_ranking(
    target_date: str,
    market: str,
    investor: str,
    trade_type: str,
    top_n: int,
) -> list[dict]:
    """
    PyKrx 최종 폴백 (과거 날짜 조회가 가능한 유일한 외부 소스).

    ⚠️ KRX가 JSON 대신 차단 페이지를 주면 통째로 실패합니다
    ("Expecting value: line 1 column 1"). 패키지 업그레이드로 해결되지
    않으며, 그때는 누적 이력으로 넘어갑니다.
    """
    if not PYKRX_AVAILABLE:
        logger.warning("PyKrx 미설치 — 이 단계를 건너뜁니다.")
        return []

    market_code = "KOSPI" if "KOSPI" in market.upper() else "KOSDAQ"
    investor_name = PYKRX_INVESTORS.get(investor, "외국인")

    try:
        # pykrx는 실패를 print로 찍습니다("Error occurred in ...").
        # 로깅이 아니라 표준출력이라 필터로는 못 거릅니다. 우리가 바로 아래에서
        # 같은 내용을 더 분명하게 남기므로(사유·날짜 포함) 그 출력만 삼킵니다.
        # 폴백은 날짜를 최대 7번 거슬러 올라가기 때문에, 놔두면 조회 한 번에
        # 같은 줄이 일곱 번 쌓입니다.
        with contextlib.redirect_stdout(io.StringIO()):
            frame = pykrx_stock.get_market_net_purchases_of_equities_by_ticker(
                target_date, target_date, market_code, investor_name
            )
    except Exception as exc:  # noqa: BLE001
        logger.warning("PyKrx 조회 실패 (%s): %s", target_date, exc)
        return []

    if frame is None or frame.empty:
        logger.warning("PyKrx 빈 결과 (%s): 휴장일이거나 데이터 미제공", target_date)
        return []

    frame = frame.reset_index().rename(columns={"티커": "종목코드"})
    if trade_type == "순매수":
        frame = frame[frame["순매수거래대금"] > 0].sort_values(
            "순매수거래대금", ascending=False
        )
    else:
        frame = frame[frame["순매수거래대금"] < 0].sort_values("순매수거래대금")

    frame = frame.head(top_n)
    if frame.empty:
        return []

    try:
        prices = pykrx_stock.get_market_ohlcv(target_date, target_date, market_code)
    except Exception:  # noqa: BLE001
        prices = None

    collected_at = kst.stamp()
    records: list[dict] = []

    for _, row in frame.iterrows():
        code = str(row["종목코드"]).zfill(6)
        price, change_pct = 0.0, 0.0
        if prices is not None and not prices.empty and code in prices.index:
            price_row = prices.loc[code]
            price = float(price_row["종가"])
            change_pct = float(price_row["등락률"])

        records.append({
            "code": code,
            "name": str(row["종목명"]),
            "price": price,
            "changePct": change_pct,
            "netAmountEok": round(float(row["순매수거래대금"]) / 100_000_000.0, 1),
            "source": f"PyKrx ({target_date})",
            "collectedAt": collected_at,
        })

    return _rank(records, trade_type, top_n)


# ==============================================================================
# 4. 누적 이력 (외부 소스가 모두 실패했을 때)
# ==============================================================================
def accumulate_history(
    rows: list[dict],
    market: str,
    investor: str,
    trade_type: str,
    interval_type: str,
) -> int:
    """
    수집된 랭킹을 "데이터 기준 거래일"로 누적합니다.

    기준일은 수집 시각이 아니라 latest_completed_session()이 계산한 거래일을
    씁니다. Naver/Daum이 "가장 최근에 끝난 거래일"만 주기 때문에, 수집 시각으로
    찍으면 토요일 새벽에 받은 금요일 데이터가 토요일로 기록됩니다.
    """
    if not rows:
        return 0

    session_str = latest_completed_session()
    obs_date = f"{session_str[:4]}-{session_str[4:6]}-{session_str[6:8]}"

    records = []
    for row in rows:
        record = dict(row)
        record.update({
            "market": market,
            "investor": investor,
            "tradeType": trade_type,
            "intervalType": interval_type,
        })
        # 같은 거래일에 조건별로 여러 건이 들어오므로 entity에 조건을 포함해야
        # 서로 덮어쓰지 않습니다.
        record["entity"] = (
            f"{market}|{investor}|{trade_type}|{interval_type}|{record.get('code', '?')}"
        )
        records.append(record)

    saved = store.put_observations(
        catalog.OBS_RADAR, obs_date, records, entity_key="entity"
    )
    logger.info(
        "수급 레이더 이력 누적: date=%s, rows=%s (%s/%s/%s/%s)",
        obs_date, saved, market, investor, trade_type, interval_type,
    )
    return saved


def read_from_history(
    target_day: date,
    market: str,
    investor: str,
    trade_type: str,
    top_n: int,
) -> dict | None:
    """
    누적 이력에서 해당 조건의 랭킹을 꺼냅니다.

    요청한 날짜에 이력이 없으면 **그보다 앞선 가장 가까운 거래일**을 씁니다.
    어느 날짜를 썼는지 source에 남겨 화면이 밝힐 수 있게 합니다.
    """
    try:
        target_str = target_day.isoformat()
        available = [
            day for day in store.list_observation_dates(catalog.OBS_RADAR)
            if day <= target_str
        ]
        if not available:
            return None

        picked = max(available)
        rows = store.read_observations(
            catalog.OBS_RADAR,
            obs_date=picked,
            filters={
                "market": market,
                "investor": investor,
                "tradeType": trade_type,
            },
        )
        if not rows:
            return None

        rows.sort(
            key=lambda r: r.get("netAmountEok") or 0.0,
            reverse=(trade_type == "순매수"),
        )
        rows = rows[:top_n]
        source = f"누적 이력 (수집기가 {picked}에 저장한 값 · 외부 소스 전부 실패)"
        for index, row in enumerate(rows, start=1):
            row["rank"] = index
            row["source"] = source
            row.pop("entity", None)

        return {
            "source": source,
            "sourceKind": "history",
            "isHistorical": True,
            "historyDate": picked,
            "rows": rows,
        }
    except Exception as exc:  # noqa: BLE001
        logger.warning("누적 이력 조회 실패: %s", exc)
        return None


# ==============================================================================
# 5. 연결 진단
# ==============================================================================
def test_daum_connection() -> dict:
    """화면이 실제로 쓰는 경로(investor_purchase API)를 그대로 호출합니다."""
    rows = fetch_daum_ranking(
        latest_completed_session(), "KOSPI", "외국인", "순매수", 5, "TODAY"
    )
    if rows:
        return {"ok": True, "stage": "ok", "message": f"{len(rows)}건 수신", "sample": rows[:3]}
    return {
        "ok": False, "stage": "empty",
        "message": "빈 결과입니다. 휴장일이거나 Daum 응답 구조가 바뀌었을 수 있습니다.",
    }


def test_naver_connection() -> dict:
    rows = fetch_naver_ranking(
        latest_completed_session(), "KOSPI", "외국인", "순매수", 5
    )
    if rows:
        return {"ok": True, "stage": "ok", "message": f"{len(rows)}건 수신", "sample": rows[:3]}
    reason = _NAVER_LAST_REASON["value"]
    return {
        "ok": False, "stage": "empty",
        "message": (
            f"빈 결과입니다. {reason}" if reason
            else "빈 결과입니다. 페이지 구조 변경 또는 차단을 의심하세요."
        ),
    }


def test_pykrx_connection() -> dict:
    if not PYKRX_AVAILABLE:
        return {
            "ok": False, "stage": "not_installed",
            "message": "pykrx 패키지가 설치되지 않았습니다.",
        }
    rows = fetch_pykrx_ranking(
        latest_completed_session(), "KOSPI", "외국인", "순매수", 5
    )
    if rows:
        return {"ok": True, "stage": "ok", "message": f"{len(rows)}건 수신"}
    return {
        "ok": False, "stage": "empty",
        "message": (
            "빈 결과입니다. KRX가 pykrx에 JSON 대신 차단 페이지를 주는 상태일 수 "
            "있습니다. 당일 조회는 KIS/Daum/Naver로 정상 동작하며, 과거 조회는 "
            "누적 이력으로 대체됩니다."
            + ("" if PYKRX_LOGGED_IN else
               " KRX 홈페이지 계정이 있으면 .env에 KRX_ID·KRX_PW를 넣어 "
               "로그인 조회를 시도해 볼 수 있습니다(선택).")
        ),
    }


# ==============================================================================
# 내부 헬퍼
# ==============================================================================
def _rank(records: list[dict], trade_type: str, top_n: int) -> list[dict]:
    if not records:
        return []
    records.sort(
        key=lambda r: r.get("netAmountEok") or 0.0,
        reverse=(trade_type == "순매수"),
    )
    top = records[:top_n]
    for index, record in enumerate(top, start=1):
        record["rank"] = index
    return top


def _cell_to_float(cell) -> float:
    try:
        text = cell.text.replace(",", "").replace("+", "").replace("%", "").strip()
        return float(text)
    except (AttributeError, TypeError, ValueError):
        return 0.0


def _to_float(value) -> float:
    try:
        return float(str(value).replace(",", "").strip())
    except (TypeError, ValueError):
        return 0.0
