package com.yourcompany.payment.shared.web;

import com.yourcompany.payment.shared.security.SecurityHeaders;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * One id per request, in the MDC, on the audit row, on the LMS interaction row and in
 * the response header. This is what makes an incident reconstructable across the
 * ledger, the logs and the upstream calls.
 *
 * A client-supplied value is accepted only if it parses as a UUID, so the field cannot
 * be used to inject content into a log line.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String incoming = request.getHeader(SecurityHeaders.CORRELATION_ID);
        String correlationId = isUuid(incoming) ? incoming : UUID.randomUUID().toString();

        MDC.put(MDC_KEY, correlationId);
        response.setHeader(SecurityHeaders.CORRELATION_ID, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    public static String current() {
        String cid = MDC.get(MDC_KEY);
        return cid == null ? "unknown" : cid;
    }

    private boolean isUuid(String value) {
        if (value == null || value.length() != 36) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
