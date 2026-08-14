package com.tj.crypto.admin;

import com.tj.crypto.admin.application.AuditService;
import com.tj.crypto.trading.reconciliation.ReconciliationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api/admin/reconciliation")
@RequiredArgsConstructor
public class ReconciliationAdminController {
    private final ReconciliationService service;
    private final AuditService auditService;

    @PostMapping("/run")
    @Transactional
    public Object run(@RequestParam String accountId, HttpServletRequest request) {
        Object report = service.run(accountId);
        auditService.logOperation(operator(request), "RUN_RECONCILIATION", "accountId=" + accountId);
        return report;
    }

    @GetMapping("/incidents")
    public Object incidents(@RequestParam(required = false) String accountId,
                            @RequestParam(required = false) String status,
                            @RequestParam(defaultValue = "200") int limit) {
        return service.incidents(accountId, status, limit);
    }

    @PostMapping("/incidents/{incidentId}/resolve")
    @Transactional
    public Object resolve(@PathVariable String incidentId,
                          @RequestParam String resolution,
                          HttpServletRequest request) {
        String operator = operator(request);
        Object result = service.resolve(incidentId, operator, resolution);
        auditService.logOperation(operator, "RESOLVE_RECONCILIATION_INCIDENT",
                "incidentId=" + incidentId + ",resolution=" + resolution);
        return result;
    }

    private String operator(HttpServletRequest request) {
        Object user = request.getAttribute("currentUser");
        return user instanceof com.tj.crypto.admin.domain.UserDO current
                ? current.getUsername() : "system";
    }
}
