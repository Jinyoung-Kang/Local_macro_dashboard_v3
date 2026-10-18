"""
tests/test_dependency_imports.py
의존성 판을 올린 뒤에도 선택 의존성(pykrx)이 실제로 import되는지.

radar.py는 pykrx import 예외를 모두 삼키고 PYKRX_AVAILABLE=False로 둡니다(KRX가 막히면 어차피 못 쓰는 선택
단계라서). pykrx는 import할 때 matplotlib·PIL까지 올리므로, pillow·matplotlib·pandas 판 조합이 깨지면 운영에서는
수급 레이더의 pykrx 단계(과거 날짜 조회는 이 단계만 지원)가 로그 한 줄 없이 꺼집니다. 다른 테스트는 모두
PYKRX_AVAILABLE을 monkeypatch로 True로 덮어써 이 회귀를 잡지 못합니다. 네트워크는 쓰지 않습니다(KRX_ID가
없으면 pykrx는 로그인을 시도하지 않고 print만 합니다).
"""
from __future__ import annotations

import importlib

from app.services import radar


def test_pykrx는_실제로_import된다():
    if not radar.PYKRX_AVAILABLE:
        # radar는 예외를 삼키므로 같은 import를 다시 해 실제 오류(어느 패키지에서 깨졌는지)를 그대로 보입니다.
        importlib.import_module("pykrx.stock")
    assert radar.PYKRX_AVAILABLE is True, radar._pykrx_import_output.getvalue()
    assert radar.pykrx_stock is not None
