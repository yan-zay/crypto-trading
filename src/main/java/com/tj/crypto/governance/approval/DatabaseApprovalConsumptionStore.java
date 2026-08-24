package com.tj.crypto.governance.approval;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class DatabaseApprovalConsumptionStore implements ApprovalConsumptionStore {
    private final ApprovalConsumptionMapper mapper;

    @Override
    @Transactional
    public boolean tryConsume(ApprovalConsumption consumption) {
        int parentInserted = mapper.insertConsumption(consumption.requestId(),
                consumption.action().name(), consumption.resourceSha256(),
                consumption.governanceSubjectSha256(), consumption.requestedBy(),
                consumption.approvedByCsv(), consumption.requestCreatedAtMs(),
                consumption.requestExpiresAtMs(), consumption.consumedAtMs());
        if (parentInserted != 1) {
            throw new IllegalStateException("approval consumption was not inserted");
        }
        for (int index = 0; index < consumption.mfaReplayReservations().size(); index++) {
            MfaReplayReservation reservation = consumption.mfaReplayReservations().get(index);
            int reservationInserted = mapper.insertMfaReservation(reservation.reservationId(),
                    consumption.requestId(), reservation.approver(), index);
            if (reservationInserted != 1) {
                throw new IllegalStateException("MFA replay reservation was not inserted");
            }
        }
        return true;
    }
}
