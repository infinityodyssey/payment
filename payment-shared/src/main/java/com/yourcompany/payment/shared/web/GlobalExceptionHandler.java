package com.yourcompany.payment.shared.web;

import com.yourcompany.payment.shared.dto.ApiResponse;
import com.yourcompany.payment.shared.exception.ApiException;
import com.yourcompany.payment.shared.crypto.SkipEncryption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The single place an exception becomes an HTTP response.
 *
 * @SkipEncryption so error bodies bypass the response encryption advice.
 *
 * Unexpected exceptions return a fixed code and message. Echoing exception text leaks
 * class names, SQL fragments and internal identifiers; the detail belongs in the log,
 * correlated by id.
 */
@Slf4j
@SkipEncryption
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleApi(ApiException e) {
        if (e.getStatus().is5xxServerError()) {
            log.error("Request failed: {}", e.getErrorCode());
        } else {
            log.info("Request rejected: {}", e.getErrorCode());
        }
        return ResponseEntity.status(e.getStatus())
                .body(ApiResponse.error(e.getErrorCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getDefaultMessage())
                .orElse("Invalid request");
        return ResponseEntity.badRequest().body(ApiResponse.error("VALIDATION_FAILED", message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("INTERNAL_ERROR", "Unable to process request"));
    }
}
