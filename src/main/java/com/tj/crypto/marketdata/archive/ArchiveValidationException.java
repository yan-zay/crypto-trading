package com.tj.crypto.marketdata.archive;

/** 归档格式、完整性或身份校验失败。 */
public class ArchiveValidationException extends RuntimeException {
    private final String code;

    public ArchiveValidationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public ArchiveValidationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
