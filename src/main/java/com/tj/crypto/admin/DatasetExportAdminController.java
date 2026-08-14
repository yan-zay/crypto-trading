package com.tj.crypto.admin;

import com.tj.crypto.admin.application.AuditService;
import com.tj.crypto.research.export.DatasetExportRequest;
import com.tj.crypto.research.export.DatasetExportService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/dataset-exports")
@RequiredArgsConstructor
public class DatasetExportAdminController {
    private final DatasetExportService service;
    private final AuditService auditService;

    @PostMapping
    public Object export(@RequestBody DatasetExportRequest request, HttpServletRequest servletRequest) {
        String operator = operator(servletRequest);
        Object result = service.export(request, operator);
        auditService.logOperation(operator, "CREATE_DATASET_EXPORT", request.type().name());
        return result;
    }

    @GetMapping
    public Object recent(@RequestParam(defaultValue = "100") int limit) {
        return service.recent(limit);
    }

    @GetMapping("/{exportId}/download")
    public ResponseEntity<Resource> download(@PathVariable String exportId) {
        Resource resource = service.resource(exportId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + resource.getFilename() + "\"")
                .body(resource);
    }

    private String operator(HttpServletRequest request) {
        Object user = request.getAttribute("currentUser");
        return user instanceof com.tj.crypto.admin.domain.UserDO current
                ? current.getUsername() : "system";
    }
}
