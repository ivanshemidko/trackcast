package io.github.ivanshemidko.spotify;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class SpotifyClientTest {
    void withServer(Function<String, String> response, java.util.function.Consumer<SpotifyClient> test) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] bytes = response.apply(ex.getRequestURI().toString()).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            try (var out = ex.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            var auth = new SpotifyAuth("", "", "http://127.0.0.1:8888/callback", Path.of("unused")) {
                @Override public String getAccessToken() { return "test"; }
            };
            test.accept(new SpotifyClient(auth, HttpClient.newHttpClient(), "http://127.0.0.1:" + server.getAddress().getPort()));
        } finally { server.stop(0); }
    }
    String snapshot = "{\"snapshot_id\":\"s1\"}";
    String track = "{\"item\":{\"id\":\"a\",\"name\":\"Song\",\"artists\":[{\"name\":\"Artist\"}]}}";
    @Test void skipsNullLocalAndEpisodesWithoutLosingValidDuplicates() throws Exception {
        withServer(url -> url.contains("/items?") ? "{\"items\":[" + track + "," + track
                + ",{\"item\":null},{\"is_local\":true},{\"item\":{\"id\":\"e\",\"type\":\"episode\"}}],\"total\":5,\"next\":null}" : snapshot,
                client -> {
                    try { assertEquals(2, client.getTracks("p").size()); }
                    catch (Exception e) { throw new RuntimeException(e); }
                });
    }
    @Test void rejectsMalformedAndTruncatedResponses() throws Exception {
        for (String page : new String[]{"{}", "{\"items\":[],\"total\":2,\"next\":null}",
                "{\"items\":[],\"total\":2,\"next\":\"next\"}"}) {
            withServer(url -> url.contains("/items?") ? page : snapshot,
                    client -> assertThrows(IOException.class, () -> client.getTracks("p")));
        }
    }
    @Test void rejectsPlaylistChangedDuringPagination() throws Exception {
        var snapshots = new AtomicInteger();
        withServer(url -> url.contains("/items?") ? "{\"items\":[],\"total\":0,\"next\":null}"
                : "{\"snapshot_id\":\"" + snapshots.incrementAndGet() + "\"}",
                client -> assertThrows(IOException.class, () -> client.getTracks("p")));
    }
    @Test void followsAllPages() throws Exception {
        withServer(url -> !url.contains("/items?") ? snapshot : url.contains("offset=0&")
                ? "{\"items\":[" + track + "],\"total\":2,\"next\":\"more\"}"
                : "{\"items\":[" + track + "],\"total\":2,\"next\":null}", client -> {
                    try { assertEquals(2, client.getTracks("p").size()); }
                    catch (Exception e) { throw new RuntimeException(e); }
                });
    }
}
