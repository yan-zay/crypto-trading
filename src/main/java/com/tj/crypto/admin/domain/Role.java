package com.tj.crypto.admin.domain;

/**
 * RBAC 角色枚举。
 * 权限从低到高：VIEWER < RESEARCHER < OPERATOR < RISK_MANAGER < ADMIN。
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

    /** 管理员：全部权限 */
    ADMIN;

    /**
     * 判断当前角色是否满足最低要求。
     * 角色按枚举顺序排列，序号越大权限越高。
     *
     * @param required 最低要求角色
     * @return true 如果当前角色权限 >= required
     */
    public boolean isAtLeast(Role required) {
        return this.ordinal() >= required.ordinal();
    }
}
