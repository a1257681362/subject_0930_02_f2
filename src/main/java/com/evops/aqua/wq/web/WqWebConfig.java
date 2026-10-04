package com.evops.aqua.wq.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 水质模块幂等拦截器仅挂在 /api/aqua/wq/** 写路径上。
 */
@Configuration
public class WqWebConfig implements WebMvcConfigurer {

    private final WqIdempotencyInterceptor idempotencyInterceptor;

    public WqWebConfig(WqIdempotencyInterceptor idempotencyInterceptor) {
        this.idempotencyInterceptor = idempotencyInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(idempotencyInterceptor)
                .addPathPatterns("/api/aqua/wq/**");
    }
}
