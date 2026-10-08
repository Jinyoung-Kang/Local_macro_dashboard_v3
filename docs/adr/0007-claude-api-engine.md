# 0007. AI 엔진에 Claude Haiku 5.5를 공식 SDK로 붙이고, 재시도는 자동 탐색이 맡는다

- 상태: 채택 (2026-10)
- 지키는 방법: `backend/src/test/java/com/macrodash/feature/ai/AiServiceAnthropicTest.java`(가짜 서버로 SDK 경로 전체),
  `AiServiceSpeedTest`(자동 탐색 순서)

## 배경

AI 리포트 엔진은 NVIDIA·Cerebras·Cloudflare를 OpenAI 호환 HTTP로 직접 불렀습니다. Claude API(Haiku 5.5)를 더하면서
같은 방식으로 붙일지, Anthropic 공식 Java SDK를 쓸지 정해야 했습니다. Haiku 5.5는 이전 모델과 요청 규칙이 다릅니다 —
기본값이 아닌 `temperature`는 400, 사고(thinking)가 기본으로 켜져 응답 앞에 사고 블록이 오고, 안전 분류기가
HTTP 200에 `stop_reason: refusal`로 거절할 수 있습니다.

## 결정

1. **공식 SDK**(`com.anthropic:anthropic-java`)로 호출합니다. 오류 종류(401·403·404·429·5xx·연결)와 거절 응답을 타입으로
   받아, 사용자에게 "무엇을 하면 되는지"를 정확히 적을 수 있습니다.
2. **SDK의 자동 재시도를 끕니다**(`maxRetries(0)`). 대기 한도는 이 서비스가 시도마다 정하고(엔진당 60초·전체 180초),
   실패하면 다음 엔진으로 넘어가는 것이 재시도 정책입니다. SDK가 몰래 다시 보내면 "엔진당 N초"가 깨집니다.
3. 요청은 Haiku 5.5에 맞춥니다 — `temperature` 없음, 사고는 기본(adaptive), effort `medium` 명시, 출력 한도 16,000
   (사고도 한도에 들어감). 응답은 **텍스트 블록만** 이어 붙입니다.
4. 키는 클라이언트 생성자에 **명시해** 넘깁니다. 넘기지 않으면 SDK가 환경 변수·로컬 프로필에서 자격 증명을 찾아,
   설정하지 않은 키로 조용히 호출할 수 있습니다.
5. 자동 탐색의 **첫 엔진**입니다(사용자 결정). 리포트 한 건이 1센트 미만이고 한국어로 바로 써서 번역 단계가 필요
   없습니다. 키가 없으면 "키 없음"으로 건너뛰므로 무료 엔진만 쓰는 환경은 그대로입니다.

## 대안과 버린 이유

- **기존 RestClient로 직접 호출**: 의존성이 늘지 않지만, 오류 분류·거절 응답·요청 규칙 변화를 직접 따라가야 합니다.
  모델 세대가 바뀔 때마다 이 코드가 조용히 틀리는 곳이 됩니다.
- **SDK 재시도 유지**: 일시적 과부하(529)에는 유리하지만, 자동 탐색의 시간 예산과 겹쳐 최악 대기가 세 배가 됩니다.

## 비용

OkHttp·Kotlin 런타임이 함께 들어와 백엔드 이미지가 수 MB 커집니다. Kotlin·Jackson 2 버전은 Spring Boot가 관리하는
판으로 맞춰지고(`mvn dependency:tree`), OkHttp는 SDK가 고른 4.12를 씁니다.
