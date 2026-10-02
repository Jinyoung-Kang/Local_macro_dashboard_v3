package com.macrodash.feature.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StatusControllerHealthTest {

    @Test
    @DisplayName("/api/health는 DB에 닿지 못하면 503이다 — 상수 ok로 헬스체크를 속이지 않는다")
    void healthReflectsDatabase() {
        DataStatusService status = mock(DataStatusService.class);
        StatusController controller = new StatusController(status, mock(VerificationService.class));

        when(status.databaseReachable()).thenReturn(true);
        assertThat(controller.health().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.health().getBody()).containsEntry("status", "ok").containsEntry("database", "ok");

        when(status.databaseReachable()).thenReturn(false);
        assertThat(controller.health().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(controller.health().getBody()).containsEntry("status", "degraded");
    }
}
