#!/usr/bin/env bash
# 수집기 간접 의존성 고정 파일(collector/constraints.txt)을 requirements.txt로 다시 만듭니다.
#
# requirements.txt는 직접 의존성만 고정합니다. 그래서 같은 커밋이라도 빌드할 때마다 starlette·pydantic·urllib3 같은
# 간접 의존성이 그날의 최신판으로 정해졌습니다. 운영 이미지와 같은 바탕(python:3.11-slim)에서 requirements.txt를
# 설치한 결과를 그대로 적어, Dockerfile·CI가 `-c constraints.txt`로 같은 판을 받게 합니다.
# requirements.txt를 바꾸면 이 스크립트를 다시 돌려 함께 커밋합니다(`make constraints`).
set -euo pipefail
cd "$(dirname "$0")/.."

body=$(docker run --rm -e DEBIAN_FRONTEND=noninteractive -v "$PWD/collector:/src:ro" python:3.11-slim sh -c '
  apt-get update -qq >/dev/null &&
  apt-get install -y -qq --no-install-recommends build-essential libxml2-dev libxslt1-dev >/dev/null &&
  pip install -q --no-cache-dir --root-user-action=ignore -r /src/requirements.txt >/dev/null &&
  pip freeze')

{
  echo "# 자동 생성 — scripts/collector-constraints.sh (make constraints). 손으로 고치지 않습니다."
  echo "# requirements.txt의 직접 의존성을 python:3.11-slim에 설치한 결과(간접 의존성 포함)."
  echo "$body"
} > collector/constraints.txt
echo "collector/constraints.txt: $(grep -c '==' collector/constraints.txt)개 패키지"
