package com.macrodash.feature.institution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class GuruServiceSectorTest {

    @Test
    @DisplayName("매핑표에 섹터가 없으면 '미분류' — 문자열 \"null\"이 화면에 나가지 않는다")
    void missingSectorIsLabelledUnclassified() {
        ObjectMapper mapper = new ObjectMapper();
        assertThat(GuruService.sectorLabel(mapper.readTree("{\"ticker\":\"AAPL\"}"))).isEqualTo("미분류");
        assertThat(GuruService.sectorLabel(mapper.readTree("{\"ticker\":\"AAPL\",\"sector\":\"\"}"))).isEqualTo("미분류");
        assertThat(GuruService.sectorLabel(null)).isEqualTo("미분류");
        assertThat(GuruService.sectorLabel(mapper.readTree("{\"sector\":\"Technology\"}"))).isEqualTo("Technology");
    }
}
