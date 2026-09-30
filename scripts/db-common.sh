# shellcheck shell=bash
# scripts/db-common.sh — db-backup.sh · db-restore.sh가 같이 쓰는 설정과 함수.
#
# 직접 실행하지 않고 `source`로 읽습니다.
#
# 어떤 DB에 붙는가
#   DATABASE_USER · DATABASE_NAME  환경변수 → .env → 기본값(macro · macrodash) 순서로 정합니다.
#                                   예전 Makefile은 사용자·DB 이름을 macro·macrodash로 박아 두어
#                                   .env에서 바꾸면 엉뚱한 DB를 백업·복원했습니다.
#   DB_EXEC                         psql·pg_dump 앞에 붙는 명령. 기본은 postgres 컨테이너 안에서
#                                   실행(`docker compose exec -T postgres`). 빈 값이면 이 맥의
#                                   psql·pg_dump를 바로 씁니다(테스트가 이렇게 씁니다).
#   BACKUP_DIR                      백업 파일을 두는 폴더(기본 backups/)

DB_SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$DB_SCRIPTS_DIR/.." && pwd)"

# .env의 KEY=VALUE 한 줄을 읽습니다(없으면 빈 문자열). docker compose와 같은 방식으로 따옴표 없이 씁니다.
env_file_value() {
    grep -s "^$1=" "$PROJECT_ROOT/.env" | tail -1 | cut -d= -f2- || true
}

DB_USER="${DATABASE_USER:-$(env_file_value DATABASE_USER)}"
DB_USER="${DB_USER:-macro}"
DB_NAME="${DATABASE_NAME:-$(env_file_value DATABASE_NAME)}"
DB_NAME="${DB_NAME:-macrodash}"
BACKUP_DIR="${BACKUP_DIR:-$PROJECT_ROOT/backups}"
DB_EXEC="${DB_EXEC-docker compose exec -T postgres}"

# docker compose는 compose 파일이 있는 폴더에서 실행해야 합니다.
cd "$PROJECT_ROOT" || exit 1

run_psql() {
    # shellcheck disable=SC2086  # DB_EXEC는 여러 단어로 나뉘어야 합니다
    $DB_EXEC psql -U "$DB_USER" -d "$DB_NAME" "$@"
}

run_pg_dump() {
    # shellcheck disable=SC2086
    $DB_EXEC pg_dump -U "$DB_USER" -d "$DB_NAME"
}

# pg_dump가 끝까지 썼는지. 중간에 끊긴 파일에는 이 끝 표시가 없습니다.
dump_is_complete() {
    tail -c 1000 "$1" | grep -q "PostgreSQL database dump complete"
}

# 지금 DB를 FILE로 덤프합니다. 실패하면 반쯤 쓴 파일을 지우고 1을 돌려줍니다.
#
# 바로 FILE에 쓰지 않고 .partial에 쓴 뒤 옮깁니다. 중간에 실패한 덤프가 정상 백업과 같은 이름으로
# 남으면, 나중에 그 파일로 복원해도 아무것도 돌아오지 않습니다.
dump_to() {
    local file="$1" partial="$1.partial"
    if ! run_pg_dump > "$partial"; then
        rm -f "$partial"
        echo "❌ 백업 실패: pg_dump가 오류로 끝났습니다(위 메시지 참고). 파일을 남기지 않았습니다." >&2
        return 1
    fi
    if ! dump_is_complete "$partial"; then
        rm -f "$partial"
        echo "❌ 백업 실패: 덤프가 끝까지 기록되지 않았습니다. 파일을 남기지 않았습니다." >&2
        return 1
    fi
    mv "$partial" "$file"
}

# 백업 파일 이름: <접두어>-YYYYmmdd-HHMMSS.sql
new_backup_path() {
    mkdir -p "$BACKUP_DIR"
    echo "$BACKUP_DIR/$1-$(date +%Y%m%d-%H%M%S).sql"
}
