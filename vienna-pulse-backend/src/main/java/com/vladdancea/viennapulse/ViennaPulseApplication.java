package com.vladdancea.viennapulse;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/// Starts the backend, with scheduling enabled for the daily feed import.
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ViennaPulseApplication {
    public static void main(String[] args) {
        SpringApplication.run(ViennaPulseApplication.class, args);
    }

    /// The current time, as a bean so that tests can fix it.
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
