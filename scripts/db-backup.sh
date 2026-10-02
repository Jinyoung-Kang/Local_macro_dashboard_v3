#!/usr/bin/env bash
# scripts/db-backup.sh — 데이터베이스 백업 (make backup)
#
# 사용: bash scripts/db-backup.sh [접두어]
#   → backups/<접두어>-YYYYmmdd-HHMMSS.sql (접두어 기본값은 DB 이름)
#
# 실패하면 파일을 남기지 않고 1로 끝납니다. 예전에는 pg_dump가 실패해도 0바이트 파일을 남기고
# 성공처럼 끝나, 백업이 있다고 믿다가 복원할 때에야 비어 있는 것을 알게 됐습니다.
set -euo pipefail
# shellcheck source=scripts/db-common.sh
source "$(dirname "${BASH_SOURCE[0]}")/db-common.sh"

prefix="${1:-$DB_NAME}"
file="$(new_backup_path "$prefix")"
dump_to "$file"
prune_backups "$prefix"

echo ""
printf "  파일     : %s (%s)\n" "$file" "$(du -h "$file" | cut -f1)"
echo "  테이블   : $(grep -c '^COPY public' "$file")개"
run_psql -tAc "SELECT '  누적 수급 : ' || count(*) || '행 · ' ||
                      coalesce(min(obs_date)::text,'없음') || ' ~ ' || coalesce(max(obs_date)::text,'없음')
               FROM observations" || true
run_psql -tAc "SELECT '  시계열    : ' || count(*) || '행' FROM timeseries" || true
run_psql -tAc "SELECT '  스냅샷    : ' || count(*) || '건' FROM snapshots" || true
echo ""
echo "  누적 수급 이력은 외부에서 다시 받을 수 없습니다(Naver·Daum·KRX는 과거 조회를"
echo "  지원하지 않습니다). 위 날짜 범위가 기대와 다르면 알려 주세요."
echo ""
