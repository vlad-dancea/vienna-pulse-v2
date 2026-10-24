package com.vladdancea.viennapulse.live;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/// Gives the rounds of [LiveDepartures] a thread of their own.
@Configuration(proxyBeanMethods = false)
class LiveConfiguration {
    static final String ROUNDS = "liveRounds";

    /// The scheduler of the rounds. The application's scheduler has one thread, which the import
    /// holds for minutes every morning, and the rounds must go on meanwhile. It is not a default
    /// candidate, so Spring Boot still sets up the application's scheduler for everything else.
    @Bean(name = ROUNDS, defaultCandidate = false)
    ThreadPoolTaskScheduler liveRounds() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("live-rounds-");
        return scheduler;
    }
}
