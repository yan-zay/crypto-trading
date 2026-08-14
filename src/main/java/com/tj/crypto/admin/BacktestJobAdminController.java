package com.tj.crypto.admin;

import com.tj.crypto.admin.application.AuditService;
import com.tj.crypto.admin.domain.UserDO;
import com.tj.crypto.backtest.job.BacktestComparisonService;
import com.tj.crypto.backtest.job.BacktestJobRequest;
import com.tj.crypto.backtest.job.BacktestJobService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@RestController
@RequestMapping("/api/admin/backtest-jobs")
@RequiredArgsConstructor
public class BacktestJobAdminController {
    private final BacktestJobService service;
    private final BacktestComparisonService comparisonService;
    private final AuditService auditService;

    @PostMapping
    @Transactional
    public Object submit(@RequestBody BacktestJobRequest request, HttpServletRequest servletRequest) {
        String operator = operator(servletRequest);
        Object result = service.submit(request, operator);
        auditService.logOperation(operator, "SUBMIT_BACKTEST_JOB",
                request.type() + ":" + request.exchange() + ":" + request.symbol());
        return result;
    }

    @GetMapping
    public Object recent(@RequestParam(required = false) String status,
                         @RequestParam(defaultValue = "100") int limit) {
        return service.recent(status, limit);
    }

    @GetMapping("/{jobId}")
    public Object find(@PathVariable String jobId) {
        Object job = service.find(jobId);
        if (job == null) throw new IllegalArgumentException("Unknown backtest job: " + jobId);
        return job;
    }

    @PostMapping("/{jobId}/cancel")
    @Transactional
    public Object cancel(@PathVariable String jobId, HttpServletRequest request) {
        Object result = service.cancel(jobId);
        auditService.logOperation(operator(request), "CANCEL_BACKTEST_JOB", "jobId=" + jobId);
        return result;
    }

    @GetMapping("/compare")
    public Object compare(@RequestParam List<String> runIds) {
        return comparisonService.compare(runIds);
    }

    private String operator(HttpServletRequest request) {
        Object value = request.getAttribute("currentUser");
        return value instanceof UserDO user ? user.getUsername() : "system";
    }
}
