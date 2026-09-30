/**
 * src/lib/api.ts
 * 백엔드 API 클라이언트.
 *
 * 인증은 httpOnly 쿠키로 처리되므로 credentials: "include"가 필수입니다.
 * (토큰을 localStorage에 두지 않습니다 — XSS로 새어 나갈 수 있습니다.)
 */

import { endpoints } from "./endpoints.ts";
import { createSharedFetcher } from "./sharedRequest.ts";

export const API_BASE =
  process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

export class UnauthorizedError extends ApiError {
  constructor() {
    super("로그인이 필요합니다.", 401);
    this.name = "UnauthorizedError";
  }
}

/**
 * 백엔드에 닿지 못했습니다(응답 자체가 없음). HTTP 상태가 없으므로 status는 0입니다.
 *
 * 브라우저는 연결 거부·주소 오류·CORS 차단을 모두 "Failed to fetch"(Safari는
 * "Load failed") 한 줄로만 알려 줍니다. 그 문구로는 무엇을 확인해야 할지 알 수
 * 없어서, 어느 주소에 닿지 못했는지를 함께 적습니다.
 */
export class NetworkError extends ApiError {
  constructor(message: string) {
    super(message, 0);
    this.name = "NetworkError";
  }
}

async function request<T>(
  path: string,
  init: RequestInit = {},
  timeoutMs = 0,
): Promise<T> {
  // 시간 제한은 호출자의 signal과 별개의 컨트롤러로 겁니다. 호출자가 취소한 것
  // (화면 이동 등 — 조용히 무시해야 함)과 시간 초과(오류로 보여야 함)를 구분하려고요.
  const controller = new AbortController();
  const external = init.signal;
  const forwardAbort = () => controller.abort();
  if (external?.aborted) {
    controller.abort();
  } else {
    external?.addEventListener("abort", forwardAbort, { once: true });
  }
  let timedOut = false;
  const timer = timeoutMs > 0
    ? setTimeout(() => {
        timedOut = true;
        controller.abort();
      }, timeoutMs)
    : null;

  let response: Response;
  try {
    response = await fetch(`${API_BASE}${path}`, {
      ...init,
      signal: controller.signal,
      credentials: "include",
      headers: {
        "Content-Type": "application/json",
        ...(init.headers ?? {}),
      },
    });
  } catch (error) {
    if (timedOut) {
      throw new NetworkError(
        `백엔드가 ${Math.round(timeoutMs / 1000)}초 안에 응답하지 않았습니다 (${API_BASE})`,
      );
    }
    if (external?.aborted) {
      throw error; // 호출자가 취소 — 그대로 올려 호출자가 무시하게 둡니다
    }
    throw new NetworkError(`백엔드 API(${API_BASE})에 연결하지 못했습니다`);
  } finally {
    if (timer !== null) clearTimeout(timer);
    external?.removeEventListener("abort", forwardAbort);
  }

  if (response.status === 401) {
    throw new UnauthorizedError();
  }

  if (!response.ok) {
    let message = `요청 실패 (HTTP ${response.status})`;
    try {
      const body = await response.json();
      if (body?.message) {
        message = body.message;
      }
    } catch {
      // 본문이 JSON이 아니면 기본 메시지를 씁니다.
    }
    throw new ApiError(message, response.status);
  }

  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

/**
 * @param timeoutMs 0이면 제한 없음. 화면 전체를 막는 요청(세션 확인)에만 겁니다 —
 *                  외부 API를 부르는 진단 요청은 수십 초가 정상일 수 있습니다.
 */
export function apiGet<T>(path: string, signal?: AbortSignal, timeoutMs = 0): Promise<T> {
  return request<T>(path, { method: "GET", signal }, timeoutMs);
}

const sharedGet = createSharedFetcher<unknown>((key) => {
  const separator = key.indexOf("|");
  return apiGet(key.slice(separator + 1), undefined, Number(key.slice(0, separator)));
});

/**
 * 진행 중인 같은 GET이 있으면 그 결과를 함께 씁니다(lib/sharedRequest 설명 참고).
 *
 * 취소 신호를 받지 않습니다 — 요청은 다른 쪽을 위해 끝까지 갑니다. 받는 쪽이 자기
 * 취소 여부를 보고 결과를 버리세요(useApi가 그렇게 합니다).
 */
export function apiGetShared<T>(path: string, timeoutMs = 0): Promise<T> {
  return sharedGet(`${timeoutMs}|${path}`) as Promise<T>;
}

export function apiPost<T>(path: string, body?: unknown): Promise<T> {
  return request<T>(path, {
    method: "POST",
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

// ---------------------------------------------------------------- 인증
export const auth = {
  login: (password: string) => apiPost<{ ok: boolean }>(endpoints.auth.login, { password }),
  logout: () => apiPost<{ ok: boolean }>(endpoints.auth.logout),
  session: () => apiGet<{ authenticated: boolean; readMode?: string }>(endpoints.auth.session),
};
