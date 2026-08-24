package com.tj.crypto.autonomy.capital;

enum PolicySignatureVerification {
    VERIFIED,
    UNKNOWN_KEY,
    KEY_NOT_CURRENT,
    POLICY_ISSUED_OUTSIDE_KEY_VALIDITY,
    INVALID_SIGNATURE
}
