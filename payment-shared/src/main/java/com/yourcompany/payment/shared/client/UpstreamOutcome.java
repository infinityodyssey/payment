package com.yourcompany.payment.shared.client;

/**
 * The audit verdict for one upstream call.
 *
 * Separate from the HTTP status because the LMS answers almost everything with 200 and
 * puts the real result in the body. A 200 carrying {@code status: "Failure"} is not a
 * success, and an operator reading LMS_API_LOG needs to see that without parsing the
 * stored payload.
 */
public record UpstreamOutcome(String upstreamStatus, String upstreamCode,
                              String outcome, String errorDetail) {

    public static final String SUCCESS = "SUCCESS";
    public static final String FAILURE = "FAILURE";
    public static final String NOT_FOUND = "NOT_FOUND";

    public static UpstreamOutcome success(String upstreamStatus, String upstreamCode) {
        return new UpstreamOutcome(upstreamStatus, upstreamCode, SUCCESS, null);
    }

    public static UpstreamOutcome failure(String upstreamStatus, String upstreamCode, String detail) {
        return new UpstreamOutcome(upstreamStatus, upstreamCode, FAILURE, detail);
    }

    public static UpstreamOutcome notFound(String upstreamStatus, String upstreamCode, String detail) {
        return new UpstreamOutcome(upstreamStatus, upstreamCode, NOT_FOUND, detail);
    }

    /** Transport-level verdict, used when the call never produced a response. */
    public static UpstreamOutcome transportFailure(String detail) {
        return new UpstreamOutcome(null, null, FAILURE, detail);
    }

    /**
     * The LMS envelope convention: {@code status} is "Success" or anything else,
     * {@code code} carries their numeric code. Default classifier for every LMS call.
     */
    public static UpstreamOutcome fromLmsEnvelope(UpstreamResponse response) {
        String status = response.text("status");
        String code = response.text("code");
        return "Success".equalsIgnoreCase(status)
                ? success(status, code)
                : failure(status, code, "upstream status was not Success");
    }

    /** For calls whose only signal is the HTTP status. */
    public static UpstreamOutcome fromHttpStatus(UpstreamResponse response) {
        return response.httpStatus() >= 200 && response.httpStatus() < 300
                ? success(null, null)
                : failure(null, null, "http " + response.httpStatus());
    }
}
