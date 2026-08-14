package com.tj.crypto.admin;

import com.tj.crypto.research.agent.ResearchAgentEnvelope;
import com.tj.crypto.research.agent.ResearchAgentQuery;
import com.tj.crypto.research.agent.ResearchAgentService;
import com.tj.crypto.research.agent.ResearchToolName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ResearchAgentAdminControllerTest {

    private ResearchAgentService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(ResearchAgentService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ResearchAgentAdminController(service)).build();
    }

    @Test
    @DisplayName("GET capabilities 明确返回 L0、无模型和只读元数据")
    void shouldExposeReadOnlyCapabilities() throws Exception {
        when(service.capabilities()).thenReturn(response("CAPABILITIES"));

        mockMvc.perform(get("/api/admin/research-agent/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentLevel").value("L0"))
                .andExpect(jsonPath("$.modelConnected").value(false))
                .andExpect(jsonPath("$.readOnly").value(true))
                .andExpect(jsonPath("$.deterministic").value(true))
                .andExpect(jsonPath("$.generatedAt").isNumber())
                .andExpect(jsonPath("$.dataSources[0]").value("runtime:test"))
                .andExpect(jsonPath("$.limitations[0]").value("not trading advice"));
    }

    @Test
    @DisplayName("POST query 只接受类型化白名单工具")
    void shouldExecuteTypedReadOnlyQuery() throws Exception {
        when(service.query(any())).thenReturn(response("STRATEGY_CATALOG"));

        mockMvc.perform(post("/api/admin/research-agent/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tool\":\"STRATEGY_CATALOG\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("STRATEGY_CATALOG"))
                .andExpect(jsonPath("$.readOnly").value(true));

        verify(service).query(any(ResearchAgentQuery.class));
    }

    @Test
    @DisplayName("订单工具名或注入附加参数在进入服务前被拒绝")
    void shouldRejectUnknownToolsAndInjectedArguments() throws Exception {
        when(service.rejectedQuery()).thenReturn(response("QUERY_REJECTED"));

        mockMvc.perform(post("/api/admin/research-agent/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tool\":\"PLACE_ORDER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.operation").value("QUERY_REJECTED"))
                .andExpect(jsonPath("$.dataSources").isNotEmpty())
                .andExpect(jsonPath("$.limitations").isNotEmpty());
        mockMvc.perform(post("/api/admin/research-agent/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tool\":\"STRATEGY_CATALOG\",\"arguments\":{\"command\":\"placeOrder\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.operation").value("QUERY_REJECTED"));

        verify(service, never()).query(any());
    }

    private ResearchAgentEnvelope response(String operation) {
        return new ResearchAgentEnvelope(
                "L0", false, operation, true, true,
                Instant.parse("2026-08-13T00:00:00Z"),
                List.of("runtime:test"),
                List.of("not trading advice"),
                Map.of("status", "ok"));
    }
}
