# fuzz-api 결과 — 요청 1004건, 표시 65건

| 경로 | 파라미터 | 값 | 상태 | ms | 표시 | 응답 앞부분 |
|---|---|---|---|---|---|---|
| GET /api/macro/fred/{seriesId} | years | `-1` | 200 | 4 | 비정상 숫자를 200으로 받음 | {"seriesId":"DGS10","available":true,"collectedAtKst":"2026-10-02 17:57 KST","ageSeconds":11630,"points":[{"date":"2016- |
| GET /api/macro/fred/{seriesId} | years | `-2147483648` | 200 | 5 | 비정상 숫자를 200으로 받음 | {"seriesId":"DGS10","available":true,"collectedAtKst":"2026-10-02 17:57 KST","ageSeconds":11630,"points":[{"date":"2016- |
| GET /api/liquidity | years | `-1` | 200 | 7 | 비정상 숫자를 200으로 받음 | {"available":true,"isEstimated":false,"collectedAtKst":"2026-10-02 17:58 KST","ageSeconds":11622,"stale":false,"rows":[{ |
| GET /api/liquidity | years | `-2147483648` | 200 | 23 | 비정상 숫자를 200으로 받음 | {"available":true,"isEstimated":false,"collectedAtKst":"2026-10-02 17:58 KST","ageSeconds":11622,"stale":false,"rows":[{ |
| GET /api/sec13f/portfolio | quarters | `-1` | 200 | 4 | 비정상 숫자를 200으로 받음 | {"cik":"0001608046","quartersRequested":-1,"quartersUsed":1,"available":true,"collectedAtKst":"2026-10-02 17:58 KST","ag |
| GET /api/sec13f/portfolio | quarters | `-2147483648` | 200 | 2 | 비정상 숫자를 200으로 받음 | {"cik":"0001608046","quartersRequested":-2147483648,"quartersUsed":1,"available":true,"collectedAtKst":"2026-10-02 17:58 |
| GET /api/sec13f/portfolio | topN | `-1` | 200 | 4 | 비정상 숫자를 200으로 받음 | {"cik":"0001608046","quartersRequested":8,"quartersUsed":8,"available":true,"collectedAtKst":"2026-10-02 17:58 KST","age |
| GET /api/sec13f/portfolio | topN | `-2147483648` | 200 | 4 | 비정상 숫자를 200으로 받음 | {"cik":"0001608046","quartersRequested":8,"quartersUsed":8,"available":true,"collectedAtKst":"2026-10-02 17:58 KST","age |
| GET /api/sec13f/consensus | reportDate | `9999-99-99` | 200 | 2 | 비정상 날짜를 200으로 받음 | {"participants":[],"participantCount":0,"availableDates":["2026-06-30","2026-03-31","2025-12-31","2025-09-30","2025-06-3 |
| GET /api/sec13f/consensus | reportDate | `2026-02-30` | 200 | 2 | 비정상 날짜를 200으로 받음 | {"participants":[],"participantCount":0,"availableDates":["2026-06-30","2026-03-31","2025-12-31","2025-09-30","2025-06-3 |
| GET /api/sec13f/consensus | reportDate | `now` | 200 | 1 | 비정상 날짜를 200으로 받음 | {"participants":[],"participantCount":0,"availableDates":["2026-06-30","2026-03-31","2025-12-31","2025-09-30","2025-06-3 |
| GET /api/sec13f/consensus | reportDate | `2026-13-01` | 200 | 2 | 비정상 날짜를 200으로 받음 | {"participants":[],"participantCount":0,"availableDates":["2026-06-30","2026-03-31","2025-12-31","2025-09-30","2025-06-3 |
| GET /api/sec13f/consensus | reportDate | `' OR 1=1` | 200 | 2 | 비정상 날짜를 200으로 받음 | {"participants":[],"participantCount":0,"availableDates":["2026-06-30","2026-03-31","2025-12-31","2025-09-30","2025-06-3 |
| GET /api/sec13f/consensus | minHolders | `-1` | 200 | 3 | 비정상 숫자를 200으로 받음 | {"participants":["🇰🇷 국민연금 (National Pension Service)","🇳🇴 노르웨이 국부펀드 (Norges Bank / GPFG)"],"participantCount":2,"availab |
| GET /api/sec13f/consensus | minHolders | `-2147483648` | 200 | 5 | 비정상 숫자를 200으로 받음 | {"participants":["🇰🇷 국민연금 (National Pension Service)","🇳🇴 노르웨이 국부펀드 (Norges Bank / GPFG)"],"participantCount":2,"availab |
| GET /api/sec13f/new-buys | reportDate | `9999-99-99` | 200 | 3 | 비정상 날짜를 200으로 받음 | {"participants":[],"participantCount":0,"availableDates":["2026-06-30","2026-03-31","2025-12-31","2025-09-30","2025-06-3 |
| GET /api/sec13f/new-buys | reportDate | `2026-02-30` | 200 | 1 | 비정상 날짜를 200으로 받음 | {"participants":[],"participantCount":0,"availableDates":["2026-06-30","2026-03-31","2025-12-31","2025-09-30","2025-06-3 |
| GET /api/sec13f/new-buys | reportDate | `now` | 200 | 1 | 비정상 날짜를 200으로 받음 | {"participants":[],"participantCount":0,"availableDates":["2026-06-30","2026-03-31","2025-12-31","2025-09-30","2025-06-3 |
| GET /api/sec13f/new-buys | reportDate | `2026-13-01` | 200 | 1 | 비정상 날짜를 200으로 받음 | {"participants":[],"participantCount":0,"availableDates":["2026-06-30","2026-03-31","2025-12-31","2025-09-30","2025-06-3 |
| GET /api/sec13f/new-buys | reportDate | `' OR 1=1` | 200 | 2 | 비정상 날짜를 200으로 받음 | {"participants":[],"participantCount":0,"availableDates":["2026-06-30","2026-03-31","2025-12-31","2025-09-30","2025-06-3 |
| GET /api/sec13f/new-buys | minHolders | `-1` | 200 | 3 | 비정상 숫자를 200으로 받음 | {"participants":["🇰🇷 국민연금 (National Pension Service)","🇳🇴 노르웨이 국부펀드 (Norges Bank / GPFG)"],"participantCount":2,"availab |
| GET /api/sec13f/new-buys | minHolders | `-2147483648` | 200 | 3 | 비정상 숫자를 200으로 받음 | {"participants":["🇰🇷 국민연금 (National Pension Service)","🇳🇴 노르웨이 국부펀드 (Norges Bank / GPFG)"],"participantCount":2,"availab |
| GET /api/guru/risk | years | `-1` | 200 | 29 | 비정상 숫자를 200으로 받음 | {"cik":"0001608046","benchmark":"SPY","years":1,"institution":{"key":"nps","cik":"0001608046","name":"🇰🇷 국민연금 (National  |
| GET /api/guru/risk | years | `-2147483648` | 200 | 28 | 비정상 숫자를 200으로 받음 | {"cik":"0001608046","benchmark":"SPY","years":1,"institution":{"key":"nps","cik":"0001608046","name":"🇰🇷 국민연금 (National  |
| GET /api/stock/scorecard | symbol | `' OR '1'='1` | 200 | 7 | 내부 정보 노출 의심 | {"symbol":"' OR '1'='1","benchmark":"SPY","years":3,"universeLabel":"13F 매핑 대형주","missing":["재무 안정성 (부채비율·이자보상배율)","성장성  |
| GET /api/stock/scorecard | symbol | `"; DROP TABLE snapshots; --` | 200 | 5 | 내부 정보 노출 의심 | {"symbol":"\"; DROP TABLE snapshots; --","benchmark":"SPY","years":3,"universeLabel":"13F 매핑 대형주","missing":["재무 안정성 (부채 |
| GET /api/stock/scorecard | symbol | `../../../etc/passwd` | 200 | 3 | 내부 정보 노출 의심 | {"symbol":"../../../etc/passwd","benchmark":"SPY","years":3,"universeLabel":"13F 매핑 대형주","missing":["재무 안정성 (부채비율·이자보상배율 |
| GET /api/stock/scorecard | symbol | `%00` | 200 | 4 | 내부 정보 노출 의심 | {"symbol":"%00","benchmark":"SPY","years":3,"universeLabel":"13F 매핑 대형주","missing":["재무 안정성 (부채비율·이자보상배율)","성장성 (매출·이익 증 |
| GET /api/stock/scorecard | symbol | `<script>alert(1)</script>` | 200 | 3 | 내부 정보 노출 의심 | {"symbol":"<script>alert(1)</script>","benchmark":"SPY","years":3,"universeLabel":"13F 매핑 대형주","missing":["재무 안정성 (부채비율· |
| GET /api/stock/scorecard | symbol | `{{7*7}}` | 200 | 2 | 내부 정보 노출 의심 | {"symbol":"{{7*7}}","benchmark":"SPY","years":3,"universeLabel":"13F 매핑 대형주","missing":["재무 안정성 (부채비율·이자보상배율)","성장성 (매출· |
| GET /api/stock/scorecard | symbol | `${jndi:ldap://x}` | 200 | 3 | 내부 정보 노출 의심 | {"symbol":"${jndi:ldap://x}","benchmark":"SPY","years":3,"universeLabel":"13F 매핑 대형주","missing":["재무 안정성 (부채비율·이자보상배율)", |
| GET /api/stock/scorecard | symbol | `삼성전자` | 200 | 2 | 내부 정보 노출 의심 | {"symbol":"삼성전자","benchmark":"SPY","years":3,"universeLabel":"13F 매핑 대형주","missing":["재무 안정성 (부채비율·이자보상배율)","성장성 (매출·이익  |
| GET /api/stock/scorecard | symbol | `%` | 200 | 3 | 내부 정보 노출 의심 | {"symbol":"%","benchmark":"SPY","years":3,"universeLabel":"13F 매핑 대형주","missing":["재무 안정성 (부채비율·이자보상배율)","성장성 (매출·이익 증가율 |
| GET /api/stock/scorecard | symbol | `_` | 200 | 3 | 내부 정보 노출 의심 | {"symbol":"_","benchmark":"SPY","years":3,"universeLabel":"13F 매핑 대형주","missing":["재무 안정성 (부채비율·이자보상배율)","성장성 (매출·이익 증가율 |
| GET /api/stock/scorecard | symbol | `\` | 200 | 2 | 내부 정보 노출 의심 | {"symbol":"\\","benchmark":"SPY","years":3,"universeLabel":"13F 매핑 대형주","missing":["재무 안정성 (부채비율·이자보상배율)","성장성 (매출·이익 증가 |
| GET /api/stock/scorecard | symbol | `' UNION SELECT NULL--` | 200 | 2 | 내부 정보 노출 의심 | {"symbol":"' UNION SELECT NULL--","benchmark":"SPY","years":3,"universeLabel":"13F 매핑 대형주","missing":["재무 안정성 (부채비율·이자보상 |
| GET /api/stock/scorecard | symbol | `` | 200 | 6 | 내부 정보 노출 의심 | {"symbol":"","benchmark":"SPY","years":3,"universeLabel":"13F 매핑 대형주","missing":["재무 안정성 (부채비율·이자보상배율)","성장성 (매출·이익 증가율) |
| GET /api/stock/scorecard | symbol | `AAPL
X-Injected: 1` | 200 | 4 | 내부 정보 노출 의심 | {"symbol":"AAPL\r\nX-Injected: 1","benchmark":"SPY","years":3,"universeLabel":"13F 매핑 대형주","missing":["재무 안정성 (부채비율·이자보상 |
| GET /api/stock/scorecard | symbol | `🙂` | 200 | 3 | 내부 정보 노출 의심 | {"symbol":"🙂","benchmark":"SPY","years":3,"universeLabel":"13F 매핑 대형주","missing":["재무 안정성 (부채비율·이자보상배율)","성장성 (매출·이익 증가율 |
| GET /api/stock/scorecard | symbol | `DGS10;ls` | 200 | 1 | 내부 정보 노출 의심 | {"symbol":"DGS10;ls","benchmark":"SPY","years":3,"universeLabel":"13F 매핑 대형주","missing":["재무 안정성 (부채비율·이자보상배율)","성장성 (매출 |
| GET /api/stock/scorecard | years | `-1` | 200 | 36 | 비정상 숫자를 200으로 받음 | {"symbol":"AAPL","benchmark":"SPY","years":1,"universeLabel":"13F 매핑 대형주","missing":["재무 안정성 (부채비율·이자보상배율)","성장성 (매출·이익  |
| GET /api/stock/scorecard | years | `-2147483648` | 200 | 28 | 비정상 숫자를 200으로 받음 | {"symbol":"AAPL","benchmark":"SPY","years":1,"universeLabel":"13F 매핑 대형주","missing":["재무 안정성 (부채비율·이자보상배율)","성장성 (매출·이익  |
| GET /api/krx/futures | days | `-1` | 200 | 2 | 비정상 숫자를 200으로 받음 | {"available":true,"isEstimated":false,"collectedAtKst":"2026-10-02 17:58 KST","ageSeconds":11614,"stale":false,"rows":[{ |
| GET /api/krx/futures | days | `-2147483648` | 200 | 2 | 비정상 숫자를 200으로 받음 | {"available":true,"isEstimated":false,"collectedAtKst":"2026-10-02 17:58 KST","ageSeconds":11614,"stale":false,"rows":[{ |
| GET /api/krx/intraday | minutes | `-1` | 200 | 3 | 비정상 숫자를 200으로 받음 | {"message":"수집기에서 장중 수급을 받지 못했습니다.","available":false} |
| GET /api/krx/intraday | minutes | `-2147483648` | 200 | 5 | 비정상 숫자를 200으로 받음 | {"message":"수집기에서 장중 수급을 받지 못했습니다.","available":false} |
| GET /api/radar/ranking | topN | `-1` | 200 | 4 | 비정상 숫자를 200으로 받음 | {"market":"KOSPI","investor":"외국인","tradeType":"순매수","intervalType":"TODAY","readMode":"auto","available":false,"rows":[ |
| GET /api/radar/ranking | topN | `-2147483648` | 200 | 7 | 비정상 숫자를 200으로 받음 | {"market":"KOSPI","investor":"외국인","tradeType":"순매수","intervalType":"TODAY","readMode":"auto","available":false,"rows":[ |
| GET /api/radar/ranking | targetDate | `9999-99-99` | 200 | 5 | 비정상 날짜를 200으로 받음 | {"market":"KOSPI","investor":"외국인","tradeType":"순매수","intervalType":"TODAY","readMode":"auto","available":false,"rows":[ |
| GET /api/radar/ranking | targetDate | `2026-02-30` | 200 | 5 | 비정상 날짜를 200으로 받음 | {"market":"KOSPI","investor":"외국인","tradeType":"순매수","intervalType":"TODAY","readMode":"auto","available":false,"rows":[ |
| GET /api/radar/ranking | targetDate | `now` | 200 | 4 | 비정상 날짜를 200으로 받음 | {"market":"KOSPI","investor":"외국인","tradeType":"순매수","intervalType":"TODAY","readMode":"auto","available":false,"rows":[ |
| GET /api/radar/ranking | targetDate | `2026-13-01` | 200 | 6 | 비정상 날짜를 200으로 받음 | {"market":"KOSPI","investor":"외국인","tradeType":"순매수","intervalType":"TODAY","readMode":"auto","available":false,"rows":[ |
| GET /api/radar/ranking | targetDate | `' OR 1=1` | 200 | 4 | 비정상 날짜를 200으로 받음 | {"market":"KOSPI","investor":"외국인","tradeType":"순매수","intervalType":"TODAY","readMode":"auto","available":false,"rows":[ |
| GET /api/radar/consensus | topN | `-1` | 200 | 7 | 비정상 숫자를 200으로 받음 | {"market":"KOSPI","tradeType":"순매수","intervalType":"TODAY","topN":-1,"available":false,"rows":[],"message":"외국인·기관 수급을 받 |
| GET /api/radar/consensus | topN | `-2147483648` | 200 | 8 | 비정상 숫자를 200으로 받음 | {"market":"KOSPI","tradeType":"순매수","intervalType":"TODAY","topN":-2147483648,"available":false,"rows":[],"message":"외국인 |
| GET /api/kr/market-totals | days | `-1` | 200 | 5 | 비정상 숫자를 200으로 받음 | {"collectedAtKst":"2026-10-02 17:06 KST","ageSeconds":14753,"available":true,"source":"금융위원회 주식시세정보 (거래소 확정치, 기준일 다음 영업일 |
| GET /api/kr/market-totals | days | `-2147483648` | 200 | 4 | 비정상 숫자를 200으로 받음 | {"collectedAtKst":"2026-10-02 17:06 KST","ageSeconds":14753,"available":true,"source":"금융위원회 주식시세정보 (거래소 확정치, 기준일 다음 영업일 |
| GET /api/status/history | limit | `-1` | 200 | 7 | 비정상 숫자를 200으로 받음 | {"history":[{"task":"toss_market_flows","speed":"slow","status":"empty","startedAt":"2026-10-02T12:12:50.879691+00:00"," |
| GET /api/status/history | limit | `-2147483648` | 200 | 4 | 비정상 숫자를 200으로 받음 | {"history":[{"task":"toss_market_flows","speed":"slow","status":"empty","startedAt":"2026-10-02T12:12:50.879691+00:00"," |
| GET /api/analytics/correlation | window | `-1` | 200 | 2 | 비정상 숫자를 200으로 받음 | {"x":null,"y":null,"mode":"change","window":5,"available":false,"message":"알 수 없는 계열입니다: DGS10"} |
| GET /api/analytics/correlation | window | `-2147483648` | 200 | 2 | 비정상 숫자를 200으로 받음 | {"x":null,"y":null,"mode":"change","window":5,"available":false,"message":"알 수 없는 계열입니다: DGS10"} |
| GET /api/analytics/correlation | years | `-1` | 200 | 1 | 비정상 숫자를 200으로 받음 | {"x":null,"y":null,"mode":"change","window":60,"available":false,"message":"알 수 없는 계열입니다: DGS10"} |
| GET /api/analytics/correlation | years | `-2147483648` | 200 | 1 | 비정상 숫자를 200으로 받음 | {"x":null,"y":null,"mode":"change","window":60,"available":false,"message":"알 수 없는 계열입니다: DGS10"} |
| GET /api/analytics/regime | years | `-1` | 200 | 21 | 비정상 숫자를 200으로 받음 | {"verdict":{"code":"EXPANSION","label":"확장 (Expansion)","summary":"성장·신용 신호가 모두 정상이고 유동성도 들어오고 있습니다. 위험자산에 우호적인 조합입니다.", |
| GET /api/analytics/regime | years | `-2147483648` | 200 | 21 | 비정상 숫자를 200으로 받음 | {"verdict":{"code":"EXPANSION","label":"확장 (Expansion)","summary":"성장·신용 신호가 모두 정상이고 유동성도 들어오고 있습니다. 위험자산에 우호적인 조합입니다.", |
