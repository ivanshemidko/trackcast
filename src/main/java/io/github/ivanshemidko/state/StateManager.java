package io.github.ivanshemidko.state;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ivanshemidko.spotify.TrackWithMessages;
import io.github.ivanshemidko.spotify.TrackWithCount;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;

public class StateManager {
    public record PlaylistDiff(Map<String, Integer> added, Map<String, List<Long>> removedMessageIds) {}
    private final Path stateFile;
    private final ObjectMapper mapper = new ObjectMapper();
    private Map<String, TrackWithMessages> currentState = new LinkedHashMap<>();
    private boolean baselineEstablished;

    public StateManager() { this(Path.of("playlist_state.json")); }
    public StateManager(Path stateFile) { this.stateFile = stateFile; }

    public void loadState() {
        if (!Files.exists(stateFile)) return;
        try {
            currentState = mapper.readValue(stateFile.toFile(), new TypeReference<LinkedHashMap<String, TrackWithMessages>>() {});
            if (currentState == null || currentState.values().stream().anyMatch(t ->
                    t == null || t.track() == null || t.messageIds() == null)) {
                throw new IOException("Invalid playlist state");
            }
            baselineEstablished = true;
        } catch (IOException e) {
            // Never silently discard message IDs and establish a new baseline.
            throw new UncheckedIOException("Cannot read playlist state; restore the file before restarting", e);
        }
    }

    public PlaylistDiff updateAndSaveState(Map<String, TrackWithCount> newState) {
        if (!baselineEstablished) {
            for (var entry : newState.entrySet()) {
                currentState.put(entry.getKey(), new TrackWithMessages(entry.getValue().track(),
                        new ArrayList<>(Collections.nCopies(entry.getValue().count(), null))));
            }
            saveToDisk();
            baselineEstablished = true;
            return new PlaylistDiff(Map.of(), Map.of());
        }
        Map<String, Integer> added = new LinkedHashMap<>();
        Map<String, List<Long>> removed = new LinkedHashMap<>();
        // Planning does not acknowledge external operations. Failed sends/deletes are retried next poll.
        for (var entry : newState.entrySet()) {
            var existing = currentState.get(entry.getKey());
            int oldCount = existing == null ? 0 : existing.count();
            if (entry.getValue().count() > oldCount) {
                added.put(entry.getKey(), entry.getValue().count() - oldCount);
            }
        }
        for (var entry : new ArrayList<>(currentState.entrySet())) {
            var incoming = newState.get(entry.getKey());
            int targetCount = incoming == null ? 0 : incoming.count();
            List<Long> ids = new ArrayList<>(entry.getValue().messageIds());
            List<Long> toDelete = new ArrayList<>();
            for (int i = ids.size() - 1; i >= targetCount; i--) {
                Long id = ids.get(i);
                if (id == null) ids.remove(i);
                else toDelete.add(id);
            }
            if (!toDelete.isEmpty()) removed.put(entry.getKey(), toDelete);
            if (ids.isEmpty()) currentState.remove(entry.getKey());
            else currentState.put(entry.getKey(), new TrackWithMessages(entry.getValue().track(), ids));
        }
        saveToDisk();
        return new PlaylistDiff(added, removed);
    }

    public void recordSentMessage(String trackId, io.github.ivanshemidko.spotify.Track track, long messageId) {
        var existing = currentState.get(trackId);
        List<Long> ids = existing == null ? new ArrayList<>() : new ArrayList<>(existing.messageIds());
        ids.add(messageId);
        currentState.put(trackId, new TrackWithMessages(track, ids));
        saveToDisk();
    }

    public void recordDeletedMessage(String trackId, long messageId) {
        var existing = currentState.get(trackId);
        if (existing == null) return;
        List<Long> ids = new ArrayList<>(existing.messageIds());
        ids.remove(Long.valueOf(messageId));
        if (ids.isEmpty()) currentState.remove(trackId);
        else currentState.put(trackId, new TrackWithMessages(existing.track(), ids));
        saveToDisk();
    }

    private void saveToDisk() {
        Path temp = null;
        try {
            Path parent = stateFile.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            temp = Files.createTempFile(parent, ".playlist-", ".tmp");
            mapper.writeValue(temp.toFile(), currentState);
            Files.move(temp, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Continuing after a failed acknowledgement could duplicate channel posts.
            throw new UncheckedIOException("Cannot persist playlist state", e);
        } finally {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) {}
        }
    }
}
