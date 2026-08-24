package com.tj.crypto.autonomy.execution;

/** Capital policy identity plus the independent deterministic execution-policy digest. */
public record ExecutionPolicyBinding(
        String capitalPolicyId,
        long capitalPolicyVersion,
        String capitalPolicySha256,
        String executionPolicySha256
) {
    public ExecutionPolicyBinding {
        capitalPolicyId = ExecutionValues.id(capitalPolicyId, "capitalPolicyId", 192);
        if (capitalPolicyVersion < 1) {
            throw new IllegalArgumentException("capitalPolicyVersion must be positive");
        }
        capitalPolicySha256 = ExecutionValues.sha256(
                capitalPolicySha256, "capitalPolicySha256");
        executionPolicySha256 = ExecutionValues.sha256(
                executionPolicySha256, "executionPolicySha256");
    }

    public String canonicalId() {
        StringBuilder value = new StringBuilder("execution-policy-binding-v1");
        ExecutionValues.append(value, capitalPolicyId);
        ExecutionValues.append(value, capitalPolicyVersion);
        ExecutionValues.append(value, capitalPolicySha256);
        ExecutionValues.append(value, executionPolicySha256);
        return value.toString();
    }
}
