package com.seatflow.realtime.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P12-008 scenario J (realtime leg): no request or broadcast may silently
 * infer or choose a session through a legacy event-scoped route.
 *
 * <p>Proves at the source level that no production code under
 * {@code src/main/java} constructs or references the removed event-scoped
 * seat destination: the canonical {@code /topic/sessions/{id}/seats}
 * destination (see {@code SeatStatusBroadcaster}) is the sole realtime
 * channel. Behavioral proof lives in {@code SeatStatusBroadcasterTest}
 * (session-A never leaks to session-B; the legacy destination receives
 * nothing) and in the consumer tests (session-less payloads are discarded,
 * never inferred).
 */
class SessionTopicExclusivityContractTest {

    @Test
    @DisplayName("No production source references the removed event-scoped seat destination")
    void noProductionSourceReferencesLegacyEventSeatDestination() throws Exception {
        Path main = Path.of("src/main/java");
        assertThat(Files.isDirectory(main)).isTrue();

        List<String> offenders;
        try (Stream<Path> files = Files.walk(main)) {
            offenders = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> referencesLegacyDestination(p))
                    .map(p -> main.relativize(p).toString())
                    .sorted()
                    .toList();
        }
        assertThat(offenders)
                .as("production sources referencing the removed event-scoped seat destination")
                .isEmpty();
    }

    private static boolean referencesLegacyDestination(Path file) {
        try {
            String code = Files.readString(file);
            // Strip line and block comments so prose history notes cannot trip
            // the gate; only real destination construction counts.
            code = code.replaceAll("(?s)/\\*.*?\\*/", "");
            code = code.replaceAll("(?m)//.*$", "");
            return code.contains("/topic/events/");
        } catch (Exception ex) {
            throw new IllegalStateException("Could not scan " + file, ex);
        }
    }
}
