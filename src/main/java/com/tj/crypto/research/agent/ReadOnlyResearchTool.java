package com.tj.crypto.research.agent;

import java.util.List;

/**
 * L0 研究工具契约。所有实现必须明确声明只读，并由服务层白名单二次校验。
 */
public interface ReadOnlyResearchTool {

    ResearchToolName name();

    String description();

    boolean readOnly();

    List<String> dataSources();

    List<String> limitations();

    Object execute();
}
