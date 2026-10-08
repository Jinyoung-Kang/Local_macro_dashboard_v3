# 작은 결정 기록

[ADR](adr/README.md)로 한 장을 쓸 만큼 크지는 않지만 나중에 "왜 이렇게 뒀지?"가 나올 결정을 남깁니다.
항목마다 문제·원인·선택·이유를 5줄 이내로 적습니다.

## 2026-10-08 · postcss-selector-parser 취약점 알림을 고치지 않고 닫음

- 문제: Dependabot이 frontend의 postcss-selector-parser 6.1.4(dev)에 GHSA-rj75-hqrm-r3gf(셀렉터 파싱 2차 복잡도)를 알림. 수정판은 7.1.6뿐.
- 원인: tailwindcss 3.4.19(v3 LTS 최신)와 postcss-nested 6.2.0이 `^6`을 요구하는데 6.x 수정판이 없음. 부모로 푸는 길은 Tailwind 4 메이저 이전뿐(Dependabot PR #3 — 빌드 실패로 닫음).
- 선택: `overrides`로 7.1.6을 강제하지 않고 알림을 `tolerable_risk`로 dismiss. 나머지 알림 4건은 버전을 올려 해결(`6a1f7e6`).
- 이유: 빌드 때 우리 소스 CSS만 파싱하는 개발 의존성이고 운영 이미지(`npm ci --omit=dev`)에는 없음. 7.1.6을 강제해도 Tailwind 출력 CSS는 같았지만, 부모가 선언한 메이저 범위 밖 조합을 떠안을 이득이 없음. Tailwind 4로 옮길 때 이 의존성째 사라짐.
