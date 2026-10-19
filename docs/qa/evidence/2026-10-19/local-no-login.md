# 로그인 없는 로컬(ADR 0016) — QA 스택 확인 (2026-10-19)

- 환경: QA 스택을 이 브랜치로 다시 빌드. `QA_NO_LOGIN=1 bash scripts/qa/qa-stack.sh up`(덧붙임 `docker-compose.qa-nologin.yml`),
  백엔드 `store_only`. 운영 스택(3000/8080)은 쓰지 않았습니다.
- 기동 로그: `로그인 없이 운영자로 엽니다 — 이 맥(127.0.0.1)에서만 (DASHBOARD_LOCAL_NO_LOGIN, ADR 0016)` 한 줄.
- 포트: `lsof`로 13000·18080 모두 `127.0.0.1`에서만 LISTEN.

## 실제 Tomcat 위의 경계 (curl, 쿠키 없음)

| 요청 | 기대 | 결과 |
|---|---|---|
| `GET /api/auth/session`(Host localhost) | 운영자로 열림 | `authenticated: true`, `noLogin: true`, `ownerMissing: false`, `user.role: OWNER`, `user.local: true`, `user.demo: false` |
| `GET /api/admin/users` — Host `localhost` · `127.0.0.1` · `[::1]` | 200 | 200 · 200 · 200 |
| 같은 요청 — Host `evil.example` · `localhost.` · `127.1` | 401 | 401 · 401 · 401 |
| 같은 요청 — `X-Forwarded-For` · `X-Real-IP` | 401 | 401 · 401 |
| 같은 요청 — `Origin: http://evil.example:13000` · `Origin: null` · `Sec-Fetch-Site: cross-site` | 403 | 403 · 403 · 403 |
| 같은 요청 — `Origin: http://localhost:13000` | 200 | 200 |
| `POST /api/alerts/inbox/read {"all":true}` — Origin 없음 · evil · LAN(`192.168.0.5:13000`) | 403 | 403 · 403 · 403 |
| 같은 요청 — `Origin: http://localhost:13000` · `http://127.0.0.1:13000` | 200 | 200 · 200 |
| CORS preflight `Origin: http://evil.example:13000` | 허용 헤더 없음 | 403, `Access-Control-Allow-Origin` 없음 |
| CORS preflight `Origin: http://localhost:13000` | 허용 | 200, `Access-Control-Allow-Origin: http://localhost:13000` |

## 브라우저 (앱 내장 브라우저)

- `http://localhost:13000/macro`: 로그인 화면 없이 대시보드가 열림. 사이드바 아래 "로컬 · 로그인 없음"(내 계정 링크), 로그아웃 버튼 없음.
- `http://localhost:13000/login`: 대시보드(`/macro`)로 돌아감.
- `/account`: "로컬 · 로그인 없음" 안내, 로그인한 기기 목록 없음, 비밀번호 변경 카드는 웹·QA·복원용 설명과 함께 남음.
- `/style` 보유 기관 검색 `C`: "일치 14,748건 중 비중 상위 200건만 보입니다 — 종목명을 더 길게 적어 좁히세요."(QA-133).
- `http://127.0.0.1:13000/macro`로 열어도 같은 화면(콘솔 오류 없음).

## 로그인 모드는 그대로 (기본 QA 스택)

- `QA_NO_LOGIN` 없이 다시 띄우면 세션 응답 `noLogin: false`, 쿠키 없는 `GET /api/admin/users` 401, 로그인 뒤 200, 기동 로그에 무로그인 줄 없음.
- `bash scripts/qa/auth-probe.sh`: 실패 0건.
