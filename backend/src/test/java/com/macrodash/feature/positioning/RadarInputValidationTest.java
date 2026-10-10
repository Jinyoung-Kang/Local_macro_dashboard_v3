package com.macrodash.feature.positioning;

import com.macrodash.support.InvalidRequestException;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class RadarInputValidationTest {

    @Test
    @Disabled("QA-037 재현 — 수정 전에는 모르는 투자자·매매 방향·기간을 그대로 수집기에 넘긴다")
    @DisplayName("QA-037 레이더 랭킹은 모르는 투자자·매매 방향·기간을 400으로 — 임의 이름의 저장본을 만들지 않게")
    void unknownRadarInputsAreRejected() {
        RadarService radar = mock(RadarService.class);
        PositioningController controller = new PositioningController(mock(CotService.class), mock(KrxService.class), radar);

        assertThatThrownBy(() -> controller.radarRanking("KOSPI", "아무나", "순매수", 30, "TODAY", null))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> controller.radarRanking("KOSPI", "외국인", "매수매도", 30, "TODAY", null))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> controller.radarRanking("KOSPI", "외국인", "순매수", 30, "WEEK", null))
                .isInstanceOf(InvalidRequestException.class);
        verifyNoInteractions(radar);
    }
}
