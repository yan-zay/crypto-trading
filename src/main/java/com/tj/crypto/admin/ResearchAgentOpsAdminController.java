package com.tj.crypto.admin;

import com.tj.crypto.admin.domain.UserDO;
import com.tj.crypto.research.agent.model.AgentModelResearchService;
import com.tj.crypto.research.agent.model.ModelHypothesisRequest;
import com.tj.crypto.research.agent.policy.AgentLevel;
import com.tj.crypto.research.agent.sandbox.AgentResearchSandboxService;
import com.tj.crypto.research.agent.sandbox.ExperimentPlanDraft;
import com.tj.crypto.research.agent.sandbox.PaperShadowProposalRequest;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/** ADMIN-only L1/L2 control plane; none of these endpoints exposes an order or credential tool. */
@RestController
@RequestMapping("/api/admin/research-agent/ops")
@RequiredArgsConstructor
public class ResearchAgentOpsAdminController {
    private final AgentResearchSandboxService sandbox;
    private final AgentModelResearchService modelResearch;
    private final com.tj.crypto.research.agent.sandbox.AgentProposalEvaluationService evaluations;

    @PostMapping("/sessions")
    public Object openSession(@RequestParam AgentLevel level,
                              @RequestParam(defaultValue = "900") long ttlSeconds,
                              HttpServletRequest request) {
        if (ttlSeconds < 1 || ttlSeconds > 21_600) {
            throw new IllegalArgumentException("ttlSeconds must be between 1 and 21600");
        }
        return sandbox.openSession(level, operator(request), Duration.ofSeconds(ttlSeconds));
    }

    @PostMapping("/sessions/{sessionId}/model-hypotheses")
    public Object generateModelHypothesis(@PathVariable String sessionId,
                                          @RequestBody ModelHypothesisRequest request) {
        return modelResearch.generateHypothesis(sessionId, request);
    }

    @PostMapping("/sessions/{sessionId}/plans")
    public Object generatePlan(@PathVariable String sessionId,
                               @RequestBody ExperimentPlanDraft request) {
        return sandbox.generateExperimentPlan(sessionId, request);
    }

    @PostMapping("/sessions/{sessionId}/proposals")
    public Object submitProposal(@PathVariable String sessionId,
                                 @RequestBody PaperShadowProposalRequest request) {
        return sandbox.submitProposal(sessionId, request);
    }

    @PostMapping("/sessions/{sessionId}/revoke")
    public Object revokeSession(@PathVariable String sessionId,
                                @RequestParam String reason,
                                HttpServletRequest request) {
        return sandbox.revokeSession(sessionId, operator(request), reason);
    }

    @PostMapping("/proposals/{proposalId}/revoke")
    public void revokeProposal(@PathVariable String proposalId,
                               @RequestParam String reason,
                               HttpServletRequest request) {
        sandbox.revokeProposal(proposalId, operator(request), reason);
        evaluations.refreshActiveScopes();
    }

    @PostMapping("/proposals/{proposalId}/consume")
    public Object consumeProposal(@PathVariable String proposalId,
                                  HttpServletRequest request) {
        return evaluations.consume(proposalId, operator(request));
    }

    private String operator(HttpServletRequest request) {
        Object current = request.getAttribute("currentUser");
        return current instanceof UserDO user ? user.getUsername() : "system";
    }
}
