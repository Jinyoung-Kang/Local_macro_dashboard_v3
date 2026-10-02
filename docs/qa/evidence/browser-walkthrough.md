# 내장 브라우저 화면 점검 (QA 스택 http://localhost:13000, 2026-10-02)

방법: 로그인 뒤 15개 화면을 열고 콤보박스·버튼을 조작. 각 화면에서 `performance.getEntriesByType('resource')`로
백엔드(18080) 요청의 **중복**(같은 URL 2회 이상)·**지연**(>1.5s)을 세고, 콘솔 오류와 실패 요청을 읽음.
모바일(375×812)에서 `scrollWidth > innerWidth`(가로 스크롤)와 24px 미만 터치 대상을 검사. 접근성은 axe-core 4.10.2를
주입해 WCAG 2.1 A/AA 규칙만 실행.

| 화면 | API 호출 | 중복 | 지연 | 콘솔 오류 | 조작 | 비고 |
|---|---|---|---|---|---|---|
| /macro | 10 | 0 | 0 | 0 | 원본 데이터 보기 | 첫 로드가 `overview?live=true` → QA(외부 차단)에서 카드 18/21 "수집 실패" → **QA-001** |
| /liquidity | 4 | 0 | 0 | 0 | 기간 변경 | |
| /sector | 4 | 0 | 0 | 0 | 기간 3M·초과성과 전환 | 전환마다 요청 1건 |
| /institutions | 8 | 0 | 0 | 0 | 기관·분기·종목 수 변경 | 변경마다 요청 1건 |
| /consensus | 10 | 0 | 0 | 0 | 기관 토글·최소 기관 수·기준 분기 | 막대 라벨 "9.0곳" **QA-004**, 기관 토글 상태 불일치 **QA-003** |
| /style | 6 | 0 | 0 | 0 | — | |
| /scorecard | 4 | 0 | 0 | 0 | — | |
| /cot | 6 | 0 | 0 | 0 | — | |
| /krx | 7 | 0 | 0 | 0 | — | 배지에 `CONTRACT` 원문 코드 **QA-005** |
| /radar | 9 | 0 | 0 | 0 | 투자주체 연기금(라이브 경로) | 라이브 경로 1,147ms(외부 차단 상태) — 동기 대기 없음(리뷰 A-1 수정 확인) |
| /regime | 3 | 0 | 0 | 0 | — | |
| /correlation | 4 | 0 | 0 | 0 | — | |
| /status | 4 | 0 | 0 | 0 | — | |
| /ai/report | 5 | 0 | 0 | 0 | 리포트 생성 클릭 | 키 없으면 버튼 disabled, 이유 안내 없음 **QA-006** |
| /connections | 3 | 0 | 0 | 0 | 진단 실행 | 키 없음 메시지 정상 |
| /login | — | — | — | 0 | Tab 이동 | 포커스 표시 outline 2px |

## 모바일 375px
/macro·/radar·/institutions·/status: 페이지 가로 스크롤 없음(표는 min-w 640 안쪽 스크롤). 메뉴 버튼 있음.
/radar 상세 표의 "원문" 링크 8개가 24px 미만(터치 대상 작음) — **QA-007(S4)**.

## axe-core (WCAG 2.1 A/AA)
| 화면 | 위반 |
|---|---|
| /status | scrollable-region-focusable (pre ×2, serious) |
| /institutions | color-contrast (히트맵 셀 ×3, serious) |
| /style | color-contrast (×16), scrollable-region-focusable (×1) |
| /krx | scrollable-region-focusable (main ×1) |
| /sector | scrollable-region-focusable (.overflow-x-auto ×1) |
| /correlation | svg-img-alt (산점도 점 496개 role=img에 제목 없음, serious) |
| /connections | label (textarea에 라벨 없음, **critical**) |
| /macro /liquidity /consensus /radar /scorecard /cot /regime /ai/report /login | 없음 |
→ **QA-008(S3)** 접근성 묶음.
