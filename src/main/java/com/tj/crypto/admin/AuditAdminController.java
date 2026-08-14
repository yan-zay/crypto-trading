package com.tj.crypto.admin;

import com.tj.crypto.admin.application.AuditService;
import com.tj.crypto.admin.application.AuditVerificationResult;
import com.tj.crypto.admin.application.AuditVerificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only audit search and integrity verification endpoints. */
@RestController
@RequestMapping("/api/admin/audit")
@RequiredArgsConstructor
public class AuditAdminController {
    private final AuditService auditService;
    private final AuditVerificationService verificationService;

    @GetMapping
    public Object search(@RequestParam(required = false) String operator,
                         @RequestParam(required = false) String operationType,
                         @RequestParam(required = false) String outcome,
                         @RequestParam(required = false) String resourceType,
                         @RequestParam(defaultValue = "100") int limit) {
        return auditService.search(operator, operationType, outcome, resourceType, limit);
    }

    @GetMapping("/verify")
    public AuditVerificationResult verify() {
        return verificationService.verify();
    }
}
