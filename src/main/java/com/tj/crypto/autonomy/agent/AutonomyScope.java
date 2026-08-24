package com.tj.crypto.autonomy.agent;

/** L4 预批准的精确策略/账户/venue/标的组合，避免独立白名单产生意外笛卡尔积。 */
public record AutonomyScope(
        StrategyRef strategy,
        AccountRef account,
        VenueRef venue,
        InstrumentRef instrument) {
    public AutonomyScope {
        if (strategy == null || account == null || venue == null || instrument == null) {
            throw new IllegalArgumentException("complete autonomy scope is required");
        }
    }

    public static AutonomyScope from(TradeProposal proposal) {
        if (proposal == null) throw new IllegalArgumentException("proposal is required");
        return new AutonomyScope(proposal.strategy(), proposal.account(), proposal.venue(), proposal.instrument());
    }
}
