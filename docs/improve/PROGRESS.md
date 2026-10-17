# 개선 작업 진행 기록 (2026-10 3차 리뷰)

이 파일만 읽고 이어갈 수 있게 씁니다. 계획은 [PLAN.md](PLAN.md), 결과는 [REPORT.md](REPORT.md).

## 작업 위치
- 브랜치 `claude/improve-2026-10` (기준 `main@097d98d`). 프로젝트 규칙(CLAUDE.md)에 맞춰 `claude/<주제>-YYYY-MM` 이름을 씁니다.
- 테스트 DB는 `macrodash_test`만 씁니다. `make infra`로 postgres 컨테이너만 띄웠습니다(운영 스택은 꺼져 있음).
- 백엔드는 CI와 같은 JDK 21로: `JAVA_HOME=$(/usr/libexec/java_home -v 21) make test-backend`.

## 0단계 — 기준선 (2026-10-18, `097d98d`, 변경 전)

| 묶음 | 명령 | 결과 | 시간 |
|---|---|---|---|
| 수집기 | `make test-collector` (ruff + pytest) | ruff 통과 · **411 통과 · 12 건너뜀** · 경고 1 | 23s |
| 백엔드 | `JAVA_HOME=<21> make test-backend` (`mvn -B verify`) | **436 통과**, 실패 0 | 35s |
| 화면 | `make test-frontend` (자가검증 + vitest + eslint + `next build`/tsc) | 자가검증 통과 · **vitest 28파일 205 통과** · eslint 통과 · 빌드 통과(27 페이지) | 15s |
| GitHub CI `main` | `gh run list` | 최근 3회 성공(`097d98d` run 38509346781) | — |

- 건너뛴 12개: 맥에 `psql`·`pg_dump`가 없어 `test_db_scripts.py`가 건너뜀(CI에서는 돔). 이미 알려진 것.
- 경고 1: `StarletteDeprecationWarning`(테스트 클라이언트가 `httpx`를 씀). 테스트 도구 쪽 경고라 동작 영향 없음.
- 기준선에서 이미 실패하는 항목: **없음**.

## 1단계 — 리뷰 (진행 중)
- 9개 관점 읽기 전용 리뷰 + 항목마다 반박 검증(워크플로 `wf_9c20895e-6f0`).
  관점: 백엔드 보안 · 백엔드 계산 정확성 · 백엔드 신뢰성/데이터 접근 · 수집기 데이터 무결성 · 수집기 API/런타임 · 화면 · 배포/의존성 · AI 파이프라인 · 문서/죽은 코드.
- 지난 두 라운드(QA-001~068)에서 고쳤거나 받아들인 항목은 제외 조건으로 넘김.

## 승인 대기·질문 모음
(없음)
