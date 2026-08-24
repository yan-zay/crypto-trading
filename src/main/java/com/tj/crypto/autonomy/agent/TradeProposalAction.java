package com.tj.crypto.autonomy.agent;

/** Agent 能表达的全部长期自治动作；不存在晋级、权限、风控、密钥或提款动作。 */
public enum TradeProposalAction {
    L3_LIVE_ADVICE,
    L4_SELECT_PREAPPROVED,
    L4_ADJUST_PREAPPROVED
}
