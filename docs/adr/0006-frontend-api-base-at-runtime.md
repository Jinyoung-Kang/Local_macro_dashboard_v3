# 0006. 화면이 백엔드 주소를 실행 시점에 정한다 (휴대폰·LAN 접속)

- 상태: 채택 (2026-10)
- 지키는 방법: `frontend/src/lib/__tests__/api.test.ts`, `backend/src/test/java/com/macrodash/config/CorsOriginsTest.java`

## 배경

백엔드 주소가 빌드 시점의 `http://localhost:8080`으로 번들에 박혀 있었습니다(`NEXT_PUBLIC_API_BASE`).
휴대폰에서 `http://192.168.x.x:3000`을 열면 화면이 **휴대폰 자신의** localhost:8080을 찾아 "백엔드에 연결하지
못했습니다"가 떴습니다. 문서의 우회(LAN IP를 박아 다시 빌드)는 맥에서 `localhost:3000`을 열 때 API가 다른
사이트가 되어 쿠키(SameSite=Strict)가 보내지지 않아 로그인이 깨졌습니다 — 둘 중 하나만 됐습니다. 그런데 README와
PRINCIPLES는 휴대폰 접속을 지원한다고 적고 있었습니다.

## 결정

1. 화면은 요청할 때 **화면을 연 호스트 + 백엔드 포트**로 주소를 조립합니다(`lib/api.ts` `apiBase()`).
   빌드에 넣는 값은 포트(`NEXT_PUBLIC_BACKEND_PORT`, compose가 `BACKEND_PORT`를 넘김)뿐입니다.
   맥에서는 `http://localhost:8080`, 휴대폰에서는 `http://192.168.x.x:8080`이 되어 어느 기기에서도 같은
   호스트의 백엔드를 부르고, 화면과 API가 같은 사이트라 쿠키도 그대로 실립니다.
2. 전체 주소를 꼭 고정해야 할 때(리버스 프록시)만 `NEXT_PUBLIC_API_BASE`로 덮어씁니다. 비워 두는 것이 기본입니다.
3. 백엔드 CORS는 `FRONTEND_ORIGIN`의 명시 목록에 더해 **화면 포트의 모든 호스트**(`http://*:3000`)를 허용합니다
   (`FRONTEND_PORT`). LAN IP는 DHCP로 바뀌므로 목록에 박아 둘 수 없습니다.

## 이유

- 쿠키는 `SameSite=Strict`라 다른 사이트의 요청에는 애초에 실리지 않습니다. CORS 패턴을 화면 포트 전체로
  넓혀도 인증이 새지 않습니다 — 공격자 페이지가 `evil.example:3000`에서 요청해도 쿠키가 없어 401입니다.
- 주소를 실행 시점에 정하면 포트를 바꿀 때도 다시 빌드할 필요가 포트 하나뿐이고, 같은 이미지가 어느 네트워크에서나 돕니다.

## 대안과 버린 이유

- **LAN IP를 .env에 적고 두 origin을 허용**: IP가 바뀔 때마다 재빌드, 휴대폰 핫스팟·사무실 와이파이마다 다른 값.
- **리버스 프록시로 화면과 API를 한 origin에**: 새 컨테이너(Caddy 등)가 생깁니다. 1인용 로컬 앱에 들일 근거가
  약하고, 필요해지면 1번의 덮어쓰기(`NEXT_PUBLIC_API_BASE`)로 그때 붙일 수 있습니다.

## 결과(검증)

- `apiBase()`: 브라우저 호스트 `192.168.0.10` → `http://192.168.0.10:8080`, SSR → `http://localhost:8080`.
- 백엔드: `Origin: http://192.168.0.10:3000`의 preflight에 `Access-Control-Allow-Origin`이 그 origin으로 돌아옴
  (2026-10-02 실측).
