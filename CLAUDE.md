# CLAUDE.md — 작업 규칙 요약

로컬 매크로·13F 대시보드. 화면(Next.js, `frontend/`) → API(Spring Boot, `backend/`) →
PostgreSQL ← 수집기(FastAPI, `collector/`). Docker Compose 네 컨테이너로 돌며, 폴더 이름
`Local_macro_dashboard_v3`가 compose 프로젝트·DB 볼륨 이름을 정한다(바꾸지 말 것).

## 먼저 읽을 문서
- 전체 구조·데이터 흐름: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- 개발·테스트·규약: [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)
- 숫자·화면 원칙: [docs/PRINCIPLES.md](docs/PRINCIPLES.md)
- 설계 결정과 지키는 방법: [docs/adr/README.md](docs/adr/README.md)
- API 경로(`RouteInventoryTest`·`endpoints.test.ts`가 이 문서와 대조): [docs/API.md](docs/API.md)
- 수집 태스크 20개와 소스별 한계: [docs/DATA_SOURCES.md](docs/DATA_SOURCES.md)
- 지난 리뷰·QA의 결함 목록과 남은 위험: [docs/reviews/](docs/reviews/) · [docs/qa/](docs/qa/) (결함 번호 `QA-###`)

## 명령 (전부 `make`, 저장소 최상위에서)
- 기동·진단: `make up` · `make doctor` · `make status` · `make logs S=backend`
- 테스트: `make test` (= `test-collector` + `test-backend` + `test-frontend`). CI와 같은 명령.
- 백업·복원: `make backup` · `make restore F=backups/….sql` (ADR 0003)
- 코드 받기는 `make update`. `git pull`은 쓰지 않는다(다른 브랜치의 작업을 조용히 놓침).
- QA·실험용 별도 스택: `bash scripts/qa/qa-stack.sh up|restore F=…|down` (compose 프로젝트 `macrodash_qa`,
  포트 13000/18080/18000/15432, 새 비밀값 `.env.qa`, 외부 호출은 차단 프록시). 성능 측정: `scripts/bench-api.sh`, `scripts/qa/k6/`.

## 절대 규칙
- **운영 DB(`macrodash`)로 테스트하지 않는다.** 테스트는 `macrodash_test`만 쓴다
  (`make test-*`가 맞춰 줌). 백엔드 통합 테스트는 시작할 때 `snapshots`를 비운다.
  부하·장애·브라우저 시험도 운영 스택(3000/8080/8000/5432)이 아니라 QA 스택에서 한다.
- **비밀값은 `.env`에만.** 저장소는 Public이다. 키·토큰·비밀번호를 코드·문서·로그·커밋에
  넣지 않는다. 예시 이메일 같은 자리표시 값도 코드 기본값으로 두지 않는다.
- **없는 숫자를 만들지 않는다.** 표본 부족·수집 실패는 `null`→화면 `—`. 0으로 채우지 않는다.
  추정치는 `isEstimated`/`isProxy`를 함께 내려보낸다.
- 날짜는 `Kst.today()`(Java)·KST 기준. `LocalDate.now()`를 쓰지 않는다(서버 TZ는 UTC).
- 데이터셋 이름은 `collector/app/catalog.py`와 `backend/.../store/Datasets.java` 둘 다 고친다
  (`DatasetsParityTest`가 대조).
- 백엔드 패키지 의존 규칙(ADR 0001)과 화면 계층(ADR 0004)을 지킨다 — `ArchitectureRulesTest`가 막는다.
  컨트롤러는 자기 기능의 서비스 + `support`·`config` + 응답 캐시 `read.ComputedCache`만 쓴다.
- 수집 실패는 직전 저장본의 값을 버리지 않는다(전부 실패 → `EmptyResult`로 유지, 일부 실패 → 항목별 '이전 값'
  이어받기 + `isStale`). 태스크에는 속도군별 시간 상한이 있다(`TASK_DEADLINE_SECONDS`).
- 옮기는 커밋과 고치는 커밋을 나눈다. 큰 파일은 고치러 들어간 부분만 떼어 낸다. 결함은 재현 테스트
  (`test(QA-###)`, 수정 전엔 xfail/@Disabled) → 근본 원인 수정(`fix(QA-###)`) 두 커밋으로. 모든 커밋이 `make test`를 통과한다.

## 전달 방식
- 작업은 `claude/<주제>-YYYY-MM` 브랜치에서 하고, 끝나면 **본체 폴더의 `main`에 fast-forward 병합해 GitHub `main`에
  push**한 뒤 CI(5개 job)까지 확인하고 커밋 번호와 함께 보고한다. 사용자는 git 명령을 직접 치지 않는다. 큰 변경은 설명하고 동의를 받은 뒤 반영한다.
- 돌고 있는 코드를 바꾸는 변경이면 보고에 "`make up` 하세요"를 넣는다(이미 `make up`까지
  했으면 그 사실을 적는다).
- GitHub 인증은 HTTPS + 키체인 토큰. 403이면 docs/DEVELOPMENT.md §5의 해결 순서.

## 이 환경의 함정
- Claude Code 셸의 `grep`·`find`는 ugrep·bfs로 바뀌어 있어 `.gitignore` 대상(`.venv`, `.next`,
  `node_modules`, `.git`)을 건너뛴다. 파일 시스템 전체를 훑을 때는 `/usr/bin/grep`을 쓴다.
- 테스트 DB·백업 스크립트는 `docker compose exec -T postgres`로 컨테이너 안의 psql을 쓴다.
  맥에 `psql`·`pg_dump`가 없어도 된다(없으면 `test_db_scripts.py` 12개가 건너뛰어진다).
- `.claude/worktrees/…` 안에서는 `git checkout main`이 실패한다. 작업은 본체 폴더에서 한다.
- 테스트 DB를 쓰는 테스트는 postgres 컨테이너가 떠 있어야 한다(`make infra` 또는 `make up`).
- 수집기 `/health`는 DB까지 확인한다(없으면 503). `/health`를 공개 경로 예시로 쓰는 테스트는 `store.ping`을
  스텁한다 — CI에는 운영 DB가 없어 로컬에서만 통과하는 테스트가 된다.
- `docker kill`로 멈춘 컨테이너는 `restart: unless-stopped`가 다시 띄우지 않는다(수동 정지로 간주). 크래시 시험에
  쓰면 안 된다.
- 외부 패키지를 올릴 때는 Maven Central·npm에서 **실제 GA 버전을 확인**한다(마일스톤은 운영에 올리지 않음).
