package com.macrodash;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 날짜 기준이 서버 시간대에 흔들리지 않는지 고정합니다.
 *
 * <p>컨테이너 기본 시간대는 UTC입니다. 예전에는 {@code LocalDate.now()}를 그대로
 * 써서 한국 시각 00:00~09:00 사이에 날짜가 하루 뒤로 밀렸고, 섹터 화면의 연초
 * 대비 수익률이 1월 1일 오전에 전년도 기준으로 계산됐습니다.
 */
class KstTest {

    @Test
    @DisplayName("UTC 자정 직후에도 한국 날짜는 이미 다음 날이다")
    void todayFollowsSeoulNotTheServerZone() {
        // 2026-01-01 00:30 KST == 2025-12-31 15:30 UTC
        ZonedDateTime newYearMorningSeoul =
                ZonedDateTime.of(2026, 1, 1, 0, 30, 0, 0, Kst.ZONE);

        LocalDate seoulDate = newYearMorningSeoul.toLocalDate();
        LocalDate utcDate = newYearMorningSeoul.withZoneSameInstant(ZoneId.of("UTC")).toLocalDate();

        assertThat(seoulDate).isEqualTo(LocalDate.of(2026, 1, 1));
        // 서버 시간대를 따랐다면 연도까지 달라집니다 — YTD가 전년도 기준이 됩니다.
        assertThat(utcDate).isEqualTo(LocalDate.of(2025, 12, 31));
        assertThat(seoulDate.getYear()).isNotEqualTo(utcDate.getYear());
    }

    @Test
    @DisplayName("today()는 JVM 기본 시간대와 무관하게 서울 날짜를 돌려준다")
    void todayIgnoresTheDefaultZone() {
        assertThat(Kst.today()).isEqualTo(LocalDate.now(Kst.ZONE));
        assertThat(Kst.ZONE).isEqualTo(ZoneId.of("Asia/Seoul"));
    }

    @Test
    @DisplayName("today(clock)은 시계의 시간대가 UTC여도 서울 날짜 — 14:59 UTC는 같은 날, 15:00 UTC는 다음 날")
    void todayWithClockUsesSeoul() {
        java.time.Clock before = java.time.Clock.fixed(Instant.parse("2026-10-11T14:59:59Z"), java.time.ZoneOffset.UTC);
        java.time.Clock after = java.time.Clock.fixed(Instant.parse("2026-10-11T15:00:00Z"), java.time.ZoneOffset.UTC);
        assertThat(Kst.today(before)).isEqualTo(LocalDate.of(2026, 10, 11));
        assertThat(Kst.today(after)).isEqualTo(LocalDate.of(2026, 10, 12));
    }

    @Test
    @DisplayName("화면 시각 형식: yyyy-MM-dd HH:mm KST (초 없음, KST로 변환)")
    void stampIsMinutePrecisionInKst() {
        Instant utc = Instant.parse("2026-09-25T15:30:59Z");
        assertThat(Kst.stamp(utc)).isEqualTo("2026-09-26 00:30 KST");
    }
}
