package io.github.ivanshemidko.state;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ivanshemidko.spotify.TrackWithMessages;
import io.github.ivanshemidko.spotify.TrackWithCount;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StateManager {

    public record PlaylistDiff(
            Map<String, Integer> added,
            Map<String, List<Long>> removedMessageIds
    ) {}

    private final File stateFile = new File("playlist_state.json");
    private final ObjectMapper mapper = new ObjectMapper();

    private Map<String, TrackWithMessages> currentState = new HashMap<>();
    private boolean baselineEstablished = false;

    public void loadState() {
        if (stateFile.exists()) {
            try {
                currentState = mapper.readValue(stateFile, new TypeReference<Map<String, TrackWithMessages>>() {});
                baselineEstablished = true;
            } catch (IOException e) {
                System.err.println("Failed to read state file, will resync baseline from current playlist: " + e.getMessage());
                baselineEstablished = false;
            }
        }
    }

    public PlaylistDiff updateAndSaveState(Map<String, TrackWithCount> newState) {
        if (!baselineEstablished) {
            Map<String, TrackWithMessages> baseline = new HashMap<>();
            for (var entry : newState.entrySet()) {
                TrackWithCount incoming = entry.getValue();
                List<Long> placeholders = new ArrayList<>();
                for (int i = 0; i < incoming.count(); i++) {
                    placeholders.add(null); // копия есть, но сообщения за неё не слали
                }
                baseline.put(entry.getKey(), new TrackWithMessages(incoming.track(), placeholders));
            }
            this.currentState = baseline;
            baselineEstablished = true;
            saveToDisk();
            return new PlaylistDiff(Map.of(), Map.of());
        }

        Map<String, Integer> added = new HashMap<>();
        Map<String, List<Long>> removedMessageIds = new HashMap<>();
        Map<String, TrackWithMessages> nextState = new HashMap<>();

        for (var entry : newState.entrySet()) {
            String id = entry.getKey();
            TrackWithCount incoming = entry.getValue();
            TrackWithMessages existing = currentState.get(id);

            int oldCount = existing != null ? existing.count() : 0;
            List<Long> messageIds = existing != null ? new ArrayList<>(existing.messageIds()) : new ArrayList<>();

            if (incoming.count() > oldCount) {
                added.put(id, incoming.count() - oldCount);
            } else if (incoming.count() < oldCount) {
                int toRemove = oldCount - incoming.count();
                List<Long> toDelete = new ArrayList<>();
                for (int i = 0; i < toRemove && !messageIds.isEmpty(); i++) {
                    Long removedId = messageIds.remove(messageIds.size() - 1);
                    if (removedId != null) {
                        toDelete.add(removedId); // null-заглушку просто выбрасываем, удалять нечего
                    }
                }
                removedMessageIds.put(id, toDelete);
            }

            nextState.put(id, new TrackWithMessages(incoming.track(), messageIds));
        }

        for (var entry : currentState.entrySet()) {
            String id = entry.getKey();
            if (!newState.containsKey(id)) {
                List<Long> toDelete = new ArrayList<>();
                for (Long messageId : entry.getValue().messageIds()) {
                    if (messageId != null) toDelete.add(messageId);
                }
                removedMessageIds.put(id, toDelete);
            }
        }

        this.currentState = nextState;
        saveToDisk();

        return new PlaylistDiff(added, removedMessageIds);
    }

    public void recordSentMessage(String trackId, long messageId) {
        TrackWithMessages track = currentState.get(trackId);
        if (track == null) return;

        List<Long> messageIds = new ArrayList<>(track.messageIds());
        messageIds.add(messageId);
        currentState.put(trackId, new TrackWithMessages(track.track(), messageIds));
        saveToDisk();
    }

    private void saveToDisk() {
        try {
            mapper.writeValue(stateFile, currentState);
        } catch (IOException e) {
            System.err.println("Failed to save the state: " + e.getMessage());
        }
    }
}
