package com.tj.crypto.admin;

import com.tj.crypto.admin.application.AuditService;
import com.tj.crypto.admin.domain.UserDO;
import com.tj.crypto.reliability.outbox.OutboxMapper;
import com.tj.crypto.reliability.outbox.OutboxService;
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
@RequestMapping("/api/admin/outbox")
@RequiredArgsConstructor
public class OutboxAdminController {
    private final OutboxMapper mapper;
    private final OutboxService service;
    private final AuditService auditService;

    @GetMapping
    public Object recent(@RequestParam(required = false) String status,
                         @RequestParam(defaultValue = "100") int limit) {
        return mapper.selectRecent(status, Math.max(1, Math.min(limit, 500)));
    }

    @GetMapping("/backlog")
    public Object backlog() {
        return service.backlog(System.currentTimeMillis());
    }

    @PostMapping("/{eventId}/retry")
    @Transactional
    public Object retry(@PathVariable String eventId, HttpServletRequest request) {
        boolean retried = mapper.retryDeadLetter(eventId, System.currentTimeMillis()) == 1;
        if (!retried) throw new IllegalStateException("Only DEAD_LETTER events can be retried");
        Object user = request.getAttribute("currentUser");
        auditService.logOperation(user instanceof UserDO current ? current.getUsername() : "system",
                "RETRY_OUTBOX_EVENT", "eventId=" + eventId);
        return java.util.Map.of("eventId", eventId, "status", "RETRY");
    }
}
