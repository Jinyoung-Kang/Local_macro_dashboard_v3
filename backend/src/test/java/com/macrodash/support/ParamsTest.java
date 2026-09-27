package com.macrodash.support;

import com.macrodash.Kst;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** URL 파라미터 해석 — 형식 오류는 400, 범위 초과는 접기. */
class ParamsTest {

    @Test
    @DisplayName("날짜: 비어 있으면 null, 올바르면 LocalDate")
    void parsesDates() {
        assertThat(Params.optionalDate("obsDate", null)).isNull();
        assertThat(Params.optionalDate("obsDate", " ")).isNull();
        assertThat(Params.optionalDate("obsDate", "2026-09-18")).isEqualTo(LocalDate.of(2026, 9, 18));
    }

    @Test
    @DisplayName("날짜: 형식이 틀리거나 없는 날짜면 무엇을 고칠지 알려 주는 400 예외")
    void rejectsBadDates() {
        assertThatThrownBy(() -> Params.optionalDate("obsDate", "abc"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("obsDate")
                .hasMessageContaining("2026-09-18");
        assertThatThrownBy(() -> Params.optionalDate("startDate", "2026-13-45"))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @DisplayName("오류 메시지에 되돌려 주는 입력값은 길면 자른다")
    void echoesShortInputOnly() {
        assertThat(Params.echo("x".repeat(500))).hasSize(41).endsWith("…");
        assertThat(Params.echo("abc")).isEqualTo("abc");
    }

    @Test
    @DisplayName("clamp: 범위 밖 값은 경계로 접는다")
    void clamps() {
        assertThat(Params.clamp(-1, 1, 200)).isEqualTo(1);
        assertThat(Params.clamp(Integer.MAX_VALUE, 1, 200)).isEqualTo(200);
        assertThat(Params.clamp(40, 1, 200)).isEqualTo(40);
    }

    @Test
    @DisplayName("yearsAgo: 2147483647년 전도 예외 없이 상한(100년)으로 접힌다")
    void yearsAgoNeverOverflows() {
        assertThat(Kst.yearsAgo(Integer.MAX_VALUE)).isEqualTo(Kst.today().minusYears(Kst.MAX_LOOKBACK_YEARS));
        assertThat(Kst.yearsAgo(-5)).isEqualTo(Kst.today());
        assertThat(Kst.yearsAgo(3)).isEqualTo(Kst.today().minusYears(3));
    }
}
