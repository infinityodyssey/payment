package com.yourcompany.payment.shared.exception;

/**
 * An upstream answered with an error status.
 *
 * Looks identical to {@link UpstreamException} from the borrower's side - same code,
 * same message, same 503 - but carries the upstream's HTTP status inwards so a client
 * can react to it. The one case that matters today is 401: the cached bearer token has
 * been rejected, so it must be evicted and the call retried once before giving up.
 */
public class UpstreamStatusException extends UpstreamException {

    private final int httpStatus;

    public UpstreamStatusException(UpstreamException cause, int httpStatus) {
        super(cause.getErrorCode(), cause.getMessage());
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public boolean isUnauthorized() {
        return httpStatus == 401 || httpStatus == 403;
    }
}
