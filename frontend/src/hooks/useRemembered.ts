"use client";

import { useState } from "react";

/**
 * 마지막으로 받은 값을 기억합니다. 지금 값이 없으면(다음 응답을 기다리는 중) 그것을 돌려줍니다.
 *
 * 선택지·카탈로그처럼 **응답에 딸린 목록**으로 컨트롤을 그릴 때 씁니다. 조건을 바꾸면 useApi가 새 응답이 올 때까지
 * 데이터를 비우는데(옛 숫자를 새 제목 아래 보이지 않으려고), 그때 컨트롤까지 사라지거나 선택지가 줄었습니다(QA-047).
 * 숫자가 아니라 목록만 이어 쓰므로 옛 값을 새 조건의 결과로 보여 주지 않습니다.
 *
 * 주의 — 렌더마다 새로 만드는 값(`data?.x ?? []`)을 넘기면 매번 '바뀐 값'이 됩니다. 응답의 필드를 그대로 넘기세요.
 */
export function useRemembered<T>(value: T | null | undefined): T | undefined {
  const [kept, setKept] = useState<T | undefined>(value ?? undefined);
  if (value != null && value !== kept) {
    setKept(value);   // 렌더 중에 이전 값을 바꾸는 React 권장 방식(effect를 거치지 않음)
  }
  return value ?? kept;
}
