# 0012. AWS 데모 배포 — RDS · 앞단 프록시 뒤 같은 출처 · 공개 데모에서 닫는 것

- 상태: 채택 (2026-10). 방문자 로그인(결정 4의 마지막 줄)은 [ADR 0013](0013-demo-viewer-mode.md) 데모 열람이 대체합니다.
  공개 데모의 AI(결정 4의 "유료 AI 키"·"AI 쓰기")는 [ADR 0014](0014-aws-demo-ai-report.md)가 바꿉니다.
- 지키는 방법: `compose.aws.yml`, `frontend/src/lib/__tests__/api.test.ts`(`NEXT_PUBLIC_API_BASE=/`)

## 배경

포트폴리오 공개용으로 BuildRisk · Wakeline 과 함께 AWS 서울 리전에 올린다. 운영 조건은 다음과 같다.

- 서버: EC2 한 대(앱 컨테이너)
- DB: RDS PostgreSQL 18 한 대(db.t4g.micro). 세 프로젝트가 DB를 하나씩 나눠 쓴다.
- 운영 시간: 평일 09–18시
- 접속 경로: 도메인 없이 CloudFront 기본 도메인(HTTPS) → EC2의 Caddy → 접근 코드 잠금화면 → 앱

로컬 구성을 그대로 올리면 세 가지가 맞지 않았다.

1. **API 주소**: 화면이 API를 `화면 호스트:8080`으로 부른다(ADR 0006). 그런데 공개 주소는 HTTPS 443 하나뿐이다.
2. **클라이언트 IP**: 로그인 제한이 접속 주소(`getRemoteAddr`)를 키로 쓴다. 프록시 뒤에서는 모든 방문자가 프록시 한 주소로 보여, 한 사람의 실패가 모두를 느리게 만든다.
3. **공개 데모의 위험**: 메일 가입, 유료 AI, 화면 조회가 일으키는 수집, 데모 계정 변경이 남아 있다.

## 결정

1. 배포 설정은 **덧붙임 파일 `compose.aws.yml`** 하나에 모은다. `docker-compose.yml`(로컬)은 바꾸지 않는다.
   - postgres 컨테이너는 profile로 빼고 `depends_on`을 비운다. collector와 backend는 `RDS_HOST`에 `sslmode=require`로 붙는다.
   - Hikari 풀은 4로 줄인다. RDS 한 대를 세 프로젝트가 나눠 쓰기 때문이다.
2. **`NEXT_PUBLIC_API_BASE=/`를 "같은 출처"로 정의한다.** `apiBase()`가 빈 문자열을 돌려줘 `/api/...` 상대 경로가 된다.
   - 앞단 Caddy가 `/api/*`는 backend로, 나머지는 frontend로 보낸다.
   - 이미지에 공개 주소를 박지 않으므로, 같은 이미지가 공개 주소와 관리자 터널(SSM 포트 포워딩, `localhost:8201`) 양쪽에서 돈다.
3. **Tomcat이 프록시의 `X-Forwarded-For`를 믿게 한다**(`SERVER_FORWARDHEADERSSTRATEGY=native`).
   - Tomcat RemoteIpValve의 기본 내부 대역(10/8 · 172.16/12 · 192.168/16 · 127/8)에서 온 요청만 헤더를 믿는다.
   - 화면·API 포트는 `127.0.0.1`에만 연다. 그래서 그 대역으로 닿을 수 있는 것은 같은 호스트의 Caddy뿐이다.
   - Caddy는 CloudFront 대역에서 온 값만 믿고, 결정한 클라이언트 IP 하나로 헤더를 덮어써 보낸다.
