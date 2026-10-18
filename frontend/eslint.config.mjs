// ESLint 10 flat config.
// Next.js 16의 eslint-config-next는 flat config를 그대로 내보냅니다(15에서는 eslintrc 형식이라 FlatCompat으로 불렀음).
// 규칙은 예전과 같습니다: next/core-web-vitals.
// npm 설치 때 eslint-plugin-react·import·jsx-a11y가 ESLint 10을 피어로 선언하지 않아 경고 3건이 납니다.
// react·import는 eslint-config-next 16.4부터 ESLint 10 호환 shim으로 감싸 쓰고, jsx-a11y는 ESLint 10에서 없어진
// context API를 쓰지 않아 동작에는 문제가 없습니다(ESLint 9와 켜진 규칙 67개가 같음).
import nextVitals from "eslint-config-next/core-web-vitals";

const config = [
  // 예전 `eslint --ext .js,.jsx,.mjs,.cjs,.ts,.tsx,.mts,.cts`와 같은 파일을 검사합니다.
  { files: ["**/*.{js,jsx,mjs,cjs,ts,tsx,mts,cts}"] },
  { ignores: [".next/**", "node_modules/**", "next-env.d.ts"] },
  ...nextVitals,
  {
    // eslint-config-next 16이 함께 켜는 React Compiler용 규칙. 마운트 뒤에 시계·세션·주소를 읽는 자리(하이드레이션
    // 불일치를 피하려는 의도된 패턴) 7곳이 걸립니다. React Compiler를 켜지 않으므로 예전 규칙(15)과 같게 둡니다 —
    // 따를지는 React Compiler 도입과 함께 정합니다(docs/decisions.md 2026-10-11).
    rules: { "react-hooks/set-state-in-effect": "off" },
  },
];

export default config;
