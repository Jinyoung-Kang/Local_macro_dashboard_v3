import { describe, expect, it } from "vitest";
import { titleWithUnread, unreadBadge } from "../alerts";

describe("읽지 않은 알림 표시", () => {
  it("탭 제목 앞에 수를 붙이고, 0이면 떼어 낸다", () => {
    expect(titleWithUnread("시장 국면 · 매크로 대시보드", 3)).toBe("(3) 시장 국면 · 매크로 대시보드");
    expect(titleWithUnread("(3) 시장 국면 · 매크로 대시보드", 0)).toBe("시장 국면 · 매크로 대시보드");
    expect(titleWithUnread("(3) 시장 국면", 120)).toBe("(99+) 시장 국면");
  });

  it("뱃지는 0이면 없다", () => {
    expect(unreadBadge(0)).toBeNull();
    expect(unreadBadge(undefined)).toBeNull();
    expect(unreadBadge(7)).toBe("7");
  });
});
