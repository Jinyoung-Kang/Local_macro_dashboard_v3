# =============================================================================
# Makefile — 자주 쓰는 명령 모음
#
#   make setup    최초 1회 준비 (.env 생성 · 키 확인 · 포트 확인)
#   make update   최신 코드 받기 (git pull 대신 이걸 쓰세요)
#   make up       전체 스택 기동
#   make collect  지금 데이터 수집 (첫 실행 후 반드시 한 번)
#   make status   수집 현황 확인
#   make down     정지
#
# Docker 없이 쓰는 개발 명령은 make dev-* 를 보세요.
# =============================================================================

SHELL := /bin/bash
COMPOSE := docker compose

# .env가 있으면 포트 설정을 읽어 안내 문구에 씁니다(없으면 기본값).
FRONTEND_PORT ?= $(shell grep -s '^FRONTEND_PORT=' .env | cut -d= -f2)
FRONTEND_PORT := $(if $(FRONTEND_PORT),$(FRONTEND_PORT),3000)
BACKEND_PORT ?= $(shell grep -s '^BACKEND_PORT=' .env | cut -d= -f2)
BACKEND_PORT := $(if $(BACKEND_PORT),$(BACKEND_PORT),8080)
COLLECTOR_PORT ?= $(shell grep -s '^COLLECTOR_PORT=' .env | cut -d= -f2)
COLLECTOR_PORT := $(if $(COLLECTOR_PORT),$(COLLECTOR_PORT),8000)
DATABASE_PORT ?= $(shell grep -s '^DATABASE_PORT=' .env | cut -d= -f2)
DATABASE_PORT := $(if $(DATABASE_PORT),$(DATABASE_PORT),5432)

# 수집기 API 토큰(.env). 설정돼 있으면 curl에 헤더로 실어 보냅니다.
# 예전에는 make collect·status·verify가 토큰을 보내지 않아, 토큰을 켜는 순간
# 이 명령들이 401로 죽었습니다.
COLLECTOR_API_TOKEN ?= $(shell grep -s '^COLLECTOR_API_TOKEN=' .env | cut -d= -f2-)
TOKEN_HEADER := $(if $(COLLECTOR_API_TOKEN),-H "X-Service-Token: $(COLLECTOR_API_TOKEN)",)

.DEFAULT_GOAL := help
.PHONY: help setup update version up down restart logs ps collect collect-all status verify \
        doctor test db-test test-collector test-backend test-frontend \
        dev-collector dev-backend dev-frontend db infra backup restore reset

# 네이티브 개발용 파이썬. collector/.venv가 있으면 그것을 씁니다.
# (conda base 같은 다른 파이썬이 PATH 앞에 있으면 pytest·uvicorn을
#  "모듈 없음"으로 실패시킵니다. 실제로 겪은 오류입니다.)
VENV_PY := collector/.venv/bin/python
PY := $(shell test -x $(VENV_PY) && echo $(VENV_PY) || echo python3)

# 지금 체크아웃된 코드의 브랜치와 커밋. 빌드할 때 화면 왼쪽 아래에 새겨 두므로,
# "받은 줄 알았는데 예전 코드였다"를 화면만 보고 알 수 있습니다.
GIT_BRANCH := $(shell git rev-parse --abbrev-ref HEAD 2>/dev/null)
GIT_COMMIT := $(shell git rev-parse --short HEAD 2>/dev/null)
export APP_VERSION := $(if $(GIT_BRANCH),$(GIT_BRANCH)@$(GIT_COMMIT),)

help: ## 사용 가능한 명령 목록
	@echo ""
	@echo "Local Macro Dashboard v2"
	@echo ""
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'
	@echo ""

# ------------------------------------------------------------------ 준비·기동
setup: ## 최초 1회 준비 (.env 생성 · 키/포트 확인)
	@bash scripts/setup.sh

update: ## 최신 코드 받기 (git pull 대신 — 다른 브랜치의 새 작업까지 알려 줍니다)
	@bash scripts/update.sh

version: ## 지금 체크아웃된 코드의 브랜치·커밋과 원격 대비 상태
	@bash scripts/version.sh

up: ## 전체 스택 기동 (최초 빌드는 5~10분)
	@test -f .env || (echo "⚠️  .env가 없습니다. 먼저 'make setup'을 실행하세요." && exit 1)
	@# 포트가 겹치면 compose는 나머지를 다 띄운 뒤 백엔드만 실패시켜, 화면이 백엔드
	@# 없이 멈춥니다. 누가 포트를 잡았는지 먼저 확인하고 겹치면 시작하지 않습니다.
	@bash scripts/ports.sh check
	$(COMPOSE) up -d --build
	@echo ""
	@bash scripts/ports.sh wait
	@echo ""
	@echo "  화면    : http://localhost:$(FRONTEND_PORT)"
	@echo "  API     : http://localhost:$(BACKEND_PORT)/api/health"
	@echo "  수집기  : http://localhost:$(COLLECTOR_PORT)/status"
	@echo ""
	@echo "  빌드한 코드 : $(if $(APP_VERSION),$(APP_VERSION),알 수 없음 (git 저장소가 아님))"
	@echo "                (화면 왼쪽 아래에도 같은 값이 표시됩니다)"
	@echo ""
	@bash scripts/version.sh --brief
	@echo "  첫 실행이라면 'make collect'로 데이터를 한 번 받아 오세요."
	@echo ""

