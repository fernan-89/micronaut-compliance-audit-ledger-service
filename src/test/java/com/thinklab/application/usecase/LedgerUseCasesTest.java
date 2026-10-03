package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.AppendLedgerEntryRequest;
import com.thinklab.application.mapper.LedgerEntryMapper;
import com.thinklab.domain.exception.ChainConflictException;
import com.thinklab.domain.exception.LedgerEntryNotFoundException;
import com.thinklab.domain.model.LedgerEntry;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.LedgerEntryRepository;
import com.thinklab.domain.repository.LedgerEntryRepository.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LedgerUseCasesTest {

    private static final String EXECUTOR = "gateway";

    @Mock private LedgerEntryRepository repository;
    @Mock private HashServicePort hashServicePort;

    private UUID organisationId;
    private UUID sovereignId;

    @BeforeEach
    void setUp() {
        organisationId = UUID.randomUUID();
        sovereignId = UUID.randomUUID();
    }

    private AppendLedgerEntryRequest request() {
        return new AppendLedgerEntryRequest("platform-gateway", "alice", "PUT control/deploy", "it-asset-registry", "asset-1", "status=204", null);
    }

    private LedgerEntry head(long sequence) {
        return LedgerEntry.createNew(UUID.randomUUID(), organisationId, sequence, Instant.now().minusSeconds(60), "gw", "bob", "A", "t", null, null, "gw",
                LedgerEntry.GENESIS_HASH);
    }

    private AppendLedgerEntryUseCase append() {
        return new AppendLedgerEntryUseCase(hashServicePort, repository);
    }

    private void stubAppendEcho() {
        when(repository.append(any(LedgerEntry.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    }

    // ------------------------------------------------------------ append

    @Test
    @DisplayName("Append: the first entry of a tenant sits at position 1 on the genesis hash")
    void appendFirstEntry() {
        when(hashServicePort.generateSovereignId("ledger-entry-creation")).thenReturn(Mono.just(sovereignId));
        when(repository.findLatest(organisationId)).thenReturn(Mono.empty());
        stubAppendEcho();

        StepVerifier.create(append().execute(organisationId, request(), EXECUTOR))
                .assertNext(response -> {
                    assertEquals(sovereignId, response.id());
                    assertEquals(1, response.sequence());
                    assertEquals(LedgerEntry.GENESIS_HASH, response.previousHash());
                    assertEquals(EXECUTOR, response.recordedBy());
                    assertEquals("PUT control/deploy", response.action());
                    assertEquals(64, response.hash().length());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Append: a later entry links to the current head: next position, previous hash = head hash")
    void appendLinksToHead() {
        LedgerEntry head = head(7);
        when(hashServicePort.generateSovereignId(any())).thenReturn(Mono.just(sovereignId));
        when(repository.findLatest(organisationId)).thenReturn(Mono.just(head));
        stubAppendEcho();

        StepVerifier.create(append().execute(organisationId, request(), EXECUTOR))
                .assertNext(response -> {
                    assertEquals(8, response.sequence());
                    assertEquals(head.getHash(), response.previousHash());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Append: losing the race re-reads the head and retries, using one sovereign id throughout")
    void appendRetriesAfterLosingTheRace() {
        LedgerEntry first = head(1);
        LedgerEntry second = head(2);
        when(hashServicePort.generateSovereignId(any())).thenReturn(Mono.just(sovereignId));
        when(repository.findLatest(organisationId)).thenReturn(Mono.just(first)).thenReturn(Mono.just(second));
        AtomicInteger attempts = new AtomicInteger();
        when(repository.append(any(LedgerEntry.class))).thenAnswer(inv -> attempts.incrementAndGet() == 1
                ? Mono.error(new ChainConflictException("taken")) : Mono.just(inv.getArgument(0)));

        StepVerifier.create(append().execute(organisationId, request(), EXECUTOR))
                .assertNext(response -> assertEquals(3, response.sequence()))
                .verifyComplete();

        verify(hashServicePort, times(1)).generateSovereignId(any());
        verify(repository, times(2)).findLatest(organisationId);
    }

    @Test
    @DisplayName("Append: persistent contention gives up after the bounded attempts with the conflict itself (409)")
    void appendGivesUpUnderContention() {
        when(hashServicePort.generateSovereignId(any())).thenReturn(Mono.just(sovereignId));
        when(repository.findLatest(organisationId)).thenReturn(Mono.empty());
        when(repository.append(any(LedgerEntry.class))).thenReturn(Mono.error(new ChainConflictException("taken")));

        StepVerifier.create(append().execute(organisationId, request(), EXECUTOR))
                .expectError(ChainConflictException.class)
                .verify();

        verify(repository, times(AppendLedgerEntryUseCase.MAX_ATTEMPTS)).append(any(LedgerEntry.class));
    }

    @Test
    @DisplayName("Append: other failures are not retried")
    void appendDoesNotRetryOtherErrors() {
        when(hashServicePort.generateSovereignId(any())).thenReturn(Mono.just(sovereignId));
        when(repository.findLatest(organisationId)).thenReturn(Mono.empty());
        when(repository.append(any(LedgerEntry.class))).thenReturn(Mono.error(new IllegalStateException("mongo down")));

        StepVerifier.create(append().execute(organisationId, request(), EXECUTOR)).expectError(IllegalStateException.class).verify();

        verify(repository, times(1)).append(any(LedgerEntry.class));
    }

    @Test
    @DisplayName("Append: a hash-service failure is propagated and nothing is written")
    void appendHashFailure() {
        when(hashServicePort.generateSovereignId(any())).thenReturn(Mono.error(new IllegalStateException("hash down")));

        StepVerifier.create(append().execute(organisationId, request(), EXECUTOR)).expectError(IllegalStateException.class).verify();

        verify(repository, never()).append(any());
    }

    @Test
    @DisplayName("Append: an invalid or future-dated request fails fast (400) before any I/O")
    void appendFailsFast() {
        AppendLedgerEntryUseCase useCase = append();
        AppendLedgerEntryRequest blankAction = new AppendLedgerEntryRequest("gw", "alice", " ", "t", null, null, null);
        AppendLedgerEntryRequest future = new AppendLedgerEntryRequest("gw", "alice", "A", "t", null, null, Instant.now().plus(Duration.ofHours(1)));

        assertThrows(IllegalArgumentException.class, () -> useCase.execute(organisationId, blankAction, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> useCase.execute(organisationId, future, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> useCase.execute(organisationId, request(), " "));

        verifyNoInteractions(repository, hashServicePort);
    }

    @Test
    @DisplayName("Append: an explicit occurredAt in the past is kept, a few seconds ahead (clock skew) is tolerated")
    void appendOccurredAt() {
        Instant past = Instant.parse("2026-01-01T00:00:00Z");
        when(hashServicePort.generateSovereignId(any())).thenReturn(Mono.just(sovereignId));
        when(repository.findLatest(organisationId)).thenReturn(Mono.empty());
        stubAppendEcho();

        StepVerifier.create(append().execute(organisationId,
                        new AppendLedgerEntryRequest("gw", "alice", "A", "t", null, null, past), EXECUTOR))
                .assertNext(response -> assertEquals(past, response.occurredAt()))
                .verifyComplete();
        StepVerifier.create(append().execute(organisationId,
                        new AppendLedgerEntryRequest("gw", "alice", "A", "t", null, null, Instant.now().plusSeconds(30)), EXECUTOR))
                .expectNextCount(1)
                .verifyComplete();
    }

    // ------------------------------------------------------------ retrieve

    @Test
    @DisplayName("Retrieve: returns the entry of the caller's tenant; another tenant's entry or a missing one is a 404")
    void retrieveOne() {
        LedgerEntry entry = head(1);
        when(repository.findById(entry.getId())).thenReturn(Mono.just(entry));
        UUID missing = UUID.randomUUID();
        when(repository.findById(missing)).thenReturn(Mono.empty());
        RetrieveLedgerEntryUseCase useCase = new RetrieveLedgerEntryUseCase(repository);

        StepVerifier.create(useCase.execute(organisationId, entry.getId())).assertNext(r -> assertEquals(entry.getHash(), r.hash())).verifyComplete();
        StepVerifier.create(useCase.execute(UUID.randomUUID(), entry.getId())).expectError(LedgerEntryNotFoundException.class).verify();
        StepVerifier.create(useCase.execute(organisationId, missing)).expectError(LedgerEntryNotFoundException.class).verify();
    }

    @Test
    @DisplayName("List: passes the filters through and bounds the page (default 100, clamp 1..500)")
    void listBoundsThePage() {
        when(repository.search(eq(organisationId), any(Filter.class), anyInt())).thenReturn(Flux.just(head(1)));
        RetrieveLedgerEntriesUseCase useCase = new RetrieveLedgerEntriesUseCase(repository);
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-02-01T00:00:00Z");

        StepVerifier.create(useCase.execute(organisationId, "alice", "A", "t", "r", from, to, null)).expectNextCount(1).verifyComplete();
        useCase.execute(organisationId, null, null, null, null, null, null, 0).blockLast();
        useCase.execute(organisationId, null, null, null, null, null, null, 9999).blockLast();
        useCase.execute(organisationId, null, null, null, null, null, null, 25).blockLast();

        ArgumentCaptor<Filter> filter = ArgumentCaptor.forClass(Filter.class);
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(repository, times(4)).search(eq(organisationId), filter.capture(), limit.capture());
        assertEquals(new Filter("alice", "A", "t", "r", from, to), filter.getAllValues().get(0));
        assertEquals(java.util.List.of(100, 1, 500, 25), limit.getAllValues());
    }

    // ------------------------------------------------------------ integrity

    @Test
    @DisplayName("Integrity: folds the streamed chain into a verdict - valid for an intact chain, broken for a tampered one")
    void integrity() {
        LedgerEntry one = head(1);
        LedgerEntry two = LedgerEntry.createNew(UUID.randomUUID(), organisationId, 2, Instant.now().minusSeconds(30), "gw", "bob", "B", "t", null, null, "gw", one.getHash());
        EvaluateChainIntegrityUseCase useCase = new EvaluateChainIntegrityUseCase(repository);

        when(repository.streamChain(organisationId)).thenReturn(Flux.just(one, two));
        StepVerifier.create(useCase.execute(organisationId)).assertNext(r -> {
            assertTrue(r.valid());
            assertEquals(2, r.entriesChecked());
            assertEquals(2, r.headSequence());
            assertEquals(two.getHash(), r.headHash());
        }).verifyComplete();

        when(repository.streamChain(organisationId)).thenReturn(Flux.just(two));
        StepVerifier.create(useCase.execute(organisationId)).assertNext(r -> {
            assertEquals(false, r.valid());
            assertEquals(2L, r.firstBrokenSequence());
        }).verifyComplete();
    }

    @Test
    @DisplayName("Integrity: every subscription gets its own verifier (no state leaks between calls)")
    void integrityIsRepeatable() {
        EvaluateChainIntegrityUseCase useCase = new EvaluateChainIntegrityUseCase(repository);
        when(repository.streamChain(organisationId)).thenReturn(Flux.just(head(1)));
        Mono<?> verdict = useCase.execute(organisationId);

        assertEquals(verdict.block(), verdict.block());
    }

    // ------------------------------------------------------------ mapper

    @Test
    @DisplayName("The mapper is a non-instantiable utility class")
    void mapperIsUtility() throws Exception {
        Constructor<LedgerEntryMapper> constructor = LedgerEntryMapper.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        InvocationTargetException failure = assertThrows(InvocationTargetException.class, constructor::newInstance);

        assertInstanceOf(UnsupportedOperationException.class, failure.getCause());
    }
}
