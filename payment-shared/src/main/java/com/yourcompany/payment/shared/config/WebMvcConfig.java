package com.yourcompany.payment.shared.config;

import com.yourcompany.payment.shared.web.VerifiedCallerArgumentResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Registers the argument resolver that hands controllers the verified caller.
 *
 * Picked up by every service through SharedConfig's component scan, so a new controller
 * can declare {@code TokenClaims} or {@code SessionContext} as a parameter and get it,
 * with no per-service wiring to remember.
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final VerifiedCallerArgumentResolver verifiedCallerArgumentResolver;

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(verifiedCallerArgumentResolver);
    }
}
