package io.github.ivanshemidko.spotify;

import java.util.List;

public record Track(String id, String name, List<String> artists) {
    private static final String BASE_URL = "https://open.spotify.com/track/";

    public String spotifyUrl() {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("Track ID cannot be null or empty");
        }
        return BASE_URL + id.trim();
    }
}

