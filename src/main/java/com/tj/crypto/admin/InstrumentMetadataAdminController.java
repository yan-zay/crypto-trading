package com.tj.crypto.admin;

import com.tj.crypto.admin.application.AuditService;
import com.tj.crypto.admin.domain.UserDO;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.trading.instrument.InstrumentMetadataRefreshService;
import com.tj.crypto.trading.instrument.InstrumentMetadataService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/instruments")
@RequiredArgsConstructor
public class InstrumentMetadataAdminController {
    private final InstrumentMetadataService metadataService;
    private final InstrumentMetadataRefreshService refreshService;
    private final AuditService auditService;

    @GetMapping
    public Object current() {
        return metadataService.current();
    }

    @PostMapping("/refresh")
    public Object refresh(@RequestParam(required = false) Exchange exchange,
                          HttpServletRequest request) {
        Object report = exchange == null ? refreshService.refreshAll() : refreshService.refresh(exchange);
        Object user = request.getAttribute("currentUser");
        auditService.logOperation(user instanceof UserDO current ? current.getUsername() : "system",
                "REFRESH_INSTRUMENT_METADATA", "exchange=" + exchange);
        return report;
    }
}
