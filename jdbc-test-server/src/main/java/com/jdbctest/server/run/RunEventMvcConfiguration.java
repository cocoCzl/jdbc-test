package com.jdbctest.server.run;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Do not drain into SseEmitter's unbounded early-send buffer before MVC initializes its handler. */
@Configuration
class RunEventMvcConfiguration implements WebMvcConfigurer, AsyncHandlerInterceptor {

    static final String READY_ATTRIBUTE = RunEventMvcConfiguration.class.getName() + ".ready";

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this);
    }

    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request,
        HttpServletResponse response, Object handler) {
        if (request.getAttribute(READY_ATTRIBUTE) instanceof Runnable ready) {
            request.removeAttribute(READY_ATTRIBUTE);
            ready.run();
        }
    }
}
