package io.github.ivanshemidko.spotify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.http.HttpHeaders;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SpotifyRateLimitTest {
    @TempDir Path dir;
    static class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-05T21:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
    HttpHeaders headers(String value) {
        return HttpHeaders.of(value == null ? Map.of() : Map.of("Retry-After", List.of(value)), (a, b) -> true);
    }
    @Test void respectsLongRetryAfterAcrossRestartAndClearsAfterSuccess() throws Exception {
        var clock = new TestClock();
        Path file = dir.resolve("rate.json");
        var limiter = new SpotifyRateLimit(file, clock);
        var limit = limiter.limited(headers("86400"));
        assertEquals(clock.now.plusSeconds(86405), limit.retryAt());
        var restarted = new SpotifyRateLimit(file, clock);
        assertThrows(SpotifyRateLimit.RateLimitedException.class, restarted::beforeRequest);
        clock.now = clock.now.plusSeconds(86404);
        assertThrows(SpotifyRateLimit.RateLimitedException.class, restarted::beforeRequest);
        clock.now = clock.now.plusSeconds(1);
        assertDoesNotThrow(restarted::beforeRequest);
        restarted.succeeded();
        assertDoesNotThrow(new SpotifyRateLimit(file, clock)::beforeRequest);
    }
    @Test void missingAndMalformedHeadersUseIncreasingPersistedBackoff() throws Exception {
        var clock = new TestClock();
        Path file = dir.resolve("rate.json");
        var limiter = new SpotifyRateLimit(file, clock);
        assertEquals(clock.now.plusSeconds(1805), limiter.limited(headers(null)).retryAt());
        clock.now = clock.now.plusSeconds(1805);
        limiter = new SpotifyRateLimit(file, clock);
        assertEquals(clock.now.plusSeconds(3605), limiter.limited(headers("invalid")).retryAt());
        clock.now = clock.now.plusSeconds(3605);
        limiter = new SpotifyRateLimit(file, clock);
        assertEquals(clock.now.plusSeconds(7205), limiter.limited(headers("-10")).retryAt());
    }
    @Test void fallbackCapsAtOneDayAndResetsAfterSuccessfulPoll() {
        var clock = new TestClock();
        var limiter = new SpotifyRateLimit(null, clock);
        for (int i = 0; i < 10; i++) {
            var limited = limiter.limited(headers(null));
            assertTrue(Duration.between(clock.now, limited.retryAt()).getSeconds() <= 86405);
            clock.now = limited.retryAt();
        }
        limiter.succeeded();
        assertEquals(clock.now.plusSeconds(1805), limiter.limited(headers(null)).retryAt());
    }
    @Test void acceptsHttpDateAndDoesNotShortenExistingCooldown() {
        var clock = new TestClock();
        var limiter = new SpotifyRateLimit(null, clock);
        Instant target = clock.now.plusSeconds(7200);
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(target.atZone(ZoneOffset.UTC));
        assertEquals(target.plusSeconds(5), limiter.limited(headers(date)).retryAt());
        assertEquals(target.plusSeconds(5), limiter.limited(headers("60")).retryAt());
    }
    @Test void cooldownBlocksTokenRefreshBeforeAnyAuthorizationRequest() {
        var clock = new TestClock();
        var limiter = new SpotifyRateLimit(null, clock);
        limiter.limited(headers("86400"));
        var auth = new SpotifyAuth("unused", "unused", "http://127.0.0.1:8888/callback",
                dir.resolve("no-token.json"), limiter);
        assertThrows(SpotifyRateLimit.RateLimitedException.class, auth::getAccessToken);
        assertFalse(Files.exists(dir.resolve("no-token.json")));
    }
    @Test void corruptCooldownIsNotSilentlyBypassed() throws Exception {
        Path file = dir.resolve("rate.json");
        Files.writeString(file, "{broken");
        assertThrows(IllegalStateException.class, () -> new SpotifyRateLimit(file));
    }
}
