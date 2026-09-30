# 0002. Spring Boot 4.1 · Jackson 3 전환

- 상태: 채택 (2026-09)
- 지키는 방법: 백엔드 테스트 전체, `FlowJsonTest`(JSON → 계산 입력 변환 규칙)

## 배경

백엔드가 Spring Boot 3.5.16을 썼습니다. Maven Central 기준 3.5 계열의 마지막 공개 릴리스가
3.5.16이고, 이후에는 4.0.x·4.1.x만 나옵니다. 공개(OSS) 보안 패치가 더 나오지 않는다고 판단했습니다.
(공식 지원 일정 페이지와 Java 취약점 DB는 작업 환경에서 접근이 막혀 직접 확인하지 못했습니다.)

## 결정

Spring Boot 4.1.1(Spring Framework 7, Jackson 3)로 올립니다.

- `spring-boot-starter-web` → `-webmvc`, 테스트는 `-webmvc-test` + `spring-boot-resttestclient`(+`-restclient`)
- 코드의 Jackson은 `com.fasterxml.jackson.databind` → `tools.jackson.databind`
- JWT 직렬화(`jjwt-jackson`)는 Jackson 2를 그대로 씁니다. jjwt 최신 0.13.0에도 Jackson 3 모듈이 없습니다.
  Jackson 2는 JWT 라이브러리 안에서만 쓰이고, 우리 코드와 HTTP 응답은 Jackson 3입니다.
- 쓰지 않던 `@EnableCaching`을 지웠습니다(`@Cacheable` 없음). Boot 4는 캐시 자동 설정이 별도 모듈이라
  남겨 두면 기동이 실패합니다.

## Jackson 3에서 뜻이 바뀐 것과 대응

같은 입력을 Jackson 2.21과 3.1로 돌려 비교했습니다. 형이 맞지 않을 때가 다릅니다.

| 호출 | Jackson 2 | Jackson 3 | 대응 |
|---|---|---|---|
| `asText()` — JSON `null` | `"null"` | `""` | `Json.asText`는 null을 먼저 거름 → 결과 같음 |
| `asText()` — 객체·배열 | `""` | 예외 | `asString("")`(객체·배열이면 `""`) |
| `asDouble()`·`asInt()`·`asLong()`·`asBoolean()` — 형이 안 맞음 | 0 / false | 예외 | 모두 `isNumber()` 등으로 먼저 확인한 뒤 읽고 있어 영향 없음. 인자 있는 판(`asDouble(0)` 등)은 true 불리언 한 경우 말고 같음 |
| `asLong()` — long 범위를 넘는 정수 | 잘린 값 | 예외 | `canConvertToLong()` 확인 뒤 읽음(넘으면 모름=null) |
| `fieldNames()` | 있음 | 없음 | `propertyNames()` |

계산 패키지(`analytics`)는 [0001](0001-feature-modules-and-dependency-rules.md)에 따라 Jackson을 모르므로
이 전환에서 바뀐 곳이 없습니다.

## 검증(전환 전후 비교)

- 백엔드 테스트 전부 통과
- 전후 jar를 같은 DB에 동시에 띄우고 63개 경로 × 압축 유무 + POST·비로그인·로그인 = 129요청의
  상태 코드·본문 값·키 순서·보안 헤더·쿠키 속성을 비교했습니다. 차이는 모두 **이전 jar끼리 비교해도
  똑같이 나는 것**뿐이었습니다(`ageSeconds` ±1초, `Map.of` 반복 순서).
- 이상 입력 109건: 전후 모두 500·내부 정보 노출 0건
- 응답 시간: 두 서버를 번갈아 40회씩, 경로별 p50 합 271.0ms → 276.7ms(+2.1%). 가장 큰 차이는
  250KB짜리 `/api/macro/overview`의 +4.4ms입니다.

## 알아 둘 차이(응답 값은 같음)

- **2KB 미만 응답은 이제 압축하지 않습니다.** Spring 7이 `Content-Length`를 붙여 설정값
  `server.compression.min-response-size: 2048`이 제대로 적용됩니다(예전엔 chunked라 작은 응답도 압축).
- 이모지(국기 등)를 `\uD83C…` 이스케이프 대신 UTF-8 그대로 씁니다. 파싱한 값은 같고 바이트는 줄었습니다.
- `Vary` 헤더가 한 줄로 합쳐지지 않고 여러 줄로 나갑니다(값은 같음).

## 비교 중 발견해 따로 고친 것

같은 jar를 두 번 띄워 비교하다, `Map.copyOf`의 반복 순서가 JVM마다 달라 COT 자산·자산군·섹터 목록
순서가 재시작마다 바뀌는 문제를 찾았습니다(업그레이드와 무관한 기존 버그). 순서를 지키도록 고쳤습니다.
