package com.tj.crypto.governance.approval;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ApprovalConsumptionMapper {
    @Insert("""
            INSERT INTO governance_approval_consumption
                (request_id, approval_action, resource_sha256, governance_subject_sha256,
                 requested_by, approved_by,
                 request_created_at_ms, request_expires_at_ms, consumed_at_ms)
            VALUES
                (#{requestId}, #{action}, #{resourceSha256}, #{governanceSubjectSha256},
                 #{requestedBy}, #{approvedBy},
                 #{requestCreatedAtMs}, #{requestExpiresAtMs}, #{consumedAtMs})
            """)
    int insertConsumption(@Param("requestId") String requestId,
                          @Param("action") String action,
                          @Param("resourceSha256") String resourceSha256,
                          @Param("governanceSubjectSha256") String governanceSubjectSha256,
                          @Param("requestedBy") String requestedBy,
                          @Param("approvedBy") String approvedBy,
                          @Param("requestCreatedAtMs") long requestCreatedAtMs,
                          @Param("requestExpiresAtMs") long requestExpiresAtMs,
                          @Param("consumedAtMs") long consumedAtMs);

    @Insert("""
            INSERT INTO governance_approval_mfa_reservation
                (replay_reservation_id, request_id, approver, reservation_ordinal)
            VALUES (#{reservationId}, #{requestId}, #{approver}, #{ordinal})
            """)
    int insertMfaReservation(@Param("reservationId") String reservationId,
                             @Param("requestId") String requestId,
                             @Param("approver") String approver,
                             @Param("ordinal") int ordinal);
}
