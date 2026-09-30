// ESLint 9 flat config.
// Next.js 15의 eslint-config-next는 아직 예전(eslintrc) 형식이라 FlatCompat으로 불러옵니다
// (Next.js 15 공식 안내와 같은 방식). 규칙은 예전 .eslintrc.json과 같습니다: next/core-web-vitals.
import { dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { FlatCompat } from "@eslint/eslintrc";

const compat = new FlatCompat({ baseDirectory: dirname(fileURLToPath(import.meta.url)) });

const config = [
  // 예전 `eslint --ext .js,.jsx,.mjs,.cjs,.ts,.tsx,.mts,.cts`와 같은 파일을 검사합니다.
  { files: ["**/*.{js,jsx,mjs,cjs,ts,tsx,mts,cts}"] },
  { ignores: [".next/**", "node_modules/**", "next-env.d.ts"] },
  ...compat.extends("next/core-web-vitals"),
];

export default config;
