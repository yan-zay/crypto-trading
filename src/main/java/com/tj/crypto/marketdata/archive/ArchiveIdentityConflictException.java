package com.tj.crypto.marketdata.archive;

/** 同一幂等键被用于不同事实时 fail-closed，绝不覆盖已有归档。 */
public final class ArchiveIdentityConflictException extends ArchiveValidationException {
    public ArchiveIdentityConflictException(String message) {
        super("ARCHIVE_IDENTITY_CONFLICT", message);
    }
}
