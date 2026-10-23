package com.vladdancea.viennapulse.api;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.cache.autoconfigure.CacheManagerCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/// Web setup shared by every API endpoint.
@Configuration(proxyBeanMethods = false)
@EnableCaching
class ApiConfiguration {
    // Every map asks every 15 seconds and glides its vehicles over that time, so positions up to one
    // step old do not show, and the database works out the vehicles at most once per step.
    static final Duration VEHICLE_STEP = Duration.ofSeconds(5);

    /// The caches of [CachedResponses], each dropping the entries no request uses any more. The
    /// stops and lines of a replaced timetable go an hour after the import, and the vehicles of a
    /// step soon after it ends, so the caches hold a few megabytes at most.
    @Bean
    CacheManagerCustomizer<CaffeineCacheManager> responseCaches() {
        return manager -> {
            var timetable = Duration.ofHours(1);
            manager.registerCustomCache(
                    CachedResponses.STOPS,
                    Caffeine.newBuilder()
                            .expireAfterAccess(timetable)
                            .recordStats()
                            .build());
            manager.registerCustomCache(
                    CachedResponses.LINES,
                    Caffeine.newBuilder()
                            .expireAfterAccess(timetable)
                            .recordStats()
                            .build());
            manager.registerCustomCache(
                    CachedResponses.VEHICLES,
                    Caffeine.newBuilder()
                            .expireAfterWrite(VEHICLE_STEP.multipliedBy(2))
                            .recordStats()
                            .build());
        };
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

    /// The current time, as a bean so that tests can fix it.
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
