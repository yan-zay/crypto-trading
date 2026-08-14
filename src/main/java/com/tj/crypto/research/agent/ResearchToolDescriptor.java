package com.tj.crypto.research.agent;

import java.util.List;

/** 可审计的工具能力描述。 */
public record ResearchToolDescriptor(
        ResearchToolName name,
        String description,
        boolean readOnly,
        List<String> dataSources,
        List<String> limitations) {
}
