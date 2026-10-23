package com.vladdancea.viennapulse.api;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletResponse;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.web.filter.ShallowEtagHeaderFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/// Web setup shared by every API endpoint.
@Configuration(proxyBeanMethods = false)
class ApiConfiguration implements WebMvcConfigurer {
    // The API's queries take about a second in production. One that takes five holds a connection
    // of the small pool that other requests wait for, so it is cancelled instead of waited for.
    private static final int QUERY_TIMEOUT_SECONDS = 5;
    // PgJDBC cancels a query over the limit by asking the database, which a database that stopped
    // answering ignores. Twice the query limit leaves the cancellation time to arrive.
    private static final int NETWORK_TIMEOUT_MILLISECONDS = 10_000;

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

    /// The database client of the API services, which cancels any query that runs for more than 5
    /// seconds and gives up on a database that sends nothing for 10. It is not a default candidate,
    /// so Spring Boot still configures the importer's client, whose statements take minutes, and
    /// injects this one only where [ApiDatabase] asks.
    @Bean(defaultCandidate = false)
    @ApiDatabase
    JdbcClient apiJdbcClient(DataSource dataSource) {
        var template = new JdbcTemplate(new NetworkTimeoutDataSource(dataSource));
        template.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        return JdbcClient.create(template);
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

    // Hikari sets a borrowed connection's network timeout back to the pool's when it returns, so the
    // importer's connections keep waiting as long as their statements take.
    private static final class NetworkTimeoutDataSource extends DelegatingDataSource {
        NetworkTimeoutDataSource(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public Connection getConnection() throws SQLException {
            var connection = super.getConnection();
            try {
                // PgJDBC reads with a socket timeout and leaves the executor unused.
                connection.setNetworkTimeout(Runnable::run, NETWORK_TIMEOUT_MILLISECONDS);
            } catch (SQLException exception) {
                connection.close();
                throw exception;
            }
            return connection;
        }
    }
}
