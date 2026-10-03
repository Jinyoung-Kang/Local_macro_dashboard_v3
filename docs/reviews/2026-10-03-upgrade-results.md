# 2026-10-03 QA 후속 작업 결과 — 프레임워크 이전 · 응답 캐시 · 큰 응답 축소

QA 보고([docs/qa/2026-10-02-qa-report.md](../qa/2026-10-02-qa-report.md)) §7의 수정 우선순위 ①②③과 CLAUDE.md 갱신.
브랜치 `claude/upgrade-2026-10` → `main` fast-forward.

## ① 프레임워크 이전

| 대상 | 결과 |
|---|---|
| recharts 2.15.0 → **3.10.1** | 완료(`609dee2`). 2.x는 유지 종료(npm deprecated). 3.x의 타입 변화(툴팁 formatter의 `ValueType \| undefined`, 축 tick·LabelList의 느슨한 좌표형)에 맞추고, 축 id가 기본값이 아니면 `CartesianGrid`에 같은 id를 줘야 격자가 그려지는 규칙(MultiLineSeries)을 반영. QA 스택에서 7개 화면의 차트 유형 전부(면적·다중 선·이중 축·가로 막대·부호 막대·산점도) 확인, 콘솔 오류 0, axe 위반 0 |
| Spring Boot 4.1.1 → 4.2 | **보류**. 2026-10-03 현재 Maven Central의 4.2는 M2(마일스톤)뿐 — 운영에 올리지 않습니다. 4.1.0 GA는 2026-06-10(실측)이라 OSS 지원은 **2027-07 전후**까지. QA 보고의 "2026-12 종료"는 정정했습니다. 4.2 GA(2026-11 예정) 뒤 이전 |

## ② 계산 결과 캐시 (`bc6146d`)

`read.ComputedCache`: 저장본 전체의 최신 수집 시각(`StoreRepository.latestCollectedAt`, 1ms 조회)이 버전입니다.
어떤 저장본이든 새로 쓰이면 모든 항목이 무효가 되어 새 데이터를 옛 계산으로 보여 주지 않습니다. 응답의
`ageSeconds`·`stale` 같은 '지금 기준' 값 때문에 60초 상한, 항목 256개(LRU), 예외는 저장하지 않음. 적용 경로 8개:
`/api/snapshot/text`, `/api/guru/profiles`·`similarity`·`risk`, `/api/stock/scorecard`, `/api/sec13f/consensus`·`new-buys`,
`/api/analytics/regime`. 캐시는 컨트롤러에 두고(HTTP 계층의 관심사) ADR 0001 규칙 2에 예외로 적었습니다.

## ③ 큰 응답 축소 (`5f1240c`)

gzip은 이미 켜져 있었습니다(전송 29/19/29KB). 줄인 것은 **직렬화·파싱해야 하는 점의 수**입니다 —
`SeriesMath.thinOlderThan`: 최근 1년은 전부, 그보다 오래된 구간은 ISO 주마다 마지막 관측 하나. 남는 점은 실제
관측값이며(평균·보간 없음) 최신값·직전값·4주/12주 변화·changePct는 솎기 전 전체로 계산합니다(테스트).

| 응답 | 전 | 후 | 점/행 수 |
|---|---|---|---|
| `/api/macro/overview` | 242KB | **76KB** (−69%) | 스프레드 2,560 → 731 |
| `/api/liquidity?years=3` | 133KB | **62KB** (−53%) | 752 → 353 |
| `/api/macro/fx?…&period=5y` | 126KB | **46KB** (−63%) | 2계열 각 ~1,260 → 약 420 |

유동성 4주/12주 변화(0.0254 / −0.1618조 달러)는 솎기 전후 동일합니다.

## 성능 (운영 스택, 같은 맥, k6 16경로)

| 조건 | 전: 처리량 / p50 / p95 | 후: 처리량 / p50 / p95 |
|---|---|---|
| 동시 10명 · 60초 | 81.5 req/s / 7.9ms / 51ms | (배포 뒤 측정) |
| 동시 50명 · 60초 | 197.7 req/s / 83.6ms / 528ms | (배포 뒤 측정) |

## CLAUDE.md
QA 스택 명령, 전달 방식(주제 브랜치 → ff 병합 → CI 확인), 결함 커밋 규칙(test → fix), 수집 실패 시 이전 값 유지·태스크
시간 상한, 함정 3개(`/health`의 DB 확인과 테스트 스텁, `docker kill`과 재시작 정책, GA 버전 확인)를 추가했습니다.
