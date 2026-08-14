package com.tj.crypto.admin.domain;

/**
 * RBAC 角色枚举。
 * LIVE_TRADER 是隔离职责而不是层级中的“更高运营角色”；ADMIN 才拥有全部权限。
 */
public enum Role {

    /** 只读：查看系统状态、信号、因子、策略 */
    VIEWER,

    /** 研究：查看 + 回测、覆盖率查询 */
    RESEARCHER,

    /** 运营：查看 + 策略启停、配置草稿 */
    OPERATOR,

    /** 风控：查看 + KillSwitch、风控配置变更 */
    RISK_MANAGER,

    /** 实盘命令：必须与普通运营和风控角色隔离分配 */
    LIVE_TRADER,

    /** 管理员：全部权限 */
    ADMIN;

    /**
     * 判断当前角色是否拥有所需 capability。不要使用 ordinal 推导互斥职责。
     *
     * @param required 最低要求角色
     * @return true 如果当前角色权限 >= required
     */
    public boolean isAtLeast(Role required) {
        if (required == null) return false;
        return switch (this) {
            case ADMIN -> true;
            case LIVE_TRADER -> required == VIEWER || required == LIVE_TRADER;
            case RISK_MANAGER -> required == VIEWER || required == RESEARCHER
                    || required == OPERATOR || required == RISK_MANAGER;
            case OPERATOR -> required == VIEWER || required == RESEARCHER
                    || required == OPERATOR;
            case RESEARCHER -> required == VIEWER || required == RESEARCHER;
            case VIEWER -> required == VIEWER;
        };
    }
}
