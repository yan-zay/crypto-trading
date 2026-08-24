package com.tj.crypto.autonomy.agent;

/** 隔离账户或子账户的自治层引用，不包含凭据。 */
public record AccountRef(String accountId) {
    public AccountRef {
        accountId = AutonomyValues.text(accountId, "accountId");
    }
}
