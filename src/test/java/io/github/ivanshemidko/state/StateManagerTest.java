package io.github.ivanshemidko.state;

import io.github.ivanshemidko.spotify.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.UncheckedIOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StateManagerTest {
    @TempDir Path dir;
    Track track = new Track("a", "Title", List.of("Artist"));
    Map<String, TrackWithCount> count(int n) {
        return n == 0 ? Map.of() : Map.of("a", new TrackWithCount(track, n));
    }
    StateManager reload() {
        var state = new StateManager(dir.resolve("state.json"));
        state.loadState();
        return state;
    }
    @Test void baselineIsSilentAndPersistedIncludingDuplicates() {
        var state = reload();
        assertTrue(state.updateAndSaveState(count(2)).added().isEmpty());
        assertTrue(reload().updateAndSaveState(count(2)).added().isEmpty());
        assertTrue(state.updateAndSaveState(count(1)).removedMessageIds().isEmpty());
        assertTrue(state.updateAndSaveState(count(0)).removedMessageIds().isEmpty());
        assertEquals(1, state.updateAndSaveState(count(1)).added().get("a"));
    }
    @Test void partialSendRetriesOnlyMissingCopiesAfterRestart() {
        var state = reload();
        state.updateAndSaveState(count(0));
        assertEquals(2, state.updateAndSaveState(count(2)).added().get("a"));
        state.recordSentMessage("a", track, 10);
        state = reload();
        assertEquals(1, state.updateAndSaveState(count(2)).added().get("a"));
        state.recordSentMessage("a", track, 11);
        assertTrue(reload().updateAndSaveState(count(2)).added().isEmpty());
    }
    @Test void failedDeletionSurvivesRestartAndPartialSuccess() {
        var state = reload();
        state.updateAndSaveState(count(0));
        state.recordSentMessage("a", track, 10);
        state.recordSentMessage("a", track, 11);
        assertEquals(List.of(11L, 10L), state.updateAndSaveState(count(0)).removedMessageIds().get("a"));
        state.recordDeletedMessage("a", 11);
        state = reload();
        assertEquals(List.of(10L), state.updateAndSaveState(count(0)).removedMessageIds().get("a"));
        state.recordDeletedMessage("a", 10);
        assertTrue(reload().updateAndSaveState(count(0)).removedMessageIds().isEmpty());
        assertEquals(1, state.updateAndSaveState(count(1)).added().get("a"));
    }
    @Test void readditionBeforeFailedDeletionDoesNotCreateDuplicatePost() {
        var state = reload();
        state.updateAndSaveState(count(0));
        state.recordSentMessage("a", track, 10);
        state.updateAndSaveState(count(0));
        var diff = reload().updateAndSaveState(count(1));
        assertTrue(diff.added().isEmpty());
        assertTrue(diff.removedMessageIds().isEmpty());
    }
    @Test void duplicateRemovalKeepsBaselineAndDeletesLatestPost() {
        var state = reload();
        state.updateAndSaveState(count(1));
        state.recordSentMessage("a", track, 10);
        assertEquals(List.of(10L), state.updateAndSaveState(count(1)).removedMessageIds().get("a"));
        state.recordDeletedMessage("a", 10);
        assertTrue(reload().updateAndSaveState(count(1)).added().isEmpty());
    }
    @Test void corruptStateFailsClosed() throws Exception {
        Files.writeString(dir.resolve("state.json"), "{broken");
        assertThrows(UncheckedIOException.class, this::reload);
        assertEquals("{broken", Files.readString(dir.resolve("state.json")));
    }
    @Test void writeFailureIsFatal() throws Exception {
        Path file = dir.resolve("not-directory");
        Files.writeString(file, "x");
        var state = new StateManager(file.resolve("state.json"));
        assertThrows(UncheckedIOException.class, () -> state.updateAndSaveState(count(0)));
    }
}
