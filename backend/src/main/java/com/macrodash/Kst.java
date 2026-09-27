package com.macrodash;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;

/**
 * 이 대시보드의 기준 시간대 (한국 표준시).
 *
 * <p>왜 필요한가 — 컨테이너의 기본 시간대는 UTC입니다. {@code LocalDate.now()}를
 * 그대로 쓰면 한국 시각 00:00~09:00 사이에 <b>날짜가 하루 어긋납니다</b>.
 * 실제로 섹터 화면의 연초 대비 수익률(YTD)이 1월 1일 오전에 전년도 기준으로
 * 계산됐습니다.
 *
 * <p>이 대시보드는 한국에서 한국 장을 함께 보는 화면이고, 수집기도 KST로 시각을
 * 찍습니다({@code collector/app/tasks.py}). 기준을 한 곳에 두어 백엔드도 같은
 * 달력을 쓰게 합니다.
 *
 * <p><b>주의</b> — 시간대를 JVM 기본값으로 바꾸지 않습니다. 기본값을 바꾸면
 * 라이브러리가 조용히 영향을 받고, 어디서 바뀐 것인지 추적하기 어려워집니다.
 * 날짜가 필요한 곳에서 {@link #today()}를 부르세요.
 */
public final class Kst {

    private Kst() {
    }

    /** 한국 표준시 (UTC+9, 서머타임 없음). */
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    /**
     * 한국 기준 오늘 날짜.
     *
     * @return 서버가 어느 시간대에서 돌든 한국 달력 기준의 오늘
     */
    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    /** "최근 N년" 조회의 상한. 저장본은 길어야 수십 년이라, 이보다 길면 "전체"와 같습니다. */
    public static final int MAX_LOOKBACK_YEARS = 100;

    /**
     * 한국 기준 오늘로부터 {@code years}년 전 날짜.
     *
     * <p>years는 URL 파라미터로 들어옵니다. 2147483647을 그대로 빼면 날짜 범위를 벗어나
     * DateTimeException(500)이 났습니다. 결과가 같으므로 {@value #MAX_LOOKBACK_YEARS}년으로
     * 접고, 음수는 0(오늘)으로 봅니다.
     *
     * @param years 몇 년 전인지
     * @return 기준 날짜 (이 날짜 이후가 조회 범위)
     */
    public static LocalDate yearsAgo(int years) {
        return today().minusYears(Math.min(Math.max(years, 0), MAX_LOOKBACK_YEARS));
    }

    /**
     * 화면에 나가는 시각 형식 — {@code "yyyy-MM-dd HH:mm"} (초 없음, 날짜 항상 포함).
     *
     * <p>수집기({@code collector/app/kst.py})와 화면({@code lib/format.ts formatKst})도
     * 같은 형식입니다. 바꿀 때는 세 곳을 함께 바꾸세요.
     */
    public static final DateTimeFormatter DISPLAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZONE);

    /**
     * 사람이 읽는 KST 시각 문자열.
     *
     * @param moment Instant·ZonedDateTime 등 시점 (시간대가 있으면 KST로 바꿉니다)
     * @return 예 {@code "2026-09-25 23:43 KST"}
     */
    public static String stamp(TemporalAccessor moment) {
        return DISPLAY.format(moment) + " KST";
    }
}
