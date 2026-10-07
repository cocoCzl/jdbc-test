package com.jdbctest.server.web;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.resource.PathResourceResolver;
import org.springframework.web.servlet.resource.ResourceResolverChain;

@Configuration
@ConditionalOnProperty(name = "jdbc-test.web.directory")
public class StaticWebConfiguration implements WebMvcConfigurer {
    private final String location;

    public StaticWebConfiguration(@Value("${jdbc-test.web.directory}") String directory) {
        location = Path.of(directory).toAbsolutePath().normalize().toUri().toString() + "/";
    }

    @Override public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**").addResourceLocations(location + "assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
        registry.addResourceHandler("/**").addResourceLocations(location)
                .setCacheControl(CacheControl.noCache()).resourceChain(false).addResolver(new SpaResolver());
    }

    @Override public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }

    private static final class SpaResolver extends PathResourceResolver {
        @Override protected Resource resolveResourceInternal(HttpServletRequest request, String path,
                List<? extends Resource> locations, ResourceResolverChain chain) {
            if (path.equals("api") || path.startsWith("api/") || path.equals("actuator")
                    || path.startsWith("actuator/") || path.equals("error") || path.startsWith("error/")) return null;
            Resource resource = super.resolveResourceInternal(request, path, locations, chain);
            if (resource != null) return resource;
            if (request == null || !(request.getMethod().equals("GET") || request.getMethod().equals("HEAD"))
                    || path.contains(".") || path.startsWith("assets/")) return null;
            String accept = request.getHeader("Accept");
            return accept != null && accept.contains("text/html")
                    ? super.resolveResourceInternal(request, "index.html", locations, chain) : null;
        }
    }
}
