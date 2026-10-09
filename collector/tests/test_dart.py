"""
tests/test_dart.py
Open DART 고유번호·주요계정.

응답 형태의 근거 — 실제 응답 표본은 없습니다(이 개발 환경에서 DART에 접속 불가).
아래 최소 응답은 공식 개발가이드의 필드 정의(kenshin579/opendart-go docs/api에
옮겨진 것)와 OpenDartReader가 CORPCODE.xml을 읽는 방식(`list` 요소 아래
corp_code·corp_name·stock_code)만으로 구성했습니다. 계정명은 가이드의 예시
("자본총계")와 괄호 표기 변형을 씁니다.

고정하는 규칙
  1. 고유번호 표는 상장사(6자리 종목코드)만 남긴다.
  2. 연결(CFS)이 있으면 연결, 없으면 별도(OFS).
  3. 금액 "1,234" → 1234, 빈 값·"-" → None (0으로 채우지 않는다).
  4. status 013(데이터 없음)은 빈 목록, 그 밖의 오류는 키가 지워진 사유로 실패.
  5. 비정상적으로 큰 ZIP은 풀지 않는다.
"""
from __future__ import annotations

import io
import zipfile
from types import SimpleNamespace

import pytest

from app import publicapi
from app.services import dart

KEY = "0123456789abcdef0123456789abcdef01234567"


def _zip(xml: str, name: str = "CORPCODE.xml") -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr(name, xml)
    return buffer.getvalue()


CORPCODE = """<?xml version="1.0" encoding="UTF-8"?>
<result>
  <list><corp_code>00126380</corp_code><corp_name>삼성전자</corp_name><stock_code>005930</stock_code><modify_date>20240101</modify_date></list>
  <list><corp_code>00999999</corp_code><corp_name>비상장회사</corp_name><stock_code> </stock_code><modify_date>20240101</modify_date></list>
</result>"""


def test_고유번호는_상장사만_남긴다():
    table = dart.parse_corp_codes(_zip(CORPCODE))
    assert table == {"005930": {"corpCode": "00126380", "name": "삼성전자"}}


def test_큰_ZIP은_풀지_않는다(monkeypatch):
    monkeypatch.setattr(dart, "_MAX_XML_BYTES", 10)
    with pytest.raises(publicapi.PublicApiError, match="비정상적으로 큽니다"):
        dart.parse_corp_codes(_zip(CORPCODE))


def test_ZIP_대신_오류가_오면_사유를_보여_준다(monkeypatch):
    body = '{"status":"010","message":"등록되지 않은 키입니다."}'.encode()
    monkeypatch.setattr(dart.settings, "dart_key", lambda: KEY)
    monkeypatch.setattr(dart.publicapi, "get", lambda *a, **k: SimpleNamespace(content=body, status_code=200))
    with pytest.raises(publicapi.PublicApiError, match="status=010 등록되지 않은 키"):
        dart.fetch_corp_codes()


def _row(stock, fs_div, account, current, previous="", before=""):
    return {
        "rcept_no": "20250311000001", "bsns_year": "2024", "stock_code": stock, "reprt_code": "11011",
        "account_nm": account, "fs_div": fs_div, "sj_div": "BS", "thstrm_amount": current,
        "frmtrm_amount": previous, "bfefrmtrm_amount": before, "currency": "KRW",
    }


def test_연결이_있으면_연결을_쓰고_금액을_숫자로_바꾼다():
    rows = [
        _row("005930", "OFS", "자본총계", "100"),
        _row("005930", "CFS", "자본총계", "1,000", "900", "800"),
        _row("005930", "CFS", "당기순이익(손실)", "-1,234", "-", ""),
        _row("000660", "OFS", "부채총계", "50"),
    ]
    grouped = dart.group_by_company(rows)

    samsung = grouped["005930"]
    assert samsung["fsDiv"] == "CFS"
    assert samsung["accounts"]["자본총계"]["current"] == 1000.0
    assert samsung["accounts"]["자본총계"]["beforePrevious"] == 800.0
    # 괄호 표기는 정규화하되 원문은 남깁니다.
    net = samsung["accounts"]["당기순이익"]
    assert net["rawName"] == "당기순이익(손실)"
    assert net["current"] == -1234.0 and net["previous"] is None and net["beforePrevious"] is None
    # 연결이 없는 회사는 별도.
    assert grouped["000660"]["fsDiv"] == "OFS"


