package com.tj.crypto.governance.security;

/** Approved classes of external, non-exportable signing custody. */
public enum CustodyProvider {
    AWS_KMS,
    AZURE_KEY_VAULT,
    GCP_KMS,
    HASHICORP_VAULT,
    HSM,
    EXTERNAL_SIGNER
}
