package com.vladdancea.viennapulse.api;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/// Web setup shared by every API endpoint.
@Configuration(proxyBeanMethods = false)
class ApiConfiguration implements WebMvcConfigurer {
    /// Lets the site and its Previews read the API from `pulse-api.vladdancea.com`, another origin.
    ///
    /// The site calls that hostname because it has no Worker in front of it, so polling does not
    /// count against Cloudflare's daily Workers requests. The API is public and read-only, so
    /// only GET and HEAD are allowed, and without credentials.
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("https://pulse.vladdancea.com", "https://pr-*.pulse.vladdancea.com")
                .allowedMethods("GET", "HEAD");
    }

    /// Gives each API response an ETag, a hash of its body, and answers a request whose
    /// `If-None-Match` carries the same hash with an empty 304 instead of the body.
    @Bean
    FilterRegistrationBean<ShallowEtagHeaderFilter> etagFilter() {
        var filter = new ShallowEtagHeaderFilter();
        // Tomcat only compresses responses whose ETag is weak, since a strong one promises the
        // exact bytes. A weak one promises the same content, which is all the hash checks.
        filter.setWriteWeakETag(true);
        var registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/*");
        return registration;
    }

    /// Sends the headers that OWASP's REST Security Cheat Sheet asks of an API. Responses from
    /// `pulse-api.vladdancea.com` reach the browser without the site's `_headers` file, so the
    /// backend sets them itself: no content sniffing, HTTPS only, and nothing to load or frame.
    @Bean
    FilterRegistrationBean<Filter> securityHeadersFilter() {
        Filter filter = (request, response, chain) -> {
            var headers = (HttpServletResponse) response;
            headers.setHeader("X-Content-Type-Options", "nosniff");
            headers.setHeader("Strict-Transport-Security", "max-age=31536000");
            headers.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
            chain.doFilter(request, response);
        };
        // Tomcat keeps these when an exception without a handler is answered from /error.
        var registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/*");
        return registration;
    }

    /// The current time, as a bean so that tests can fix it.
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
