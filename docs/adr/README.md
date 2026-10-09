# 설계 결정 기록 (ADR)

되돌리기 어렵거나, 나중에 "왜 이렇게 했지?"라는 질문이 나올 결정을 한 장씩 남깁니다.
각 문서는 **배경 → 결정 → 이유 → 대안과 버린 이유 → 결과(지키는 방법)** 순서입니다.
한 장을 쓸 만큼 크지 않은 결정(의존성 알림을 닫은 이유 등)은 [../decisions.md](../decisions.md)에 몇 줄로 남깁니다.

| 번호 | 제목 |
|---|---|
| [0001](0001-feature-modules-and-dependency-rules.md) | 백엔드 기능 단위 패키지와 의존 규칙 |
| [0002](0002-spring-boot-4-and-jackson-3.md) | Spring Boot 4.1 · Jackson 3 전환 |
| [0003](0003-backup-and-restore.md) | 백업·복원: 끝 표시 확인과 한 트랜잭션 통째 교체 |
| [0004](0004-frontend-layering.md) | 프런트엔드: 그리기 · 훅 · 순수 함수 · API 클라이언트 나누기 |
| [0005](0005-radar-history-replace-write.md) | 수급 레이더 이력: 조합·날짜 단위 교체 쓰기, 소스가 밝힌 기준일, 거래소 달력 |
| [0006](0006-frontend-api-base-at-runtime.md) | 화면이 백엔드 주소를 실행 시점에 정함 (휴대폰·LAN 접속), CORS는 화면 포트의 모든 호스트 |
| [0007](0007-claude-api-engine.md) | AI 엔진에 Claude Haiku 5.5를 공식 SDK로, 재시도는 SDK가 아니라 자동 탐색이 맡음 |
| [0008](0008-backend-owned-tables-and-scheduled-jobs.md) | 백엔드가 쓰는 테이블과 정기 작업 — 테이블마다 쓰는 쪽은 하나, 스키마 파일 한 곳, 정기 작업은 수집을 일으키지 않음 |
| [0009](0009-regime-track-record-point-in-time.md) | 국면 판정 성과: 시점 기준 재현(발표 지연), 다음 거래일 진입, 표본 부족은 숫자를 내지 않음 |
