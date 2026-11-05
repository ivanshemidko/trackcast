package io.github.ivanshemidko.spotify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpHeaders;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Shared by token refresh and Web API requests. A restart must not bypass Spotify's cooldown. */
public class SpotifyRateLimit {
    private static final long FALLBACK_SECONDS = 1800;
    private final Path file;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();
    private Instant notBefore = Instant.EPOCH;
    private int consecutiveLimits;

    record SavedLimit(String notBefore, int consecutiveLimits) {}

    public static class RateLimitedException extends IOException {
        private final Instant retryAt;

        RateLimitedException(Instant retryAt) {
            super("Spotify rate limit; requests paused until " + retryAt);
            this.retryAt = retryAt;
        }

        public Instant retryAt() { return retryAt; }
    }

    public SpotifyRateLimit() { this(null, Clock.systemUTC()); }
    public SpotifyRateLimit(Path file) { this(file, Clock.systemUTC()); }

    SpotifyRateLimit(Path file, Clock clock) {
        this.file = file;
        this.clock = clock;
        if (file == null || !Files.exists(file)) return;
        try {
            SavedLimit saved = mapper.readValue(file.toFile(), SavedLimit.class);
            notBefore = Instant.parse(saved.notBefore());
            if (saved.consecutiveLimits() < 0) throw new IllegalArgumentException("Invalid limit count");
            consecutiveLimits = saved.consecutiveLimits();
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Cannot read Spotify cooldown file: " + file, e);
        }
    }

    public void beforeRequest() throws RateLimitedException {
        if (clock.instant().isBefore(notBefore)) throw new RateLimitedException(notBefore);
    }

    public RateLimitedException limited(HttpHeaders headers) {
        long fallback = Math.min(86_400, FALLBACK_SECONDS * (1L << Math.min(consecutiveLimits, 6)));
        Instant now = clock.instant();
        Instant retryAt = now.plusSeconds(fallback);
        String value = headers.firstValue("Retry-After").orElse("").strip();
        try {
            long seconds = Long.parseLong(value);
            if (seconds < 0) throw new NumberFormatException("Negative Retry-After");
            retryAt = now.plusSeconds(Math.max(1, seconds));
        } catch (NumberFormatException | DateTimeException | ArithmeticException e) {
            try {
                Instant date = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                if (date.isAfter(now)) retryAt = date;
            } catch (DateTimeParseException ignored) {
                // Missing/malformed header: back off for 30m, 1h, 2h, ... up to 24h.
            }
        }
        retryAt = retryAt.plusSeconds(5); // Avoid retrying on the boundary of Spotify's window.
        if (retryAt.isAfter(notBefore)) notBefore = retryAt;
        consecutiveLimits = Math.min(consecutiveLimits + 1, 7);
        save();
        return new RateLimitedException(notBefore);
    }

    public void succeeded() {
        if (consecutiveLimits == 0 && notBefore.equals(Instant.EPOCH)) return;
        notBefore = Instant.EPOCH;
        consecutiveLimits = 0;
        save();
    }

    private void save() {
        if (file == null) return;
        Path temp = null;
        try {
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            temp = Files.createTempFile(parent, ".spotify-limit-", ".tmp");
            mapper.writeValue(temp.toFile(), new SavedLimit(notBefore.toString(), consecutiveLimits));
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot persist Spotify cooldown", e);
        } finally {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) {}
        }
    }
}
