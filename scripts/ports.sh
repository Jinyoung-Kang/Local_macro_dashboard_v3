#!/usr/bin/env bash
#
# scripts/ports.sh
# 기동 전 포트 점검 · 기동 후 응답 대기.
#
#   bash scripts/ports.sh check   기동 전에 — 쓸 포트를 **다른** 컨테이너·프로그램이 잡고
#                                 있으면 누가 잡았는지와 해결 방법을 보여 주고 1로 끝납니다
#   bash scripts/ports.sh wait    기동 후에 — 백엔드·화면이 실제로 응답할 때까지 기다립니다
#
# 왜 필요한가 — 포트가 겹치면 docker compose는 다른 컨테이너를 다 띄운 뒤 마지막에
#   "Bind for 127.0.0.1:8080 failed: port is already allocated"
# 한 줄만 남기고 끝납니다. 화면(3000)은 떠 있으니 브라우저에서는 백엔드가 없는 채로
# 멈춘 화면만 보입니다. 누가 포트를 잡고 있는지는 알려 주지 않습니다.
#
# 주의사항 — macOS 기본 bash(3.2)에서도 돌아야 합니다(연관 배열·mapfile 금지).
#
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

GREEN=$(printf '\033[32m'); YELLOW=$(printf '\033[33m'); RED=$(printf '\033[31m')
DIM=$(printf '\033[2m'); BOLD=$(printf '\033[1m'); RESET=$(printf '\033[0m')
ok()   { printf '  %s✓%s %s\n' "$GREEN" "$RESET" "$1"; }
warn() { printf '  %s!%s %s\n' "$YELLOW" "$RESET" "$1"; }
fail() { printf '  %s✗%s %s\n' "$RED" "$RESET" "$1"; }
note() { printf '    %s%s%s\n' "$DIM" "$1" "$RESET"; }

get_env() { grep -s "^$1=" .env | head -1 | cut -d= -f2- | tr -d '"'"'"; }
port_of() { local v; v=$(get_env "$1"); printf '%s' "${v:-$2}"; }

FRONTEND_PORT=$(port_of FRONTEND_PORT 3000)
BACKEND_PORT=$(port_of BACKEND_PORT 8080)
COLLECTOR_PORT=$(port_of COLLECTOR_PORT 8000)
DATABASE_PORT=$(port_of DATABASE_PORT 5432)

# 이 프로젝트의 컨테이너 ID(멈춘 것 포함). 우리 컨테이너가 잡은 포트는 충돌이 아닙니다.
OWN_IDS=$(docker compose ps -aq 2>/dev/null || true)
is_own() {
  local short="$1" id
  for id in $OWN_IDS; do
    case "$id" in "$short"*) return 0 ;; esac
  done
  return 1
}

# 그 포트를 게시한 **다른** 컨테이너: "이름 (프로젝트)" 줄들
foreign_containers() {
  docker ps --filter "publish=$1" --format '{{.ID}}|{{.Names}}|{{.Label "com.docker.compose.project"}}' 2>/dev/null \
    | while IFS='|' read -r cid name project; do
        [ -z "$cid" ] && continue
        is_own "$cid" && continue
        printf '%s%s\n' "$name" "${project:+ (compose 프로젝트: $project)}"
      done
}

own_publishes() {
  docker ps --filter "publish=$1" --format '{{.ID}}' 2>/dev/null \
    | while read -r cid; do is_own "$cid" && echo yes && break; done
}

# 그 포트에서 LISTEN 중인 프로세스: "명령 (PID n)" 줄들
listeners() {
  command -v lsof >/dev/null 2>&1 || return 0
  lsof -nP -iTCP:"$1" -sTCP:LISTEN -Fpc 2>/dev/null \
    | awk '/^p/ {pid=substr($0,2)} /^c/ {print substr($0,2) " (PID " pid ")"}' | sort -u
}

