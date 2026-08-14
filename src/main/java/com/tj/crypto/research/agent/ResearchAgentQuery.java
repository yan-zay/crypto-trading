package com.tj.crypto.research.agent;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 类型化 L0 查询。查询只选择固定工具，不接受自由文本指令或任意参数。
 */
public final class ResearchAgentQuery {

    private final ResearchToolName tool;

    @JsonCreator
    public ResearchAgentQuery(@JsonProperty("tool") ResearchToolName tool) {
        this.tool = tool;
    }

    public ResearchToolName tool() {
        return tool;
    }

    /** 未声明字段一律拒绝，防止把自然语言或写命令伪装成工具参数。 */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("Unsupported research query field: " + field);
    }
}
