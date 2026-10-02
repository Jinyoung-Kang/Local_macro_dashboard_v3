/**
 * 백엔드 주소 결정 — 화면을 연 호스트에 백엔드 포트를 붙입니다.
 *
 * 빌드 시점의 localhost:8080이 번들에 박혀 있으면 휴대폰(192.168.x.x:3000)은 자기 자신의
 * localhost를 찾고, LAN IP를 박으면 맥의 localhost:3000에서 쿠키가 다른 사이트가 됩니다.
 */
import { afterEach, describe, expect, it, vi } from "vitest";
import { apiBase, apiGet, apiPost } from "@/lib/api";

describe("apiBase", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("브라우저에서는 화면을 연 호스트 + 백엔드 포트", () => {
    vi.stubGlobal("window", { location: { protocol: "http:", hostname: "192.168.0.10" } });
    expect(apiBase()).toBe("http://192.168.0.10:8080");

    vi.stubGlobal("window", { location: { protocol: "http:", hostname: "localhost" } });
    expect(apiBase()).toBe("http://localhost:8080");
  });

  it("서버(SSR)에서는 localhost", () => {
    vi.stubGlobal("window", undefined);
    expect(apiBase()).toBe("http://localhost:8080");
  });
});

describe("요청 헤더", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("GET에는 Content-Type을 붙이지 않는다 — 붙이면 URL마다 preflight(OPTIONS)가 먼저 나간다", async () => {
    const fetchMock = vi.fn().mockImplementation(async () =>
      new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } }),
    );
    vi.stubGlobal("fetch", fetchMock);
    vi.stubGlobal("window", { location: { protocol: "http:", hostname: "localhost" } });

    await apiGet("/api/x");
    const getHeaders = fetchMock.mock.calls[0][1].headers as Record<string, string>;
    expect(getHeaders["Content-Type"]).toBeUndefined();

    await apiPost("/api/y", { a: 1 });
    const postHeaders = fetchMock.mock.calls[1][1].headers as Record<string, string>;
    expect(postHeaders["Content-Type"]).toBe("application/json");
  });
});
