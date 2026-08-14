package com.tj.crypto.admin.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.admin.application.AuditRecord;
import com.tj.crypto.admin.application.AuditService;
import com.tj.crypto.admin.domain.UserDO;
import com.tj.crypto.observability.slo.SloName;
import com.tj.crypto.observability.slo.TradingSloService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AdminAuditFilterTest {

    @Test
    void auditsPaperOrderWithoutCapturingRequestBodyOrCredentials() throws Exception {
        AuditService auditService = mock(AuditService.class);
        TradingSloService sloService = mock(TradingSloService.class);
        AdminAuditFilter filter = new AdminAuditFilter(auditService, new ObjectMapper(), sloService);
        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                "/api/admin/paper-trading/orders");
        request.addHeader("Authorization", "Bearer must-not-be-recorded");
        request.addHeader("X-Request-Id", "request-1");
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            UserDO user = new UserDO();
            user.setUsername("operator");
            req.setAttribute("currentUser", user);
            ((MockHttpServletResponse) res).setStatus(201);
        });

        ArgumentCaptor<AuditRecord> record = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditService).append(record.capture());
        assertThat(record.getValue().operator()).isEqualTo("operator");
        assertThat(record.getValue().outcome()).isEqualTo("SUCCESS");
        assertThat(record.getValue().detail()).doesNotContain("Bearer", "must-not-be-recorded");
        verify(sloService).record(SloName.AUDIT_APPEND, true, record.getValue().latencyMs());
        verify(sloService).recordPaperOrder(true, record.getValue().latencyMs());
        assertThat(response.getHeader("X-Request-Id")).isEqualTo("request-1");
    }

    @Test
    void recordsFailedWriteOutcome() throws Exception {
        AuditService auditService = mock(AuditService.class);
        TradingSloService sloService = mock(TradingSloService.class);
        AdminAuditFilter filter = new AdminAuditFilter(auditService, new ObjectMapper(), sloService);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/configs/x");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(422));

        ArgumentCaptor<AuditRecord> record = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditService).append(record.capture());
        assertThat(record.getValue().outcome()).isEqualTo("FAILURE");
        verify(sloService).record(SloName.AUDIT_APPEND, true, record.getValue().latencyMs());
    }
}
