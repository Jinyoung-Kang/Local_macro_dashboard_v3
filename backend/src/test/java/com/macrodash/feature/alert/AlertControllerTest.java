package com.macrodash.feature.alert;

import com.macrodash.support.CurrentUser;
import com.macrodash.support.InvalidRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AlertControllerTest {

    @Test
    @DisplayName("QA-020 읽음 처리는 한 번에 고른 항목 200개까지 — 그보다 많으면 400")
    void tooManyIdsAreRejected() {
        AlertController controller = new AlertController(mock(AlertService.class));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/alerts/inbox/read");
        request.setAttribute(CurrentUser.ATTRIBUTE, new CurrentUser(7, "m@test.local", "MEMBER", "FREE", null));
        List<Long> ids = LongStream.rangeClosed(1, 201).boxed().toList();

        assertThatThrownBy(() -> controller.read(new AlertController.ReadRequest(ids, null), request))
                .isInstanceOf(InvalidRequestException.class);
    }
}
