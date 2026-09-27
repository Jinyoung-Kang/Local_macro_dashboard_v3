/**
 * 화면 응답에 붙이는 보안 헤더.
 *
 * - X-Frame-Options / frame-ancestors: 다른 사이트가 이 화면을 iframe에 넣어
 *   클릭을 가로채지(clickjacking) 못하게 합니다.
 * - nosniff: 응답을 선언한 형식 그대로만 해석하게 합니다.
 * - Referrer-Policy: 다른 사이트로 나갈 때 대시보드 주소(경로)를 넘기지 않습니다.
 *
 * 주의사항 — script-src까지 막는 전체 CSP는 넣지 않았습니다. Next.js가 인라인
 * 스크립트를 쓰므로 nonce 설정 없이 막으면 화면이 뜨지 않습니다.
 */
const SECURITY_HEADERS = [
  { key: "X-Frame-Options", value: "DENY" },
  { key: "Content-Security-Policy", value: "frame-ancestors 'none'" },
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
  { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=()" },
];

/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  // "X-Powered-By: Next.js"로 프레임워크를 광고하지 않습니다.
  poweredByHeader: false,
  // 백엔드 주소는 **빌드할 때** 번들에 들어갑니다(NEXT_PUBLIC_ 접두사). 실행 중인 컨테이너의
  // 환경변수를 바꿔도 반영되지 않으므로, 바꾼 뒤에는 이미지를 다시 빌드하세요(make up).
  env: {
    NEXT_PUBLIC_API_BASE: process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080",
  },
  // 합쳐진 메뉴의 옛 주소. 즐겨찾기·공유 링크가 404가 되지 않게 새 화면으로 보냅니다.
  async redirects() {
    return [
      { source: "/ai/test", destination: "/connections", permanent: true },
      { source: "/toss", destination: "/connections", permanent: true },
      // 🧬 구루 포트폴리오 분석 → 🧬 기관 13F 스타일·위험 (메뉴 이름에 맞춰 주소도 변경)
      { source: "/guru", destination: "/style", permanent: true },
    ];
  },
  async headers() {
    return [{ source: "/:path*", headers: SECURITY_HEADERS }];
  },
};

export default nextConfig;
