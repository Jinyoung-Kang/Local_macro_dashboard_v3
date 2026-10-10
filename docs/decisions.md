# 작은 결정 기록

[ADR](adr/README.md)로 한 장을 쓸 만큼 크지는 않지만 나중에 "왜 이렇게 뒀지?"가 나올 결정을 남깁니다.
항목마다 문제·원인·선택·이유를 5줄 이내로 적습니다.

## 2026-10-08 · postcss-selector-parser 취약점 알림을 고치지 않고 닫음

- 문제: Dependabot이 frontend의 postcss-selector-parser 6.1.4(dev)에 GHSA-rj75-hqrm-r3gf(셀렉터 파싱 2차 복잡도)를 알림. 수정판은 7.1.6뿐.
- 원인: tailwindcss 3.4.19(v3 LTS 최신)와 postcss-nested 6.2.0이 `^6`을 요구하는데 6.x 수정판이 없음. 부모로 푸는 길은 Tailwind 4 메이저 이전뿐(Dependabot PR #3 — 빌드 실패로 닫음).
- 선택: `overrides`로 7.1.6을 강제하지 않고 알림을 `tolerable_risk`로 dismiss. 나머지 알림 4건은 버전을 올려 해결(`6a1f7e6`).
- 이유: 빌드 때 우리 소스 CSS만 파싱하는 개발 의존성이고 운영 이미지(`npm ci --omit=dev`)에는 없음. 7.1.6을 강제해도 Tailwind 출력 CSS는 같았지만, 부모가 선언한 메이저 범위 밖 조합을 떠안을 이득이 없음. Tailwind 4로 옮길 때 이 의존성째 사라짐.

## 2026-10-08 · braces 취약점(GHSA-vfj7-8cjw-p6xm)은 고칠 판이 없어 그대로 둠

- 문제: frontend의 braces 3.0.3(dev)에 깊게 중첩된 중괄호 패턴으로 스택을 넘기는 DoS(high). 취약 범위가 `<= 3.0.3`이고 최신판이 3.0.3이라 수정판이 없음.
- 원인: tailwindcss 3(chokidar·micromatch)과 eslint-config-next(`@next/eslint-plugin-next` → fast-glob 3.3.1 → micromatch)가 끌어옴. eslint-config-next는 15.5.27·16.4.0 모두 fast-glob 3.3.1 고정이라 Tailwind 4로 가도 남음.
- 선택: 손대지 않음. Dependabot 알림 #1은 GitHub가 이미 `auto_dismissed`. CI의 `npm audit --omit=dev --audit-level=high`는 운영 의존성만 봐서 통과.
- 이유: 운영 이미지(`npm ci --omit=dev`)에 없고, braces가 펼치는 패턴은 우리 설정의 glob(`./src/**/*.{ts,tsx}` 등)뿐이라 외부 입력이 닿지 않음. braces 수정판이 나오면 lockfile만 갱신(micromatch `^3.0.3`·chokidar `~3.0.2` 범위 안이면).

## 2026-10-10 · braces 알림(GHSA-vfj7-8cjw-p6xm)이 다시 열려 직접 닫음

- 문제: 2026-10-08에 GitHub가 자동으로 닫았던(`auto_dismissed`) Dependabot 알림 #1이 2026-10-09 15:36 UTC에 다시 열림.
- 원인: 같은 시각 세 저장소가 함께 다시 열림 — 저장소를 비공개로 돌리면서 자동 분류 규칙이 더는 닫아 두지 않는 것으로 봄(시각으로 한 추정, 문서로는 확인 못함).
- 선택: `tolerable_risk`로 직접 dismiss(댓글에 이 항목). 근거는 위 2026-10-08 항목 그대로 — 수정판 없음, 상류는 고칠 계획이 없다고 답함(micromatch/braces#70, 잠김).
- 이유: dev 의존성(tailwindcss 3 → chokidar · micromatch)이고 `npm ls braces --omit=dev` 0건, 펼치는 패턴은 우리 설정의 glob뿐. 닫은 알림은 수정판이 나와도 다시 열리지 않을 수 있어 Tailwind 4 이전 때 함께 확인.