down: ## 정지 (데이터는 보존)
	$(COMPOSE) down

restart: ## 재기동
	$(COMPOSE) restart

ps: ## 컨테이너 상태
	$(COMPOSE) ps

logs: ## 로그 따라 보기 (make logs S=backend 로 특정 서비스만)
	$(COMPOSE) logs -f --tail=100 $(S)

infra: ## PostgreSQL만 기동 (네이티브 개발용)
	$(COMPOSE) up -d postgres

# ------------------------------------------------------------------ 데이터
collect: ## 시세·수급 수집 (fast — 약 1분)
	@curl -fsS $(TOKEN_HEADER) -X POST "http://localhost:$(COLLECTOR_PORT)/collect?group=fast" \
		| python3 -m json.tool --no-ensure-ascii 2>/dev/null || echo "수집기에 연결하지 못했습니다."

collect-all: ## 전체 수집 (13F 포함 — 10분 이상)
	@curl -fsS $(TOKEN_HEADER) -X POST "http://localhost:$(COLLECTOR_PORT)/collect?group=all&wait=false" \
		| python3 -m json.tool --no-ensure-ascii 2>/dev/null || echo "수집기에 연결하지 못했습니다."
	@echo "백그라운드로 실행 중입니다. 'make status'로 진행 상황을 확인하세요."

status: ## 수집 현황 (구버전 collector.py --status)
	@curl -fsS $(TOKEN_HEADER) "http://localhost:$(COLLECTOR_PORT)/status" \
		| python3 -c "import json,sys; d=json.load(sys.stdin); \
print('마지막 실행:', d.get('lastRunStatus')); \
print('누락 데이터셋:', len(d.get('missingDatasets') or [])); \
[print(' ', t['status'], t['task'], (t.get('detail') or '')[:60]) for t in (d.get('taskSummary') or [])]" \
		2>/dev/null || echo "수집기에 연결하지 못했습니다."

verify: ## 교차 검증 실행 (KRX·KIS 대조)
	@curl -fsS $(TOKEN_HEADER) "http://localhost:$(COLLECTOR_PORT)/verify/readings" \
		| python3 -m json.tool --no-ensure-ascii 2>/dev/null || echo "수집기에 연결하지 못했습니다."

# ------------------------------------------------------------------ 진단
doctor: ## 어디가 막혔는지 한 번에 진단 (데이터가 안 보일 때 먼저 실행)
	@bash scripts/doctor.sh

# ------------------------------------------------------------------ 테스트
test: test-collector test-backend test-frontend ## 전체 테스트

# ⚠️ 테스트는 운영 DB(macrodash)를 쓰지 않습니다.
#
# 백엔드 통합 테스트는 매 테스트 시작 시 snapshots를 비웁니다. 접속 기본값이
# 운영 DB였을 때는 `make test-backend` 한 번으로 수집해 둔 데이터가 전부
# 사라졌습니다(되살리려면 make collect-all — 10분 이상). 그래서 테스트 전용
# DB를 따로 만들어 씁니다. 테스트 쪽에도 "이름이 _test로 끝나지 않으면 실행을
# 거부"하는 안전장치를 두었습니다(ApiIntegrationTest).
TEST_DB_NAME := macrodash_test

# 마이그레이션은 번호 순서로 적용합니다(V10은 V9 다음). 모두 여러 번 실행해도 안전합니다.
MIGRATIONS = $(shell ls db/migrations/V*__*.sql | sort -t V -k 2 -n)

db-test: ## 테스트 전용 DB 준비 (없으면 만들고 스키마 적용)
	@$(COMPOSE) exec -T postgres psql -U $${DATABASE_USER:-macro} -d postgres -tAc \
		"SELECT 1 FROM pg_database WHERE datname='$(TEST_DB_NAME)'" | grep -q 1 || \
		$(COMPOSE) exec -T postgres psql -U $${DATABASE_USER:-macro} -d postgres \
			-c "CREATE DATABASE $(TEST_DB_NAME) OWNER $${DATABASE_USER:-macro}" >/dev/null
	@for f in $(MIGRATIONS); do \
		$(COMPOSE) exec -T postgres psql -q -v ON_ERROR_STOP=1 -U $${DATABASE_USER:-macro} \
			-d $(TEST_DB_NAME) -f /dev/stdin < "$$f" >/dev/null || exit 1; \
	done
	@echo "✅ 테스트 DB 준비: $(TEST_DB_NAME) (운영 DB는 건드리지 않습니다)"

