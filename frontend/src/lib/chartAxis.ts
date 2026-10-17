/**
 * 차트 축의 순수 계산 — 값 범위, 라벨 폭. React·Recharts를 모릅니다.
 */
/**
 * Y축 표시 범위.
 *
 * <b>왜 필요한가</b> — Recharts는 면적 차트의 Y축을 0부터 그립니다. 순유동성처럼
 * 값이 5.85~6.0조 달러 사이에서 움직이는 계열에 0~8조 축을 쓰면, 정작 읽어야 할
 * 변동이 축 꼭대기 얇은 띠에 눌려 직선처럼 보입니다.
 *
 * 0이 의미를 갖는 계열(스프레드처럼 부호가 중요한 값)만 0을 포함시키고,
 * 나머지는 데이터 범위에 맞춰 여백만 둡니다.
 */
export function valueDomain(
  data: { value: number | null }[],
  includeZero: boolean,
): [number | "auto", number | "auto"] {
  const values = data
    .map((point) => point.value)
    .filter((value): value is number => value !== null && Number.isFinite(value));

  if (values.length === 0) {
    return ["auto", "auto"];
  }

  let min = Math.min(...values);
  let max = Math.max(...values);
  if (includeZero) {
    min = Math.min(min, 0);
    max = Math.max(max, 0);
  }

  // 위아래로 8%씩 숨 쉴 공간. 선이 축에 붙어 잘린 것처럼 보이지 않게 합니다.
  const span = max - min;
  const pad = span === 0 ? Math.abs(max) * 0.05 || 1 : span * 0.08;

  // 값이 모두 0 이상인 계열은 축을 0 아래로 내리지 않습니다. 역레포처럼
  // 음수가 될 수 없는 양에 "-0.11T" 눈금이 찍히면 있을 수 없는 값을
  // 있을 수 있는 것처럼 보여 주게 됩니다.
  const lower = min >= 0 ? Math.max(0, min - pad) : min - pad;
  return [lower, max + pad];
}

/**
 * 축 라벨 길이를 눈대중으로 잽니다.
 *
 * 한글·한자는 폭이 라틴 문자의 약 두 배입니다. 글자 수만 세면
 * "KODEX 레버리지"와 "TIGER 미국필라델피아반도체나스닥"을 같게 보게 됩니다.
 */
export function visualWidth(text: string): number {
  let width = 0;
  for (const char of text) {
    width += /[가-힣ㄱ-ㅎㅏ-ㅣ一-鿿ぁ-ヿ]/.test(char) ? 2 : 1;
  }
  return width;
}

export function truncateToWidth(text: string, maxWidth: number): string {
  if (visualWidth(text) <= maxWidth) {
    return text;
  }
  let out = "";
  let width = 0;
  for (const char of text) {
    const next = width + (/[가-힣ㄱ-ㅎㅏ-ㅣ一-鿿ぁ-ヿ]/.test(char) ? 2 : 1);
    if (next > maxWidth - 1) {
      break;
    }
    out += char;
    width = next;
  }
  return out + "…";
}

/** 이름과 구분자 사이의 표시 — 백엔드 13F 비중 추이 줄 이름(`이름 · 클래스`, `이름 · CUSIP …`)과 같습니다. */
const TAG_SEPARATOR = " · ";

/**
 * 이름표를 폭에 맞춰 줄이되, 첫 ' · ' 뒤의 구분자는 그대로 둡니다 — 줄이는 쪽은 앞의 이름입니다.
 *
 * 같은 이름의 두 항목을 가르려고 붙인 구분자(' · COM'과 ' · PERP PFD CNV A', ' · CUSIP 464287200'과 '…655')가
 * 앞에서부터 자르면 통째로 잘려 다시 같은 이름표가 됩니다(QA-120). 이름은 적어도 `minNameWidth`만큼 남기고,
 * 그래도 폭을 넘으면 구분자를 자르지 않고 이름표를 그만큼 길게 둡니다. ' · '가 없으면 {@link truncateToWidth}와 같습니다.
 */
export function truncateKeepingTag(label: string, maxWidth: number, minNameWidth = 12): string {
  const at = label.indexOf(TAG_SEPARATOR);
  if (at < 0) {
    return truncateToWidth(label, maxWidth);
  }
  const tag = label.slice(at);
  const nameWidth = Math.max(minNameWidth, maxWidth - visualWidth(tag));
  return `${truncateToWidth(label.slice(0, at), nameWidth).replace(/\s+…$/, "…")}${tag}`;
}

/**
 * 여러 키의 이름표 — 각자 {@link truncateKeepingTag}로 줄이고, 그래도 다른 키와 같아진 이름표는 줄이지 않은 키로 둡니다.
 * 키가 서로 다르면 이름표도 서로 다릅니다(같은 이름표 아래 다른 숫자가 보이지 않게).
 */
export function distinctLabels(keys: readonly string[], maxWidth: number): string[] {
  const labels = keys.map((key) => truncateKeepingTag(key, maxWidth));
  for (;;) {
    const counts = new Map<string, number>();
    labels.forEach((label) => counts.set(label, (counts.get(label) ?? 0) + 1));
    let changed = false;
    labels.forEach((label, index) => {
      if ((counts.get(label) ?? 0) > 1 && label !== keys[index]) {
        labels[index] = keys[index];
        changed = true;
      }
    });
    if (!changed) {
      return labels;
    }
  }
}