@pytest.mark.parametrize("text,expected", [("1,234", 1234.0), ("-5", -5.0), ("", None), ("-", None), (None, None), ("N/A", None)])
def test_금액_파싱(text, expected):
    assert dart.parse_amount(text) == expected


def test_status_013은_빈_목록():
    response = SimpleNamespace(json=lambda: {"status": "013", "message": "조회된 데이타가 없습니다."}, text="", status_code=200)
    assert dart._json(response, KEY) == {"status": "013", "list": []}


def test_그_밖의_status는_키를_지운_사유로_실패():
    response = SimpleNamespace(json=lambda: {"status": "020", "message": f"요청 제한 초과 {KEY}"}, text="", status_code=200)
    with pytest.raises(publicapi.PublicApiError) as info:
        dart._json(response, KEY)
    assert "status=020" in str(info.value) and KEY not in str(info.value)


def test_사업보고서_기준_연도():
    from datetime import date

    from app import tasks
    assert tasks._latest_annual_year(date(2026, 3, 31)) == 2024  # 3월엔 작년 보고서가 없는 회사가 많음
    assert tasks._latest_annual_year(date(2026, 4, 1)) == 2025


# ------------------------------------------------------------------ 태스크
class _FakeStore:
    def __init__(self, snapshots=None, codes=None):
        self.snapshots = dict(snapshots or {})
        self.codes = codes or []

    def read_snapshot(self, name):
        from datetime import datetime, timezone
        payload = self.snapshots.get(name)
        return SimpleNamespace(payload=payload, collected_at=datetime.now(timezone.utc)) if payload else None

    def put_snapshot(self, name, payload):
        self.snapshots[name] = payload

    def recent_observation_codes(self, dataset, since, limit):
        return list(self.codes)


def test_최신_연도에_없는_회사만_전년도로_다시_받는다(monkeypatch):
    from app import catalog, tasks

    fake = _FakeStore(
        snapshots={catalog.SNAP_DART_CORP_CODES: {"codes": {
            "005930": {"corpCode": "00126380", "name": "삼성전자"},
            "000660": {"corpCode": "00164779", "name": "SK하이닉스"},
        }}},
        codes=["005930", "000660", "069500"],  # 069500(ETF)은 DART 대상이 아님
    )
    monkeypatch.setattr(tasks.korea_public, "store", fake)
    calls = []

    def fetch(corp_codes, year):
        calls.append((tuple(corp_codes), year))
        latest = tasks._latest_annual_year(__import__("datetime").datetime.now(tasks.KST).date())
        if year == latest:
            return [_row("005930", "CFS", "자본총계", "10")]
        return [_row("000660", "CFS", "자본총계", "5")]

    monkeypatch.setattr(tasks.dart_service, "fetch_accounts", fetch)
    result = tasks.task_dart_fundamentals()

    saved = fake.snapshots[catalog.SNAP_DART_FUNDAMENTALS]
    assert set(saved["companies"]) == {"005930", "000660"}
    assert saved["companies"]["005930"]["name"] == "삼성전자"
    assert saved["unmapped"] == ["069500"]
    # 두 번째 호출은 첫 호출에서 빠진 회사만.
    assert calls[1][0] == ("00164779",)
    assert "2/2" in result


def test_이번에_못_받은_종목은_이전_저장본을_유지한다(monkeypatch):
    from app import catalog, tasks

    old = {"000660": {"bsnsYear": "2023", "accounts": {}}}
    fake = _FakeStore(
        snapshots={
            catalog.SNAP_DART_CORP_CODES: {"codes": {"005930": {"corpCode": "00126380", "name": "삼성전자"}}},
            catalog.SNAP_DART_FUNDAMENTALS: {"companies": old},
        },
        codes=["005930"],
    )
    monkeypatch.setattr(tasks.korea_public, "store", fake)
    monkeypatch.setattr(tasks.dart_service, "fetch_accounts", lambda codes, year: [_row("005930", "CFS", "자본총계", "10")])

    tasks.task_dart_fundamentals()

    assert set(fake.snapshots[catalog.SNAP_DART_FUNDAMENTALS]["companies"]) == {"005930", "000660"}


def test_키가_없으면_EmptyResult(monkeypatch):
    from app import tasks

    fake = _FakeStore(codes=["005930"])
    monkeypatch.setattr(tasks.korea_public, "store", fake)

    def missing():
        raise publicapi.MissingKey("x")

    monkeypatch.setattr(tasks.dart_service, "fetch_corp_codes", missing)
    with pytest.raises(tasks.EmptyResult, match="DART_API_KEY"):
        tasks.task_dart_fundamentals()
