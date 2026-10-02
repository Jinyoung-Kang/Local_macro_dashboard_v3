# 의존성·지원 기간 점검 (2026-10-02)

| 구성 | 버전 | 알려진 취약점 | 지원 상태 |
|---|---|---|---|
| Python (수집기 이미지) | 3.11-slim | pip-audit: 0건 (requirements.txt 전체) | 보안 지원 2027-10까지 — 여유 1년 |
| FastAPI / uvicorn / psycopg / pandas / yfinance / pykrx | 0.141.1 / 0.34.0 / 3.2.3 / 2.2.3 / 1.7.0 / 1.2.8 | 0건 | 현행 |
| Node (화면 이미지) | 22-alpine | npm audit: 0건 (prod 58·dev 473) | LTS 유지보수 2027-04까지 |
| Next.js / React / recharts | 15.5.26 / 18.3.1 / 2.15.0 | 0건 | Next 16·React 19가 현행. recharts 2.x는 **유지 종료**(npm 설치 시 deprecated 경고) → 3.x 이전 권장 |
| Java (백엔드) | Temurin 21 JRE | — | LTS, 2029-09까지 |
| Spring Boot / Framework | 4.1.1 / 7.0.9 | GitHub Advisory: 런타임 의존성 60개 전부 0건 | Spring Boot 4.1 OSS 지원은 **2026-12 전후 종료 예정**(GA 2025-11 + 13개월) → 4.2 이전 계획 필요 |
| Tomcat embed / Jackson / HikariCP / PostgreSQL JDBC | 11.0.24 / 2.21.5·3.1.5 / 7.0.2 / 42.7.13 | 0건 | 현행 |
| PostgreSQL | 16-alpine | — | 2028-11까지 |

방법: `pip-audit -r collector/requirements.txt`, `npm audit`(frontend), `scripts/qa/dep-advisories.sh`(Maven 60개 ×
GitHub Advisory `affects=` 조회). 결과 파일: maven-advisories.txt. 지원 기간은 각 프로젝트의 공개 일정 기준이며
2026-10 시점의 추정입니다.
