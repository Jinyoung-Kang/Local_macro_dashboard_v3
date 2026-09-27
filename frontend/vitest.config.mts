import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

/**
 * 화면 훅·컴포넌트 테스트 (npm test).
 *
 * 순수 함수 검사(src/lib/__checks__, npm run check)는 그대로 두고, 여기서는 React로
 * 실제 렌더링해야 확인되는 것(훅의 상태 변화 등)만 다룹니다. 테스트 파일은
 * `src/**\/__tests__/*.test.ts(x)`입니다.
 */
export default defineConfig({
  resolve: {
    alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) },
  },
  test: {
    environment: "jsdom",
    include: ["src/**/__tests__/**/*.test.{ts,tsx}"],
    restoreMocks: true,
  },
});
