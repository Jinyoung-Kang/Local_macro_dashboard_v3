# 설계 결정 기록 (ADR)

되돌리기 어렵거나, 나중에 "왜 이렇게 했지?"라는 질문이 나올 결정을 한 장씩 남깁니다.
각 문서는 **배경 → 결정 → 이유 → 대안과 버린 이유 → 결과(지키는 방법)** 순서입니다.

| 번호 | 제목 |
|---|---|
| [0001](0001-feature-modules-and-dependency-rules.md) | 백엔드 기능 단위 패키지와 의존 규칙 |
| [0002](0002-spring-boot-4-and-jackson-3.md) | Spring Boot 4.1 · Jackson 3 전환 |
| [0003](0003-backup-and-restore.md) | 백업·복원: 끝 표시 확인과 한 트랜잭션 통째 교체 |
| [0004](0004-frontend-layering.md) | 프런트엔드: 그리기 · 훅 · 순수 함수 · API 클라이언트 나누기 |
| [0005](0005-radar-history-replace-write.md) | 수급 레이더 이력: 조합·날짜 단위 교체 쓰기, 소스가 밝힌 기준일, 거래소 달력 |
| [0006](0006-frontend-api-base-at-runtime.md) | 화면이 백엔드 주소를 실행 시점에 정함 (휴대폰·LAN 접속), CORS는 화면 포트의 모든 호스트 |
