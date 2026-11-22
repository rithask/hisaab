package com.hisaab.settings.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Refuses requests to endpoints marked {@link RequiresFeature} while their feature is off. The refusal goes through
 * the usual error handling, so the app shows the same message as any other.
 */
@Configuration
public class FeatureGate implements HandlerInterceptor, WebMvcConfigurer {
    private final FeatureService features;

    public FeatureGate(FeatureService features) {
        this.features = features;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        if (handler instanceof HandlerMethod m) {
            RequiresFeature required = AnnotatedElementUtils.findMergedAnnotation(m.getMethod(), RequiresFeature.class);
            if (required == null) required = AnnotatedElementUtils.findMergedAnnotation(m.getBeanType(), RequiresFeature.class);
            if (required != null) features.require(required.value());
        }
        return true;
    }
}
