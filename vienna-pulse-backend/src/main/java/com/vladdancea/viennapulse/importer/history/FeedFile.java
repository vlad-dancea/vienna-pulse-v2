package com.vladdancea.viennapulse.importer.history;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/// The size and SHA-256 of a downloaded feed, which tell whether two imports read the same feed.
public record FeedFile(long size, String sha256) {
    public static FeedFile of(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Every Java runtime has SHA-256", exception);
        }
        try (var in = new DigestInputStream(Files.newInputStream(file), digest)) {
            var size = in.transferTo(OutputStream.nullOutputStream());
            return new FeedFile(size, HexFormat.of().formatHex(digest.digest()));
        }
    }
}
