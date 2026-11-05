package io.github.ivanshemidko.spotify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.time.Duration;

public class SpotifyClient {

    private static final int PAGE_SIZE = 50;
    private static final String FIELDS =
            "items(is_local,item(id,name,type,is_local,artists(name))),next,total";

    private final String apiBase;
    private final SpotifyAuth auth;
    private final HttpClient http;
    private final SpotifyRateLimit rateLimit;
    private final Path cacheFile;
    private final ObjectMapper mapper = new ObjectMapper();
    private CachedPlaylist cached;
    private int requestCount;

    record CachedPlaylist(String playlistId, String snapshot, List<Track> tracks) {}

    public SpotifyClient(SpotifyAuth auth, HttpClient http) {
        this(auth, http, "https://api.spotify.com/v1", null);
    }

    public SpotifyClient(SpotifyAuth auth, HttpClient http, Path cacheFile) {
        this(auth, http, "https://api.spotify.com/v1", cacheFile);
    }

    SpotifyClient(SpotifyAuth auth, HttpClient http, String apiBase) {
        this(auth, http, apiBase, null);
    }

    SpotifyClient(SpotifyAuth auth, HttpClient http, String apiBase, Path cacheFile) {
        this.apiBase = apiBase;
        this.auth = auth;
        this.http = http;
        this.cacheFile = cacheFile;
        this.rateLimit = auth == null ? new SpotifyRateLimit() : auth.rateLimit();
        if (cacheFile != null && Files.exists(cacheFile)) {
            try {
                CachedPlaylist saved = mapper.readValue(cacheFile.toFile(), CachedPlaylist.class);
                if (saved == null || saved.playlistId() == null || saved.snapshot() == null
                        || saved.snapshot().isBlank() || saved.tracks() == null
                        || saved.tracks().stream().anyMatch(t -> t == null || t.id() == null
                        || t.id().isBlank() || t.name() == null || t.artists() == null)) {
                    throw new IOException("Invalid playlist cache");
                }
                cached = new CachedPlaylist(saved.playlistId(), saved.snapshot(), List.copyOf(saved.tracks()));
            } catch (IOException e) {
                // This is only a read cache. The authoritative Telegram message state is separate.
                System.err.println("Spotify cache unreadable; will download playlist again: " + e.getMessage());
            }
        }
    }

    public List<Track> getTracks(String playlistId) throws IOException, InterruptedException {
        requestCount = 0;
        String snapshot = snapshot(playlistId);
        if (cached != null && playlistId.equals(cached.playlistId()) && snapshot.equals(cached.snapshot())) {
            rateLimit.succeeded();
            System.out.println("Spotify playlist unchanged; reused cache (" + requestCount + " API request)");
            return cached.tracks();
        }
        Integer expectedTotal = null;
        List<Track> allTracks = new ArrayList<>();
        int offset = 0;

        while (true) {

            JsonNode page = fetchPage(playlistId, offset);
            if (!page.path("items").isArray() || !page.has("next") || !page.path("total").canConvertToInt()) {
                throw new IOException("Incomplete Spotify playlist response; state was not changed");
            }
            int total = page.path("total").asInt();
            if (expectedTotal != null && expectedTotal != total) throw new IOException("Playlist changed during pagination");
            expectedTotal = total;
            for (JsonNode item : page.get("items")) {
                JsonNode trackNode = item.get("item");
                if (trackNode == null) trackNode = item.get("track");
                if (item.path("is_local").asBoolean() || trackNode == null || trackNode.isNull()
                        || trackNode.path("is_local").asBoolean() || trackNode.path("id").asText().isBlank()
                        || "episode".equals(trackNode.path("type").asText())) continue;
                allTracks.add(parseTrack(trackNode));
            }

            if (page.get("next").isNull()) {
                if (offset + page.get("items").size() != total) throw new IOException("Truncated playlist response");
                break;
            }

            if (page.get("items").isEmpty()) throw new IOException("Empty non-final Spotify page");
            offset += page.get("items").size();
        }

        if (!snapshot.equals(snapshot(playlistId))) throw new IOException("Playlist changed while reading; retry next poll");
        CachedPlaylist next = new CachedPlaylist(playlistId, snapshot, List.copyOf(allTracks));
        saveCache(next);
        cached = next;
        rateLimit.succeeded();
        System.out.println("Spotify playlist loaded: " + allTracks.size() + " entries (" + requestCount + " API requests)");
        return next.tracks();
    }

    private void saveCache(CachedPlaylist next) throws IOException {
        if (cacheFile == null) return;
        Path parent = cacheFile.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, ".spotify-cache-", ".tmp");
        try {
            mapper.writeValue(temp.toFile(), next);
            Files.move(temp, cacheFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private JsonNode fetchPage(String playlistId, int offset) throws IOException, InterruptedException {
        String url = apiBase + "/playlists/" + playlistId + "/items"
                + "?limit=" + PAGE_SIZE
                + "&offset=" + offset
                + "&fields=" + URLEncoder.encode(FIELDS, StandardCharsets.UTF_8);

        return fetch(url);
    }

    private String snapshot(String playlistId) throws IOException, InterruptedException {
        String value = fetch(apiBase + "/playlists/" + playlistId + "?fields=snapshot_id")
                .path("snapshot_id").asText();
        if (value.isBlank()) throw new IOException("Missing playlist snapshot");
        return value;
    }

    private JsonNode fetch(String url) throws IOException, InterruptedException {
        rateLimit.beforeRequest();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + auth.getAccessToken())
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        requestCount++;

        if (response.statusCode() == 429) throw rateLimit.limited(response.headers());
        if (response.statusCode() != 200) {
            if (response.statusCode() == 401) auth.invalidateAccessToken();
            throw new IOException("Spotify API error: " + response.statusCode() + " " + response.body());
        }

        return mapper.readTree(response.body());
    }

    private Track parseTrack(JsonNode trackNode) {
        String id = trackNode.get("id").asText();

        String name = trackNode.path("name").asText();

        List<String> artists = new ArrayList<>();
        for (JsonNode artist : trackNode.path("artists")) {
            artists.add(artist.path("name").asText());
        }

        return new Track(id, name, artists);
    }
}
