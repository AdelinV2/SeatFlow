package com.seatflow.ai.proposal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Secure proposal store tests (TASK-P15-005 sections 5, 13; mandatory 5, 6, 16, 18, 19).
 */
class ProposalStoreTest {

    private MutableClock clock;
    private ProposalStore store;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-09-07T10:00:00Z"));
        store = new ProposalStore(
                new ReservationProposalProperties(
                        Duration.ofMinutes(5), 500, Duration.ofMinutes(1)),
                clock);
    }

    private ReservationProposal create(UUID conversationId, String owner, UUID sessionId, List<UUID> seats) {
        return store.create(conversationId, owner, UUID.randomUUID(), sessionId, seats,
                seats.stream()
                        .map(id -> new ReservationProposal.SeatDisplay(
                                id, "Row A Seat 1", "Orchestra", "A", 1))
                        .toList(),
                List.of(), null, null, null, 1000L * seats.size(), "EUR");
    }

    @Test
    @DisplayName("16: proposal TTL defaults to 5m and max to 500 with validation bounds")
    void defaultsAndBounds() {
        var props = new ReservationProposalProperties(
                Duration.ofMinutes(5), 500, Duration.ofMinutes(1));
        assertThat(props.ttl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(props.maxActiveProposals()).isEqualTo(500);
        assertThatThrownBy(() -> new ReservationProposalProperties(
                Duration.ofSeconds(30), 500, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReservationProposalProperties(
                Duration.ofMinutes(31), 500, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReservationProposalProperties(
                Duration.ofMinutes(5), 0, Duration.ofMinutes(1)))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("5: another user cannot read or supersede the proposal (anti-enumeration)")
    void crossOwnerDenied() {
        UUID conversationId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        ReservationProposal created =
                create(conversationId, "owner-1", sessionId, List.of(UUID.randomUUID()));
        assertThat(store.getForOwner(created.proposalId(), "owner-2")).isNull();
        assertThat(store.peekForOwner(created.proposalId(), "owner-2")).isNull();
        // Cross-owner supersede is denied: the active proposal survives.
        store.supersedeForConversation(conversationId, "owner-2");
        assertThat(store.getForOwner(created.proposalId(), "owner-1")).isNotNull();
    }

    @Test
    @DisplayName("6: expired proposal is invalid and lazily marked EXPIRED")
    void expiredProposalInvalid() {
        UUID conversationId = UUID.randomUUID();
        ReservationProposal created =
                create(conversationId, "owner-1", UUID.randomUUID(), List.of(UUID.randomUUID()));
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        assertThat(store.getForOwner(created.proposalId(), "owner-1")).isNull();
        ReservationProposal peeked = store.peekForOwner(created.proposalId(), "owner-1");
        assertThat(peeked).isNotNull();
        assertThat(peeked.status()).isEqualTo(ProposalStatus.EXPIRED);
    }

    @Test
    @DisplayName("one active proposal per conversation: new supersedes old")
    void supersedeOnCreate() {
        UUID conversationId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        ReservationProposal first =
                create(conversationId, "owner-1", sessionId, List.of(UUID.randomUUID()));
        ReservationProposal second =
                create(conversationId, "owner-1", sessionId, List.of(UUID.randomUUID()));
        assertThat(store.getForOwner(first.proposalId(), "owner-1")).isNull();
        assertThat(store.peekForOwner(first.proposalId(), "owner-1").status())
                .isEqualTo(ProposalStatus.SUPERSEDED);
        assertThat(store.getForOwner(second.proposalId(), "owner-1")).isNotNull();
        assertThat(store.activeIdForConversation(conversationId)).isEqualTo(second.proposalId());
    }

    @Test
    @DisplayName("capacity: never evicts another user's ACTIVE proposal; rejects instead")
    void capacityNeverEvictsActive() {
        var small = new ProposalStore(
                new ReservationProposalProperties(
                        Duration.ofMinutes(5), 2, Duration.ofMinutes(1)),
                clock);
        small.create(UUID.randomUUID(), "owner-1", null, UUID.randomUUID(),
                List.of(UUID.randomUUID()), List.of(), List.of(), null, null, null, 100L, "EUR");
        small.create(UUID.randomUUID(), "owner-2", null, UUID.randomUUID(),
                List.of(UUID.randomUUID()), List.of(), List.of(), null, null, null, 100L, "EUR");
        assertThatThrownBy(() -> small.create(UUID.randomUUID(), "owner-3", null, UUID.randomUUID(),
                List.of(UUID.randomUUID()), List.of(), List.of(), null, null, null, 100L, "EUR"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("18: process restart invalidates proposals (memory-only store)")
    void restartInvalidates() {
        UUID conversationId = UUID.randomUUID();
        ReservationProposal created =
                create(conversationId, "owner-1", UUID.randomUUID(), List.of(UUID.randomUUID()));
        // A fresh store (new process) knows nothing about the old ID.
        var restarted = new ProposalStore(
                new ReservationProposalProperties(
                        Duration.ofMinutes(5), 500, Duration.ofMinutes(1)),
                clock);
        assertThat(restarted.getForOwner(created.proposalId(), "owner-1")).isNull();
        assertThat(restarted.peekForOwner(created.proposalId(), "owner-1")).isNull();
    }

    @Test
    @DisplayName("19: proposal storage carries no secrets")
    void noSecretsInStorage() {
        UUID seatId = UUID.randomUUID();
        ReservationProposal created =
                create(UUID.randomUUID(), "owner-1", UUID.randomUUID(), List.of(seatId));
        String serialized = created.toString();
        assertThat(serialized).doesNotContain("Bearer", "eyJ", "GROQ", "sk-", "card", "4242");
        assertThat(created.serverIdempotencyKey()).isNotBlank();
        assertThat(created.reservationId()).isNull();
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant now) {
            this.now = new AtomicReference<>(now);
        }

        void advance(Duration duration) {
            now.updateAndGet(current -> current.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
