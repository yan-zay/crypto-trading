package com.tj.crypto.admin;

import com.tj.crypto.research.agent.ResearchAgentEnvelope;
import com.tj.crypto.research.agent.ResearchAgentQuery;
import com.tj.crypto.research.agent.ResearchAgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** L0 只读研究入口；这里没有任何交易、凭据或配置写依赖。 */
@RestController
@RequestMapping("/api/admin/research-agent")
@RequiredArgsConstructor
public class ResearchAgentAdminController {

    private final ResearchAgentService researchAgentService;

    @GetMapping("/capabilities")
    public ResponseEntity<ResearchAgentEnvelope> capabilities() {
        return ResponseEntity.ok(researchAgentService.capabilities());
    }

    @PostMapping("/query")
    public ResponseEntity<ResearchAgentEnvelope> query(@RequestBody ResearchAgentQuery query) {
        return ResponseEntity.ok(researchAgentService.query(query));
    }

    /** 拒绝未知工具、自由参数和不可解析请求，同时保留统一的边界元数据。 */
    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class})
    public ResponseEntity<ResearchAgentEnvelope> rejectInvalidQuery(Exception ignored) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(researchAgentService.rejectedQuery());
    }
}