test-collector: db-test ## 수집기 테스트 (PostgreSQL 필요)
	@test -x $(VENV_PY) || echo "ℹ️  collector/.venv가 없어 $(PY)로 실행합니다. 'No module named pytest'가 나오면 docs/LOCAL_SETUP.md의 가상환경 절을 보세요."
	cd collector && TEST_DATABASE_URL=$${TEST_DATABASE_URL:-postgresql://macro:macro@localhost:$(DATABASE_PORT)/$(TEST_DB_NAME)} \
		$(if $(filter $(VENV_PY),$(PY)),.venv/bin/python,python3) -m pytest tests -q

test-backend: db-test ## 백엔드 테스트 (PostgreSQL 필요)
	cd backend && TEST_DATABASE_URL=$${TEST_DATABASE_URL:-jdbc:postgresql://localhost:$(DATABASE_PORT)/$(TEST_DB_NAME)} \
		mvn -B verify

test-frontend: ## 화면 자가검증 + 린트 + 빌드(타입 검사 포함)
	cd frontend && npm run check && npm run lint && npm run build

# ------------------------------------------------------------- 네이티브 개발
dev-collector: ## 수집기 개발 서버 (자동 리로드 · collector/.venv 필요)
	@test -x $(VENV_PY) || (echo "⚠️  collector/.venv가 없습니다. 먼저:" && \
		echo "    python3 -m venv collector/.venv && collector/.venv/bin/pip install -r collector/requirements.txt" && exit 1)
	cd collector && DATABASE_URL=postgresql://macro:macro@localhost:5432/macrodash \
		.venv/bin/python -m uvicorn app.main:app --reload --port $(COLLECTOR_PORT)

dev-backend: ## 백엔드 개발 서버
	cd backend && mvn spring-boot:run

dev-frontend: ## 화면 개발 서버 (핫 리로드)
	cd frontend && NEXT_PUBLIC_API_BASE=http://localhost:$(BACKEND_PORT) npm run dev

db: ## PostgreSQL 셸
	$(COMPOSE) exec postgres psql -U macro -d macrodash

# ------------------------------------------------------------------ 백업·정리
backup: ## 데이터베이스 백업 (backups/ 폴더에 저장 · 내용 요약 표시)
	@mkdir -p backups
	@f="backups/macrodash-$$(date +%Y%m%d-%H%M%S).sql"; \
	$(COMPOSE) exec -T postgres pg_dump -U macro -d $${DATABASE_NAME:-macrodash} > "$$f"; \
	echo ""; \
	ls -lh "$$f" | awk '{printf "  파일     : %s (%s)\n", $$NF, $$5}'; \
	if tail -c 200 "$$f" | grep -q 'PostgreSQL database dump complete\|unrestrict'; then \
		echo "  상태     : 정상 종료 표시 확인"; \
	else \
		echo "  ⚠️ 상태  : 덤프가 중간에 끊겼을 수 있습니다. 다시 실행하세요"; \
	fi; \
	echo "  테이블   : $$(grep -c '^COPY public' "$$f")개"; \
	echo ""; \
	$(COMPOSE) exec -T postgres psql -U macro -d $${DATABASE_NAME:-macrodash} -tAc \
		"SELECT '  누적 수급 : ' || count(*) || '행 · ' || \
		        coalesce(min(obs_date)::text,'없음') || ' ~ ' || coalesce(max(obs_date)::text,'없음') \
		 FROM observations" ; \
	$(COMPOSE) exec -T postgres psql -U macro -d $${DATABASE_NAME:-macrodash} -tAc \
		"SELECT '  시계열    : ' || count(*) || '행' FROM timeseries"; \
	$(COMPOSE) exec -T postgres psql -U macro -d $${DATABASE_NAME:-macrodash} -tAc \
		"SELECT '  스냅샷    : ' || count(*) || '건' FROM snapshots"
	@echo ""
	@echo "  누적 수급 이력은 외부에서 다시 받을 수 없습니다(Naver·Daum·KRX는 과거 조회를"
	@echo "  지원하지 않습니다). 위 날짜 범위가 기대와 다르면 알려 주세요."
	@echo ""

restore: ## 백업 복원 (make restore F=backups/xxx.sql)
	@test -n "$(F)" || (echo "사용법: make restore F=backups/파일.sql" && exit 1)
	$(COMPOSE) exec -T postgres psql -U macro -d macrodash < $(F)

reset: ## ⚠️ 전체 삭제 후 재기동 (수집 이력까지 사라집니다)
	@printf "정말 모든 데이터를 지울까요? 누적 수급 이력은 복구할 수 없습니다 [y/N] " && read ans && [ "$$ans" = "y" ]
	$(COMPOSE) down -v
	$(COMPOSE) up -d --build
