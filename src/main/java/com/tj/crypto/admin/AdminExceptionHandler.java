package com.tj.crypto.admin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.tj.crypto.marketdata.backfill.HistoricalDataAccessException;

import java.util.Map;

/** Consistent, non-stacktrace error responses for admin APIs. */
@Slf4j
@RestControllerAdvice(basePackages = "com.tj.crypto.admin")
public class AdminExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of(
                "success", false, "error", error.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "success", false, "error", error.getMessage()));
    }

    @ExceptionHandler(HistoricalDataAccessException.class)
    public ResponseEntity<Map<String, Object>> upstreamDataFailure(
            HistoricalDataAccessException error) {
        log.warn("Historical market-data provider failed: {}", error.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                "success", false, "error", error.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> internalError(Exception error) {
        log.error("Unhandled admin API error", error);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "success", false, "error", "Internal server error"));
    }
}
