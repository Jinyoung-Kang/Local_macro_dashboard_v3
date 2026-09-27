# 수집기 이미지 (Python 3.11 + FastAPI)
FROM python:3.11-slim

# pykrx·lxml 빌드와 TLS 검증에 필요한 최소 패키지만 설치합니다.
RUN apt-get update \
    && apt-get install -y --no-install-recommends build-essential libxml2-dev libxslt1-dev ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# 운영 의존성만 설치합니다. 테스트 도구(pytest 등)는 requirements-dev.txt에 있습니다.
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt \
    && apt-get purge -y build-essential && apt-get autoremove -y

# root로 돌지 않습니다. 앱이 쓰는 곳은 /tmp(yfinance 캐시)와 홈(라이브러리 설정 캐시)뿐이고,
# 코드(/app)는 읽기만 합니다. 컨테이너가 뚫려도 이미지 안의 파일을 바꿀 수 없습니다.
RUN useradd --create-home --uid 10001 --shell /usr/sbin/nologin app

COPY app ./app

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    TZ=Asia/Seoul

USER app

EXPOSE 8000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
