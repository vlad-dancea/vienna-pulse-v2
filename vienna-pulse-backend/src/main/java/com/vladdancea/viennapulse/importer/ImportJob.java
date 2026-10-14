package com.vladdancea.viennapulse.importer;

import com.vladdancea.viennapulse.importer.zip.FeedDownloader;
import com.vladdancea.viennapulse.importer.zip.FeedValidator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

@Service
@Slf4j
@RequiredArgsConstructor
public class ImportJob {
    private final FeedDownloader downloader;
    private final FeedValidator validator;

    @Scheduled(cron = "${pulse.importer.cron}", zone = "Europe/Vienna")
    public void run() {
        log.info("Feed import triggered");
        final Path feed;
        try {
            feed = downloader.download();
        } catch (RestClientException exception) {
            log.error("Feed download failed", exception);
            return;
        }

        try {
            validator.validate(feed);
            log.info("Feed accepted: {}", feed);
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(feed);
            } catch (IOException cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            log.error("Feed rejected", exception);
        }
    }
}
