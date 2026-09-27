# 토스증권 Open API 테스트 입력

**실제 호출 결과가 아닙니다.** 개발 환경에서 토스 API에 접속할 수 없어, 공식 OpenAPI 스펙
1.2.17(2026-09-14 공개본)의 스키마와 필드 설명·예시값으로 만든 응답입니다.

- `market_investor_trading_kospi.json` — `GET /api/v1/market-indicators/KOSPI/investor-trading`
  (금액은 원 단위 **문자열 정수**, 기관 합계 = 세부 7개 합, 4개 분류 매수 합계 = 매도 합계 — 스펙의 등식)
- `stock_investor_trading_005930.json` — `GET /api/v1/stocks/005930/investor-trading`
  첫 기록은 **당일 잠정치** 모양 그대로입니다 — 스펙 설명대로 개인·기관 세부·기타법인·
  외국인 보유·CFD가 `null`입니다. 이 null을 0으로 바꾸지 않는지가 테스트의 핵심입니다.

실제 응답으로 바꿀 수 있게 되면 이 파일들을 교체하세요(키·계좌 정보는 들어 있지 않습니다).
