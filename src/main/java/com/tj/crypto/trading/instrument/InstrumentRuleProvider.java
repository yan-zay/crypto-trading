package com.tj.crypto.trading.instrument;

import com.tj.crypto.common.domain.Exchange;

import java.util.List;

public interface InstrumentRuleProvider {
    Exchange exchange();
    List<InstrumentRuleSnapshot> fetch();
}
