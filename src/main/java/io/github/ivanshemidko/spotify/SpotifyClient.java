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

public class SpotifyClient {

    private static final int PAGE_SIZE = 50;
    private static final String FIELDS =
            "items(added_at,item(id,name,artists(name),external_urls)),next";

    private final SpotifyAuth auth;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public SpotifyClient(SpotifyAuth auth, HttpClient http) {
        this.auth = auth;
        this.http = http;
    }

    public List<Track> getTracks(String playlistId) throws IOException, InterruptedException {
        List<Track> allTracks = new ArrayList<>();
        int offset = 0;

        while (true) {

            JsonNode page = fetchPage(playlistId, offset);
            for (JsonNode item : page.get("items")) {
                JsonNode trackNode = item.get("item");
                allTracks.add(parseTrack(trackNode));
            }

            if (page.get("next").isNull()) {
                break;
            }

            offset += PAGE_SIZE;
        }

        return allTracks;
    }

    private JsonNode fetchPage(String playlistId, int offset) throws IOException, InterruptedException {
        String url = "https://api.spotify.com/v1/playlists/" + playlistId + "/items"
                + "?limit=" + PAGE_SIZE
                + "&offset=" + offset
                + "&fields=" + URLEncoder.encode(FIELDS, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + auth.getAccessToken())
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            System.out.println("Spotify API returned HTTP error code " + response.toString()    );
            throw new IOException("Spotify API error: " + response.statusCode() + " " + response.body());
        }

        return mapper.readTree(response.body());
    }

    private Track parseTrack(JsonNode trackNode) {
        String id = trackNode.get("id").asText();

        String name = trackNode.get("name").asText();
        System.out.println(name);

        List<String> artists = new ArrayList<>();
        for (JsonNode artist : trackNode.get("artists")) {
            artists.add(artist.get("name").asText());
        }

        return new Track(id, name, artists);
    }
}