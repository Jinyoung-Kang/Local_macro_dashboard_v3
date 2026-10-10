// ESLint 9 flat config.
// Next.js 16의 eslint-config-next는 flat config를 그대로 내보냅니다(15에서는 eslintrc 형식이라 FlatCompat으로 불렀음).
// 규칙은 예전과 같습니다: next/core-web-vitals.
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
