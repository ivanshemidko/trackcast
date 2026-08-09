package io.github.ivanshemidko.spotify;

import java.util.List;

public record TrackWithMessages(Track track, List<Long> messageIds) {
    public int count() {
        return messageIds.size();
    }
}