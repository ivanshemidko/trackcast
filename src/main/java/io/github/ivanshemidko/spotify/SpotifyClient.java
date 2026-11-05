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
    private final ObjectMapper mapper = new ObjectMapper();

    public SpotifyClient(SpotifyAuth auth, HttpClient http) {
        this(auth, http, "https://api.spotify.com/v1");
    }

    SpotifyClient(SpotifyAuth auth, HttpClient http, String apiBase) {
        this.apiBase = apiBase;
        this.auth = auth;
        this.http = http;
    }

    public List<Track> getTracks(String playlistId) throws IOException, InterruptedException {
        String snapshot = snapshot(playlistId);
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
        return allTracks;
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
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + auth.getAccessToken())
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

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