CONFLICT=0
check_one() {
  local port="$1" label="$2" envvar="$3" others procs
  if [ -n "$(own_publishes "$port")" ]; then
    ok "$port ($label) — 이 프로젝트의 컨테이너가 사용 중 (정상)"
    return
  fi
  others=$(foreign_containers "$port")
  if [ -n "$others" ]; then
    CONFLICT=1
    fail "$port ($label) — 다른 컨테이너가 이 포트를 잡고 있습니다"
    printf '%s\n' "$others" | sed 's/^/      · /'
    note "그 컨테이너가 필요 없다면:  docker stop <이름>"
    note "둘 다 써야 한다면: .env의 ${envvar}를 비어 있는 다른 번호로 바꾸세요"
    return
  fi
  procs=$(listeners "$port")
  # lsof는 다른 사용자(root 등)의 프로세스를 못 볼 수 있습니다. 그때는 직접 접속해 봅니다.
  if [ -z "$procs" ] && (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
    procs="(프로그램 이름은 확인하지 못함 — sudo lsof -nP -iTCP:$port -sTCP:LISTEN 으로 확인)"
  fi
  if [ -n "$procs" ]; then
    CONFLICT=1
    if printf '%s\n' "$procs" | grep -qiE 'com\.docker|vpnkit|docker-proxy'; then
      fail "$port ($label) — Docker가 포트를 놓지 않았습니다 (이 포트를 쓰는 컨테이너는 없음)"
      note "이전 컨테이너의 흔적입니다. Docker Desktop을 완전히 종료했다가 다시 켠 뒤 make up"
    else
      fail "$port ($label) — 다른 프로그램이 이 포트를 쓰고 있습니다"
      printf '%s\n' "$procs" | sed 's/^/      · /'
      note "그 프로그램을 끄거나(예: IDE에서 실행 중인 Spring Boot), .env의 ${envvar}를 바꾸세요"
    fi
    return
  fi
  ok "$port ($label) 비어 있음"
}

# 화면이 백엔드를 찾는 주소가 BACKEND_PORT와 어긋나면, 컨테이너는 다 떠도 화면은
# 백엔드를 못 찾습니다. localhost 주소일 때만 검사합니다(리버스 프록시 주소는 의도).
check_addresses() {
  local api origin api_port origin_port
  api=$(get_env NEXT_PUBLIC_API_BASE); origin=$(get_env FRONTEND_ORIGIN)
  api_port=$(printf '%s' "$api" | sed -nE 's#^https?://(localhost|127\.0\.0\.1):([0-9]+).*#\2#p')
  origin_port=$(printf '%s' "$origin" | sed -nE 's#^https?://(localhost|127\.0\.0\.1):([0-9]+).*#\2#p')
  if [ -n "$api_port" ] && [ "$api_port" != "$BACKEND_PORT" ]; then
    CONFLICT=1
    fail "NEXT_PUBLIC_API_BASE($api)가 BACKEND_PORT($BACKEND_PORT)와 다릅니다"
    note ".env에서 NEXT_PUBLIC_API_BASE 줄을 지우면 BACKEND_PORT를 따라갑니다"
  fi
  if [ -n "$origin_port" ] && [ "$origin_port" != "$FRONTEND_PORT" ]; then
    CONFLICT=1
    fail "FRONTEND_ORIGIN($origin)이 FRONTEND_PORT($FRONTEND_PORT)와 다릅니다 — 로그인이 막힙니다"
    note ".env에서 FRONTEND_ORIGIN 줄을 지우면 FRONTEND_PORT를 따라갑니다"
  fi
}

cmd_check() {
  if ! docker info >/dev/null 2>&1; then
    fail "Docker에 연결하지 못했습니다 — Docker Desktop을 켠 뒤 다시 실행하세요"
    exit 1
  fi
  printf '%s포트 점검%s\n' "$BOLD" "$RESET"
  check_one "$FRONTEND_PORT" "화면" FRONTEND_PORT
  check_one "$BACKEND_PORT" "백엔드 API" BACKEND_PORT
  check_one "$COLLECTOR_PORT" "수집기" COLLECTOR_PORT
  check_one "$DATABASE_PORT" "PostgreSQL" DATABASE_PORT
  check_addresses
  if [ "$CONFLICT" -eq 1 ]; then
    printf '\n  %s위 문제를 해결한 뒤 다시 make up 하세요.%s 포트 번호를 바꿨다면 --build가 필요합니다\n' "$BOLD" "$RESET"
    note "(화면이 백엔드 주소를 빌드할 때 새겨 두기 때문입니다 — make up은 항상 --build로 띄웁니다)"
    exit 1
  fi
}

# 한 서비스가 응답할 때까지 기다립니다. 성공하면 0.
wait_http() {
  local url="$1" seconds="$2" i=0
  while [ "$i" -lt "$seconds" ]; do
    curl -fsS -o /dev/null --max-time 3 "$url" 2>/dev/null && return 0
    sleep 2; i=$((i + 2))
  done
  return 1
}

# 백엔드 헬스체크는 DB에 닿지 못하면 503을 돌려줍니다(본문에 "database": "unreachable").
# 그건 "연결 못 함"이 아니라 "떠 있는데 DB가 문제"이므로 사유를 그대로 보여 줍니다.
wait_backend_health() {
  local url="$1" seconds="$2" i=0 code
  while [ "$i" -lt "$seconds" ]; do
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$url" 2>/dev/null)
    case "$code" in
      200) return 0 ;;
      503) BACKEND_HEALTH_BODY=$(curl -s --max-time 3 "$url" 2>/dev/null); return 2 ;;
    esac
    sleep 2; i=$((i + 2))
  done
  return 1
}

cmd_wait() {
  printf '%s기동 확인%s\n' "$BOLD" "$RESET"
  local bad=0
  # 백엔드는 JVM 기동에 20~40초가 걸립니다.
  BACKEND_HEALTH_BODY=""
  wait_backend_health "http://localhost:$BACKEND_PORT/api/health" 120
  case $? in
    0) ok "백엔드 응답함 — http://localhost:$BACKEND_PORT/api/health" ;;
    2) bad=1
       fail "백엔드는 떠 있지만 DB에 닿지 못합니다: $(printf '%s' "$BACKEND_HEALTH_BODY" | cut -c1-120)"
       note "make logs S=postgres · make logs S=backend" ;;
    *) bad=1
       fail "백엔드가 2분 안에 응답하지 않았습니다"
       note "make logs S=backend  로 기동 실패 원인을 확인하세요" ;;
  esac
  if wait_http "http://localhost:$FRONTEND_PORT/login" 60; then
    ok "화면 응답함 — http://localhost:$FRONTEND_PORT"
  else
    bad=1
    fail "화면이 1분 안에 응답하지 않았습니다 — make logs S=frontend"
  fi
  [ "$bad" -eq 0 ]
}

case "${1:-check}" in
  check) cmd_check ;;
  wait) cmd_wait ;;
  *) echo "사용법: bash scripts/ports.sh [check|wait]"; exit 2 ;;
esac
