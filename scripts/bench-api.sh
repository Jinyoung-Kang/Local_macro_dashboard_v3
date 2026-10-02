#!/usr/bin/env bash
#
# scripts/bench-api.sh — 화면 API 응답 시간을 잰다 (성능 변경 전후 비교용).
#
# 사용: bash scripts/bench-api.sh [반복 횟수=5] [> 결과파일]
#
# .env의 APP_PASSWORD로 로그인해 쿠키를 받은 뒤, 아래 목록의 엔드포인트를 반복 호출하고
# 중앙값(ms)과 응답 크기를 표로 찍는다. 첫 호출은 JIT·커넥션 준비 때문에 느리므로 버린다.
# 비밀번호는 출력하지 않는다. 쿠키 파일은 임시 폴더에 만들고 끝나면 지운다.
#
# 숫자는 같은 맥·같은 저장본·수집기가 쉬는 시간에 재야 비교할 수 있다. 수집 직후에는
# 저장본이 바뀌어 응답 크기가 달라질 수 있다.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

get_env() { grep -s "^$1=" .env | head -1 | cut -d= -f2-; }
BACKEND_PORT=$(get_env BACKEND_PORT); BACKEND_PORT=${BACKEND_PORT:-8080}
BASE="http://localhost:${BACKEND_PORT}"
N=${1:-5}

PASSWORD=$(get_env APP_PASSWORD)
if [ -z "$PASSWORD" ]; then echo "APP_PASSWORD가 .env에 없습니다" >&2; exit 1; fi

JAR=$(mktemp -t bench-cookie); trap 'rm -f "$JAR"' EXIT
code=$(curl -s -o /dev/null -w '%{http_code}' -c "$JAR" -H 'Content-Type: application/json' \
  -H "Origin: http://localhost:3000" \
  --data-binary "$(printf '{"password":%s}' "$(printf '%s' "$PASSWORD" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))')")" \
  "$BASE/api/auth/login")
unset PASSWORD
if [ "$code" != "200" ]; then echo "로그인 실패: HTTP $code" >&2; exit 1; fi

# 화면이 실제로 부르는 조합 (docs/API.md). 계산이 많거나 응답이 큰 것 위주.
ENDPOINTS=(
  "/api/macro/overview"
  "/api/macro/risk"
  "/api/macro/advanced"
  "/api/macro/fx?ids=usdkrw,dxy,eurusd,usdjpy&period=5y&mode=index"
  "/api/macro/spread?longId=DGS10&shortId=DGS2"
  "/api/liquidity?years=3"
  "/api/sector/rotation?period=1M"
  "/api/sector/momentum"
  "/api/sec13f/institutions"
  "/api/guru/profiles"
  "/api/guru/similarity"
  "/api/guru/risk?benchmark=SPY&years=1"
  "/api/stock/scorecard?symbol=AAPL&benchmark=SPY&years=1"
  "/api/cot/extremes?percentile=95&lookbackWeeks=52"
  "/api/krx/futures?days=60"
  "/api/krx/investor-trend"
  "/api/radar/ranking"
  "/api/radar/consensus"
  "/api/kr/investor-flows"
  "/api/kr/stock-flows?codes=005930,000660"
  "/api/analytics/regime?years=5"
  "/api/analytics/correlation?x=DGS10&y=DGS2&window=60&years=3&mode=change"
  "/api/status"
  "/api/status/issues"
  "/api/snapshot/text"
)

printf '%-78s %6s %7s %5s\n' "endpoint" "ms(중앙)" "bytes" "code"
for ep in "${ENDPOINTS[@]}"; do
  times=(); size=0; code=0
  for i in $(seq 0 "$N"); do
    out=$(curl -s -o /dev/null -b "$JAR" -H 'Accept-Encoding: gzip' \
      -w '%{time_total} %{size_download} %{http_code}' "$BASE$ep")
    t=$(echo "$out" | awk '{printf "%d", $1*1000}'); size=$(echo "$out" | awk '{print $2}'); code=$(echo "$out" | awk '{print $3}')
    [ "$i" -gt 0 ] && times+=("$t")   # 첫 호출은 버림
  done
  median=$(printf '%s\n' "${times[@]}" | sort -n | awk '{a[NR]=$1} END {print a[int((NR+1)/2)]}')
  printf '%-78s %6s %7s %5s\n' "$ep" "$median" "$size" "$code"
done
