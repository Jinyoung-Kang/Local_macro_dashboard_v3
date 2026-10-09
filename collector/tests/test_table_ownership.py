"""
tests/test_table_ownership.py
테이블마다 쓰는 프로세스는 하나(ADR 0008) — 수집기는 백엔드가 쓰는 테이블에 쓰지 않습니다.

백엔드 쪽은 ArchitectureRulesTest가 "백엔드는 수집기 테이블에 쓰지 않는다"를 검사합니다. 여기는 그 반대입니다.
수집기가 읽어도 되는 백엔드 테이블은 alert_rules(관심 종목 코드) 하나뿐입니다.
"""
from __future__ import annotations

import re
from pathlib import Path

APP = Path(__file__).resolve().parents[1] / "app"
BACKEND_TABLES = (
    "regime_log", "users", "user_sessions", "audit_log", "email_tokens", "usage_counters",
    "alert_rules", "alert_events", "alert_deliveries",
)
WRITE = re.compile(
    r"(?i)(INSERT\s+INTO|UPDATE|DELETE\s+FROM|TRUNCATE)\s+(" + "|".join(BACKEND_TABLES) + r")\b"
)


def test_수집기는_백엔드_테이블에_쓰지_않는다():
    found = []
    for path in APP.rglob("*.py"):
        for match in WRITE.finditer(path.read_text(encoding="utf-8")):
            found.append(f"{path.name}: {match.group(0)}")
    assert found == []
