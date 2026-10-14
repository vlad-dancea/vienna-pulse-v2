package com.vladdancea.viennapulse.importer;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pulse.importer")
public record ImporterProperties(URI feedUrl) {}
