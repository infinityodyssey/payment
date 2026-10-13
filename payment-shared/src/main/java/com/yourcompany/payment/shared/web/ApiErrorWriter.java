package com.yourcompany.payment.shared.web;

import com.yourcompany.payment.shared.dto.ApiResponse;
import com.yourcompany.payment.shared.exception.ApiException;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Error responses from inside servlet filters, where @ControllerAdvice does not apply.
 *
 * Error bodies are never encrypted: the client must be able to read them without a
 * session key, since the commonest errors are the ones where the session or key is the
 * problem. They therefore contain no borrower data.
 */
@Component
@RequiredArgsConstructor
public class ApiErrorWriter {

    private final ObjectMapper objectMapper;

    public void write(HttpServletResponse response, ApiException e) throws IOException {
        response.setStatus(e.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.error(e.getErrorCode(), e.getMessage())));
    }
}