4. **공개 데모에서 닫는 것**
   - 앱 설정으로 닫는 것: 메일(`MAIL_HOST` 비움 → 가입·재설정 불가), ~~유료 AI 키~~, `DASHBOARD_READ_MODE=store_only`
     → AI는 Claude Haiku 5.5 하나만(`AI_ENGINES`)·데모 방문자 공용 하루 한도로 엽니다([ADR 0014](0014-aws-demo-ai-report.md)).
     무료 엔진 키는 계속 비웁니다.
   - 앞단 Caddy에서 막는 것:
     - 운영자 경로(`/api/status*`, `/api/admin/*` 등): 404
     - 계정을 바꾸는 쓰기(비밀번호 변경, 탈퇴, 전체 로그아웃, 가입, 재설정, ~~AI 쓰기~~): 403
       → AI 리포트 생성(`POST /api/ai/report`)은 통과시킵니다([ADR 0014](0014-aws-demo-ai-report.md)).
   - ~~방문자는 접근 코드로 잠금을 푼 뒤 데모 member 계정으로 로그인한다.~~ → 방문자는 잠금을 풀면 로그인 없이
     읽기 전용으로 봅니다(데모 열람, [ADR 0013](0013-demo-viewer-mode.md)). 운영자는 관리자 터널에서 계정으로 로그인합니다.

## 이유

- **덧붙임 파일을 쓴 이유**: 로컬 실행·테스트(`make up`, QA 스택)가 지금과 똑같이 돈다. 배포에서 바뀌는 점은 한 파일만 읽으면 보인다.
- **`/`를 쓴 이유**: 공개 주소(`https://dxxxx.cloudfront.net`)를 빌드 인자로 박으면 관리자 터널에서는 API가 다른 출처가 된다. 쿠키가 `SameSite=Strict`라 로그인이 깨진다(ADR 0006에서 겪은 문제와 같음).
- **Tomcat 내부 대역 기본값을 그대로 쓴 이유**: 배포에서는 앱 포트가 루프백에만 열려 있어 위조할 수 있는 경로가 없다. 로컬(`0.0.0.0`, LAN 휴대폰 접속)에는 이 설정을 넣지 않는다. LAN 기기는 192.168 대역이라 헤더를 위조할 수 있기 때문이다.

## 대안과 버린 이유

- **공개 주소를 `NEXT_PUBLIC_API_BASE`에 박기**: 관리자 터널에서 로그인이 깨진다. 주소가 바뀌면 다시 빌드해야 한다.
- **앱 안에 잠금화면 만들기**: 세 프로젝트의 스택(Next+Spring ×2, Next+Spring+FastAPI)마다 따로 만들어야 한다. 앞단 한 곳(Caddy forward_auth + gate)에서 화면·API·정적 파일을 모두 막는 편이 단순하다.
- **RDS 대신 postgres 컨테이너 유지**: 비용은 조금 싸지만 포트폴리오에서 보이려는 관리형 DB 경험이 빠진다.

## 결과(검증)

2026-10-10 로컬 리허설에서 확인했다. 리허설 환경은 다음과 같다.
- DB: RDS 흉내(PostgreSQL 18.6 + PostGIS, superuser 아닌 마스터, TLS 강제, `max_connections=80`)
- 앞단: Caddy + gate

확인한 것:
- 최신 백업(pg_dump 16, 44MB)을 psql 18로 5초 만에 복원했다. collector가 V4–V7을 적용했다.
- DB 연결 6개가 모두 TLS(`pg_stat_ssl.ssl = t`)였다.
- 잠금 → 해제 → 운영자·데모 계정 로그인 → 데이터 200을 확인했다.
- 운영자 경로는 404, 데모 계정의 비밀번호 변경·탈퇴는 403이었다.
- 클라이언트 IP별 로그인 제한: A IP는 6번째 실패부터 429(1초 지연), B IP는 영향 없음.
- 빌드된 번들의 fetch가 `"".concat("").concat(path)` — 상대 경로였다.
- 멈춤 → 시작을 2회 해서 각각 약 40초 만에 healthy가 됐다.
- 메모리(수집 스케줄 끔): backend 260MiB, collector 252MiB, frontend 56MiB.
