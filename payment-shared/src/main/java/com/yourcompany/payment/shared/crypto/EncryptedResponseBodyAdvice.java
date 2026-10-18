package com.yourcompany.payment.shared.crypto;

import com.yourcompany.payment.shared.config.SharedProperties;
import com.yourcompany.payment.shared.dto.ApiResponse;
import com.yourcompany.payment.shared.dto.EncryptedEnvelope;
import com.yourcompany.payment.shared.security.SessionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import tools.jackson.databind.ObjectMapper;

/**
 * Encrypts successful response bodies.
 *
 * There is no plaintext escape hatch. The legacy version honoured an
 * "X-Accept-Plaintext: true" request header that its own CORS allow-list advertised, so
 * any caller could ask for the borrower's data in the clear.
 *
 * Error bodies are left unencrypted on purpose: they carry no borrower data, and the
 * commonest errors are exactly the ones where the session or the key is the problem.
 */
@Slf4j
@ControllerAdvice
@RequiredArgsConstructor
public class EncryptedResponseBodyAdvice implements ResponseBodyAdvice<Object> {

    private final AesGcmCipherService cipherService;
    private final ObjectMapper objectMapper;
    private final SharedProperties props;

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return props.getSecurity().isRequireEncryptedPayloads()
                && !returnType.hasMethodAnnotation(SkipEncryption.class)
                && !returnType.getDeclaringClass().isAnnotationPresent(SkipEncryption.class);
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {

        if (body == null || body instanceof EncryptedEnvelope) {
            return body;
        }
        if (body instanceof ApiResponse<?> api && !api.isSuccess()) {
            return body;
        }

        SessionContext ctx = contextFrom(request);
        if (ctx == null) {
            // No session means no key. Returning plaintext here would be the same hole
            // the legacy code had, so return nothing instead.
            log.error("Response encryption requested with no session in context");
            response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
            return ApiResponse.error("INTERNAL_ERROR", "Unable to process request");
        }

        try {
            return cipherService.encrypt(objectMapper.writeValueAsString(body),
                    ctx.sessionKey(), ctx.sessionId());
        } catch (Exception e) {
            log.error("Response encryption failed");
            response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
            return ApiResponse.error("INTERNAL_ERROR", "Unable to process request");
        }
    }

    private SessionContext contextFrom(ServerHttpRequest request) {
        if (request instanceof ServletServerHttpRequest servlet) {
            return (SessionContext) servlet.getServletRequest().getAttribute(SessionContext.ATTRIBUTE);
        }
        return null;
    }
}
