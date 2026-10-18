# 의존성·지원 기간 점검 (2026-10-02)

| 구성 | 버전 | 알려진 취약점 | 지원 상태 |
|---|---|---|---|
| Python (수집기 이미지) | 3.11-slim | pip-audit: 0건 (requirements.txt 전체) | 보안 지원 2027-10까지 — 여유 1년 |
| FastAPI / uvicorn / psycopg / pandas / yfinance / pykrx | 0.141.1 / 0.34.0 / 3.2.3 / 2.2.3 / 1.7.0 / 1.2.8 | 0건 | 현행 |
| Node (화면 이미지) | 22-alpine | npm audit: 0건 (prod 58·dev 473) | LTS 유지보수 2027-04까지 |
| Next.js / React / recharts | 15.5.26 / 18.3.1 / 2.15.0 | 0건 | Next 16·React 19가 현행. recharts 2.x는 **유지 종료**(npm 설치 시 deprecated 경고) → 3.x 이전 권장 |
| Java (백엔드) | Temurin 21 JRE | — | LTS, 2029-09까지 |
| Spring Boot / Framework | 4.1.1 / 7.0.9 | GitHub Advisory: 런타임 의존성 60개 전부 0건 | **정정(2026-10-03)**: 4.1.0 GA는 2026-06-10(Maven Central 실측), 4.1.1은 2026-08-20. OSS 지원(약 13개월)은 **2027-07 전후**까지. 4.2는 2026-10-03 현재 M2(마일스톤)뿐이라 운영에 올릴 수 없음 — 4.2 GA(2026-11 예정) 뒤 이전 |
| Tomcat embed / Jackson / HikariCP / PostgreSQL JDBC | 11.0.24 / 2.21.5·3.1.5 / 7.0.2 / 42.7.13 | 0건 | 현행 |
| PostgreSQL | 16-alpine | — | 2028-11까지 |

방법: `pip-audit -r collector/requirements.txt`, `npm audit`(frontend), `scripts/qa/dep-advisories.sh`(Maven 60개 ×
GitHub Advisory `affects=` 조회). 결과 파일: maven-advisories.txt. 지원 기간은 각 프로젝트의 공개 일정 기준이며
2026-10 시점의 추정입니다.

**추가(2026-10-18)**: OSV.dev 조회 기준으로 Jackson 3.1.5·2.21.5(jackson-core·jackson-databind 권고 여러 건)와 Tomcat 11.0.24
(11.0.25·26 보안 수정분, tomcat.apache.org/security-11.html)에 공개 권고가 생겼습니다. `068c7e3`에서 3.1.7·2.21.7·11.0.27로 덮어썼고
(Spring Boot 4.1.2가 나오면 지움 — [decisions.md](../../decisions.md) 2026-10-18), 그 뒤 해석된 아티팩트에서 권고가 있는 것은 0개였습니다.
같은 날 수집기는 fastapi 0.143.5·uvicorn 0.54.0·pandas 2.3.3·numpy 2.4.6·pykrx 1.2.9로, 화면은 ESLint 10·TypeScript 6·Tailwind 4·vitest 5로
올렸습니다(REPORT §8).
