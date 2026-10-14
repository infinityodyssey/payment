package com.yourcompany.payment.shared.exception;

import org.springframework.http.HttpStatus;

/**
 * A concurrent request already advanced this session.
 *
 * Deliberately not retried. Re-applying a transition against state the caller never read
 * is how a session ends up bound to two agreements.
 */
public class SessionConflictException extends ApiException {
    public SessionConflictException() {
        super("SESSION_CONFLICT",
              "Another request is already in progress. Please reload the page and try again.",
              HttpStatus.CONFLICT);
    }
}
