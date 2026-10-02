/** 상관계수의 세기를 말로 옮깁니다. 숫자만 보면 0.35가 큰지 작은지 알기 어렵습니다. */
export function correlationStrength(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) {
    return "계산할 수 없습니다.";
  }
  const magnitude = Math.abs(value);
  const direction = value > 0 ? "같은 방향" : "반대 방향";
  if (magnitude < 0.2) {
    return "거의 무관합니다.";
  }
  if (magnitude < 0.4) {
    return `약하게 ${direction}으로 움직였습니다.`;
  }
  if (magnitude < 0.6) {
    return `어느 정도 ${direction}으로 움직였습니다.`;
  }
  return `뚜렷하게 ${direction}으로 움직였습니다.`;
}
