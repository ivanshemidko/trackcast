package io.github.ivanshemidko.spotify;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class SpotifyCachingTest {
    @TempDir Path dir;
    record Reply(int status, String body, String retryAfter) {
        Reply(String body) { this(200, body, null); }
    }
    static final String SNAPSHOT = "{\"snapshot_id\":\"s1\"}";
    static final String PAGE = "{\"items\":[{\"item\":{\"id\":\"a\",\"name\":\"Song\","
            + "\"artists\":[{\"name\":\"Artist\"}]}}],\"total\":1,\"next\":null}";
    class MockSpotify implements AutoCloseable {
        final HttpServer server;
        final List<String> requests = Collections.synchronizedList(new ArrayList<>());
        volatile Function<String, Reply> handler = url -> new Reply(url.contains("/items?") ? PAGE : SNAPSHOT);
        MockSpotify() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", ex -> {
                String url = ex.getRequestURI().toString();
                requests.add(url);
                Reply reply = handler.apply(url);
                if (reply.retryAfter() != null) ex.getResponseHeaders().set("Retry-After", reply.retryAfter());
                byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(reply.status(), body.length);
                try (var out = ex.getResponseBody()) { out.write(body); }
            });
            server.start();
        }
        SpotifyClient client() { return client(new SpotifyRateLimit()); }
        SpotifyClient client(SpotifyRateLimit limiter) {
            var auth = new SpotifyAuth("", "", "http://127.0.0.1:8888/callback", dir.resolve("token.json"), limiter) {
                @Override public String getAccessToken() { return "test"; }
            };
            return new SpotifyClient(auth, HttpClient.newHttpClient(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), dir.resolve("cache.json"));
        }
        public void close() { server.stop(0); }
    }
    @Test void unchangedPlaylistUsesExactlyOneRequestAndReturnsTracks() throws Exception {
        try (var api = new MockSpotify()) {
            var client = api.client();
            var tracks = client.getTracks("p");
            assertEquals(3, api.requests.size());
            assertEquals(tracks, client.getTracks("p"));
            assertEquals(4, api.requests.size());
            assertFalse(api.requests.get(3).contains("/items?"));
        }
    }
    @Test void cacheSurvivesRestartButDoesNotCrossPlaylistIds() throws Exception {
        try (var api = new MockSpotify()) {
            api.client().getTracks("p");
            assertEquals(1, api.client().getTracks("p").size());
            assertEquals(4, api.requests.size());
            api.client().getTracks("other");
            assertEquals(7, api.requests.size());
        }
    }
    @Test void changedVersionRefreshesCache() throws Exception {
        try (var api = new MockSpotify()) {
            var client = api.client();
            client.getTracks("p");
            api.handler = url -> new Reply(url.contains("/items?") ? PAGE.replace("Song", "Changed")
                    : SNAPSHOT.replace("s1", "s2"));
            assertEquals("Changed", client.getTracks("p").get(0).name());
            assertEquals(6, api.requests.size());
            assertEquals("Changed", api.client().getTracks("p").get(0).name());
            assertEquals(7, api.requests.size());
        }
    }
    @Test void failedReadDoesNotPoisonExistingCache() throws Exception {
        try (var api = new MockSpotify()) {
            var client = api.client();
            var original = client.getTracks("p");
            var versions = new AtomicInteger(1);
            api.handler = url -> new Reply(url.contains("/items?") ? PAGE
                    : "{\"snapshot_id\":\"s" + versions.incrementAndGet() + "\"}");
            assertThrows(IOException.class, () -> client.getTracks("p"));
            api.handler = url -> new Reply(url.contains("/items?") ? PAGE : SNAPSHOT);
            assertEquals(original, api.client().getTracks("p"));
            assertEquals(7, api.requests.size());
        }
    }
    @Test void corruptReadCacheIsSafelyRefetched() throws Exception {
        Files.writeString(dir.resolve("cache.json"), "{broken");
        try (var api = new MockSpotify()) {
            assertEquals(1, api.client().getTracks("p").size());
            assertEquals(3, api.requests.size());
            assertEquals(1, api.client().getTracks("p").size());
            assertEquals(4, api.requests.size());
        }
    }
    @Test void rateLimitStopsAllRequestsEvenAfterRestart() throws Exception {
        Path rateFile = dir.resolve("rate.json");
        try (var api = new MockSpotify()) {
            api.handler = url -> new Reply(429, "Too many requests", "86400");
            var client = api.client(new SpotifyRateLimit(rateFile));
            assertThrows(SpotifyRateLimit.RateLimitedException.class, () -> client.getTracks("p"));
            assertEquals(1, api.requests.size());
            assertThrows(SpotifyRateLimit.RateLimitedException.class, () -> client.getTracks("p"));
            var restarted = api.client(new SpotifyRateLimit(rateFile));
            assertThrows(SpotifyRateLimit.RateLimitedException.class, () -> restarted.getTracks("p"));
            assertEquals(1, api.requests.size());
            assertFalse(Files.exists(dir.resolve("cache.json")));
        }
    }
    @Test void rateLimitDuringPaginationDoesNotReturnStalePlaylist() throws Exception {
        try (var api = new MockSpotify()) {
            var client = api.client();
            client.getTracks("p");
            api.handler = url -> url.contains("/items?") ? new Reply(429, "limited", "7200")
                    : new Reply(SNAPSHOT.replace("s1", "s2"));
            assertThrows(SpotifyRateLimit.RateLimitedException.class, () -> client.getTracks("p"));
            assertEquals(5, api.requests.size());
            assertThrows(SpotifyRateLimit.RateLimitedException.class, () -> client.getTracks("p"));
            assertEquals(5, api.requests.size());
        }
    }
}
