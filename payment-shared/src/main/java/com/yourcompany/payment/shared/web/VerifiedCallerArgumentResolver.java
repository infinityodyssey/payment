package com.yourcompany.payment.shared.web;

import com.yourcompany.payment.shared.exception.ApiException;
import com.yourcompany.payment.shared.exception.SessionException;
import com.yourcompany.payment.shared.security.BorrowerAuthFilter;
import com.yourcompany.payment.shared.security.PartnerAuthFilter;
import com.yourcompany.payment.shared.security.SessionContext;
import com.yourcompany.payment.shared.security.TokenClaims;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Lets a controller declare {@code TokenClaims} or {@code SessionContext} as a method
 * parameter and receive the verified object, instead of pulling a request attribute out
 * and casting it.
 *
 * The filters are already the only place a token is verified; this is about the other
 * half of that — a controller should not contain the plumbing that fetches the result
 * either. Reading an attribute and null-checking it is three lines of boilerplate that
 * every new endpoint has to copy, and the copy is where someone eventually forgets the
 * check.
 *
 * <h2>Absent means misconfigured, so it throws</h2>
 * If the attribute is missing, the filter did not run on this path — a wiring error, not
 * a caller error. The resolver fails closed with 401 rather than handing a controller a
 * null, which would surface later as a NullPointerException and a 500 somewhere
 * unrelated. Failing here says what is actually wrong.
 *
 * {@code TokenClaims} is resolved from the borrower attribute first and the partner
 * attribute second. Only one is ever present: the two live on different filter chains,
 * selected by path.
 */
@Slf4j
@Component
public class VerifiedCallerArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        Class<?> type = parameter.getParameterType();
        return TokenClaims.class.equals(type) || SessionContext.class.equals(type);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {

        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        if (request == null) {
            throw unauthenticated(parameter);
        }

        if (SessionContext.class.equals(parameter.getParameterType())) {
            Object session = request.getAttribute(SessionContext.ATTRIBUTE);
            if (session == null) {
                log.error("No session context on {} — SessionContextFilter is not mounted on this path",
                        request.getRequestURI());
                throw SessionException.required();
            }
            return session;
        }

        Object claims = request.getAttribute(BorrowerAuthFilter.BORROWER_CLAIMS);
        if (claims == null) {
            claims = request.getAttribute(PartnerAuthFilter.PARTNER_CLAIMS);
        }
        if (claims == null) {
            log.error("No verified token on {} — no auth filter is mounted on this path",
                    request.getRequestURI());
            throw unauthenticated(parameter);
        }
        return claims;
    }

    private ApiException unauthenticated(MethodParameter parameter) {
        return new ApiException("TOKEN_MISSING", "Authentication required.", HttpStatus.UNAUTHORIZED);
    }
}
