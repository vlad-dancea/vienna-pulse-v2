package com.vladdancea.viennapulse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/// Starts the backend, with scheduling enabled for the daily feed import.
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ViennaPulseApplication {
    public static void main(String[] args) {
        SpringApplication.run(ViennaPulseApplication.class, args);
    }
}
