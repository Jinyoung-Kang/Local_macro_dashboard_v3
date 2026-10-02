#!/usr/bin/env bash
# scripts/qa/qa-stack.sh — 운영 스택과 분리된 QA 스택을 띄우고 지웁니다.
#
# 사용:
#   bash scripts/qa/qa-stack.sh up            .env.qa가 없으면 새 비밀값으로 만들고 빌드·기동
#   bash scripts/qa/qa-stack.sh restore F=…   백업 파일을 QA DB에 복원(운영 DB는 건드리지 않음)
#   bash scripts/qa/qa-stack.sh ps|logs [S]|exec S …|compose …
#   bash scripts/qa/qa-stack.sh api /api/…    QA 백엔드에 로그인해 한 번 호출(비밀번호는 출력하지 않음)
#   bash scripts/qa/qa-stack.sh proxy [mode]  차단 프록시 모드 조회·변경 (block | hang | slow:N)
#   bash scripts/qa/qa-stack.sh down          컨테이너·볼륨 삭제 (운영 볼륨 local_macro_dashboard_v3_*는 그대로)
#
# compose 프로젝트 이름은 macrodash_qa, 포트는 13000(화면)·18080(API)·18000(수집기)·15432(DB)·18888(프록시).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

export COMPOSE_PROJECT_NAME="${QA_PROJECT:-macrodash_qa}"
export COMPOSE_FILE="docker-compose.yml:scripts/qa/docker-compose.qa.yml"
export COMPOSE_ENV_FILES="$ROOT/.env.qa"
export ENV_FILE="$ROOT/.env.qa"          # scripts/db-*.sh가 DB 이름·계정을 여기서 읽습니다
export BACKUP_DIR="${BACKUP_DIR:-$ROOT/backups/qa}"

random_token() { LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c "$1"; }

make_env() {
  [ -f .env.qa ] && return 0
  umask 077
  cat > .env.qa <<ENV
# QA 스택 전용 — 이 세션에서 만든 비밀값. 운영 .env와 무관하며 커밋하지 않습니다(.gitignore).
APP_PASSWORD=$(random_token 24)
JWT_SECRET=$(random_token 48)
COLLECTOR_API_TOKEN=$(random_token 40)
DASHBOARD_READ_MODE=auto
COLLECTOR_SCHEDULER=false
COLLECTOR_RUN_LOG_RETENTION_DAYS=90
# 외부 API 키는 전부 비움 — 유료·한도 API를 부르지 않습니다(프록시가 어차피 막습니다).
FRED_API_KEY=
KRX_API_KEY=
KIS_APP_KEY=
KIS_APP_SECRET=
LS_APP_KEY=
LS_APP_SECRET=
TOSS_CLIENT_ID=
TOSS_CLIENT_SECRET=
SEC_USER_AGENT=
DATA_GO_KR_SERVICE_KEY=
DART_API_KEY=
NVIDIA_API_KEY=
CEREBRAS_API_KEY=
CLOUDFLARE_ACCOUNT_ID=
CLOUDFLARE_API_TOKEN=
WEB_BIND_HOST=127.0.0.1
COOKIE_SECURE=false
DATABASE_USER=macro
DATABASE_PASSWORD=$(random_token 20)
DATABASE_NAME=macrodash
DATABASE_PORT=15432
BACKEND_PORT=18080
COLLECTOR_PORT=18000
FRONTEND_PORT=13000
QA_PROXY_PORT=18888
BACKUP_KEEP=5
ENV
  echo "✅ .env.qa 생성(새 비밀값). 비밀번호는 'bash scripts/qa/qa-stack.sh api …'가 파일에서 읽습니다."
}

env_value() { grep -s "^$1=" .env.qa | head -1 | cut -d= -f2-; }

cmd="${1:-}"; shift || true
case "$cmd" in
  up)
    make_env
    APP_VERSION="qa@$(git rev-parse --short HEAD 2>/dev/null || echo unknown)" docker compose up -d --build "$@"
    docker compose ps
    ;;
  down)
    docker compose down -v --remove-orphans
    ;;
  restore)
    file="${1#F=}"
    [ -n "$file" ] || { echo "사용: restore F=backups/….sql" >&2; exit 1; }
    bash scripts/db-restore.sh "$file" --yes
    ;;
  backup)
    bash scripts/db-backup.sh
    ;;
  ps) docker compose ps ;;
  logs) docker compose logs --tail="${TAIL:-200}" "$@" ;;
  exec) docker compose exec -T "$@" ;;
  compose) docker compose "$@" ;;
  proxy)
    port="$(env_value QA_PROXY_PORT)"; port="${port:-18888}"
    if [ -n "${1:-}" ]; then curl -s "http://127.0.0.1:$port/__mode?set=$1"; else curl -s "http://127.0.0.1:$port/__mode"; fi; echo
    ;;
  api)
    port="$(env_value BACKEND_PORT)"; port="${port:-18080}"
    jar="$(mktemp -t qacookie)"; trap 'rm -f "$jar"' EXIT
    body="$(env_value APP_PASSWORD | python3 -c 'import json,sys; print(json.dumps({"password": sys.stdin.read().strip()}))')"
    curl -s -o /dev/null -c "$jar" -H 'Content-Type: application/json' -H "Origin: http://localhost:$(env_value FRONTEND_PORT)" \
      --data-binary "$body" "http://127.0.0.1:$port/api/auth/login"
    unset body
    curl -s -b "$jar" "${@:2}" "http://127.0.0.1:$port$1"
    ;;
  *)
    sed -n '2,14p' "$0"; exit 1 ;;
esac
