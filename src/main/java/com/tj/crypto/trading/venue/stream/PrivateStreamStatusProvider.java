package com.tj.crypto.trading.venue.stream;

import java.util.List;

public interface PrivateStreamStatusProvider {
    List<PrivateStreamStatus> statuses();
}
