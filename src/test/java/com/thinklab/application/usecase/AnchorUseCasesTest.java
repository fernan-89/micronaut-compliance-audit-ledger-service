package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.AnchorResultResponse;
import com.thinklab.domain.exception.AnchoringNotConfiguredException;
import com.thinklab.domain.model.Anchor;
import com.thinklab.domain.model.AnchorSigner;
import com.thinklab.domain.model.LedgerEntry;
import com.thinklab.domain.port.AnchorPort;
import com.thinklab.domain.repository.LedgerEntryRepository;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnchorUseCasesTest {

    private static final Instant WHEN = Instant.parse("2026-10-03T12:00:00Z");

    @Mock private LedgerEntryRepository repository;
    @Mock private AnchorPort anchorPort;

    private final AnchorSigner signer = new AnchorSigner("anchor-key");
    private UUID org;

    @BeforeEach
    void setUp() {
        org = UUID.randomUUID();
    }

    private List<LedgerEntry> chain(int length) {
        List<LedgerEntry> entries = new ArrayList<>();
        String previous = LedgerEntry.GENESIS_HASH;
        for (int i = 1; i <= length; i++) {
            LedgerEntry e = LedgerEntry.createNew(UUID.randomUUID(), org, i, WHEN.plusSeconds(i), "gw", "alice", "A" + i, "t", null, null, "gw", previous);
            entries.add(e);
            previous = e.getHash();
        }
        return entries;
    }

    private Anchor anchorAt(List<LedgerEntry> chain, int position) {
        LedgerEntry e = chain.get(position - 1);
        return signer.sign(org, e.getSequence(), e.getHash(), WHEN.plusSeconds(100));
    }

    private AnchorChainHeadsUseCase anchoring() {
        return new AnchorChainHeadsUseCase(repository, anchorPort, signer);
    }

    // ------------------------------------------------------------ anchoring

    @Test
    @DisplayName("first anchor of a tenant: verifies the whole chain from the genesis and publishes a signed anchor at the head")
    void firstAnchor() {
        List<LedgerEntry> entries = chain(3);
        when(anchorPort.read(org)).thenReturn(Flux.empty());
        when(repository.streamChainAfter(org, 0)).thenReturn(Flux.fromIterable(entries));
        when(anchorPort.publish(any(Anchor.class))).thenReturn(Mono.empty());

        StepVerifier.create(anchoring().executeFor(org)).assertNext(result -> {
            assertEquals("PUBLISHED", result.status());
            assertEquals(3, result.anchor().headSequence());
            assertEquals(entries.get(2).getHash(), result.anchor().headHash());
            assertNull(result.reason());
        }).verifyComplete();

        ArgumentCaptor<Anchor> published = ArgumentCaptor.forClass(Anchor.class);
        verify(anchorPort).publish(published.capture());
        assertTrue(signer.isAuthentic(published.getValue()));
        assertEquals(org, published.getValue().organisationId());
    }

    @Test
    @DisplayName("later anchors verify only what is new, starting from the last anchor's hash")
    void incrementalAnchor() {
        List<LedgerEntry> entries = chain(5);
        Anchor last = anchorAt(entries, 3);
        when(anchorPort.read(org)).thenReturn(Flux.just(anchorAt(entries, 2), last));
        when(repository.streamChainAfter(org, 3)).thenReturn(Flux.fromIterable(entries.subList(3, 5)));
        when(anchorPort.publish(any(Anchor.class))).thenReturn(Mono.empty());

        StepVerifier.create(anchoring().executeFor(org)).assertNext(result -> assertEquals(5, result.anchor().headSequence())).verifyComplete();

        verify(repository, never()).streamChainAfter(org, 0);
    }

    @Test
    @DisplayName("nothing new since the last anchor (or an empty chain) publishes nothing")
    void unchanged() {
        List<LedgerEntry> entries = chain(3);
        Anchor last = anchorAt(entries, 3);
        when(anchorPort.read(org)).thenReturn(Flux.just(last)).thenReturn(Flux.empty());
        when(repository.streamChainAfter(org, 3)).thenReturn(Flux.empty());
        when(repository.streamChainAfter(org, 0)).thenReturn(Flux.empty());
        when(repository.findLatest(org)).thenReturn(Mono.just(entries.get(2)));

        StepVerifier.create(anchoring().executeFor(org)).assertNext(result -> {
            assertEquals("UNCHANGED", result.status());
            assertEquals(3, result.anchor().headSequence());
        }).verifyComplete();
        StepVerifier.create(anchoring().executeFor(org)).assertNext(result -> {
            assertEquals("UNCHANGED", result.status());
            assertNull(result.anchor());
        }).verifyComplete();

        verify(anchorPort, never()).publish(any());
    }

    @Test
    @DisplayName("nothing new, but the head no longer matches the last anchor (truncated, rewritten in place, or emptied): REFUSED, never UNCHANGED")
    void refusesWhenTheHeadNoLongerMatchesTheAnchor() {
        List<LedgerEntry> entries = chain(5);
        Anchor last = anchorAt(entries, 5);
        LedgerEntry rewrittenHead = LedgerEntry.createNew(UUID.randomUUID(), org, 5, WHEN.plusSeconds(5), "gw", "mallory", "A5", "t", null, null, "gw", entries.get(3).getHash());
        when(anchorPort.read(org)).thenReturn(Flux.just(last));
        when(repository.streamChainAfter(org, 5)).thenReturn(Flux.empty());
        when(repository.findLatest(org)).thenReturn(Mono.just(entries.get(3))).thenReturn(Mono.just(rewrittenHead)).thenReturn(Mono.empty());

        for (String expected : new String[]{"entries were removed or rewritten", "entries were removed or rewritten", "chain is empty"}) {
            StepVerifier.create(anchoring().executeFor(org)).assertNext(result -> {
                assertEquals("REFUSED", result.status());
                assertTrue(result.reason().contains(expected), result.reason());
            }).verifyComplete();
        }
        verify(anchorPort, never()).publish(any());
    }

    @Test
    @DisplayName("a chain that fails verification is NEVER anchored: sealing it would turn the tampering into a signed fact")
    void refusesABrokenChain() {
        List<LedgerEntry> entries = chain(4);
        entries.remove(1);
        when(anchorPort.read(org)).thenReturn(Flux.empty());
        when(repository.streamChainAfter(org, 0)).thenReturn(Flux.fromIterable(entries));

        StepVerifier.create(anchoring().executeFor(org)).assertNext(result -> {
            assertEquals("REFUSED", result.status());
            assertNull(result.anchor());
            assertTrue(result.reason().contains("missing"));
        }).verifyComplete();

        verify(anchorPort, never()).publish(any());
    }

    @Test
    @DisplayName("a rewrite BELOW the last anchor breaks the link to it, so the new head is refused too")
    void refusesWhenTheCheckpointNoLongerLinks() {
        List<LedgerEntry> honest = chain(3);
        Anchor last = anchorAt(honest, 3);
        LedgerEntry orphan = LedgerEntry.createNew(UUID.randomUUID(), org, 4, WHEN.plusSeconds(4), "gw", "alice", "A4", "t", null, null, "gw", "f".repeat(64));
        when(anchorPort.read(org)).thenReturn(Flux.just(last));
        when(repository.streamChainAfter(org, 3)).thenReturn(Flux.just(orphan));

        StepVerifier.create(anchoring().executeFor(org)).assertNext(result -> assertEquals("REFUSED", result.status())).verifyComplete();
    }

    @Test
    @DisplayName("an unauthentic latest anchor means the anchor store was tampered with: refused, nothing read from the chain")
    void refusesAnUnauthenticAnchor() {
        when(anchorPort.read(org)).thenReturn(Flux.just(new Anchor(org, 3, "ab".repeat(32), WHEN, "00")));

        StepVerifier.create(anchoring().executeFor(org)).assertNext(result -> {
            assertEquals("REFUSED", result.status());
            assertTrue(result.reason().contains("anchor store was tampered with"));
        }).verifyComplete();

        verify(repository, never()).streamChainAfter(any(), anyLong());
    }

    @Test
    @DisplayName("executeAll walks every tenant, logs a refusal, survives one tenant failing, and keeps going")
    void executeAll() {
        UUID refusedOrg = UUID.randomUUID();
        UUID failingOrg = UUID.randomUUID();
        List<LedgerEntry> entries = chain(2);
        List<LedgerEntry> broken = new ArrayList<>(entries);
        broken.remove(0);
        when(repository.findOrganisationIds()).thenReturn(Flux.just(org, refusedOrg, failingOrg));
        when(anchorPort.read(org)).thenReturn(Flux.empty());
        when(repository.streamChainAfter(org, 0)).thenReturn(Flux.fromIterable(entries));
        when(anchorPort.publish(any(Anchor.class))).thenReturn(Mono.empty());
        when(anchorPort.read(refusedOrg)).thenReturn(Flux.empty());
        when(repository.streamChainAfter(refusedOrg, 0)).thenReturn(Flux.fromIterable(broken));
        when(anchorPort.read(failingOrg)).thenReturn(Flux.error(new IllegalStateException("anchor store unreadable")));

        List<AnchorResultResponse> results = anchoring().executeAll().collectList().block();

        assertEquals(List.of("PUBLISHED", "REFUSED"), results.stream().map(AnchorResultResponse::status).toList());
    }

    @Test
    @DisplayName("publishing without anchoring configured surfaces AnchoringNotConfiguredException")
    void notConfigured() {
        when(anchorPort.read(org)).thenReturn(Flux.empty());
        when(repository.streamChainAfter(org, 0)).thenReturn(Flux.fromIterable(chain(1)));
        when(anchorPort.publish(any(Anchor.class))).thenReturn(Mono.error(new AnchoringNotConfiguredException()));

        StepVerifier.create(anchoring().executeFor(org)).expectError(AnchoringNotConfiguredException.class).verify();
    }

    // ------------------------------------------------------------ integrity with anchors

    @Test
    @DisplayName("Integrity: confirms the chain against every authentic anchor and reports how many")
    void integrityWithAnchors() {
        List<LedgerEntry> entries = chain(4);
        when(anchorPort.read(org)).thenReturn(Flux.just(anchorAt(entries, 2), anchorAt(entries, 4)));
        when(repository.streamChain(org)).thenReturn(Flux.fromIterable(entries));

        StepVerifier.create(new EvaluateChainIntegrityUseCase(repository, anchorPort, signer).execute(org)).assertNext(r -> {
            assertTrue(r.valid());
            assertEquals(2, r.anchorsVerified());
        }).verifyComplete();
    }

    @Test
    @DisplayName("Integrity: a chain that disagrees with an anchor is invalid; an anchor that does not authenticate is reported as tampering")
    void integrityCatchesRewritesAndForgedAnchors() {
        List<LedgerEntry> honest = chain(3);
        Anchor anchor = anchorAt(honest, 3);
        EvaluateChainIntegrityUseCase useCase = new EvaluateChainIntegrityUseCase(repository, anchorPort, signer);

        List<LedgerEntry> rewritten = new ArrayList<>(honest.subList(0, 2));
        rewritten.add(LedgerEntry.createNew(UUID.randomUUID(), org, 3, WHEN.plusSeconds(3), "gw", "mallory", "A3", "t", null, null, "gw", rewritten.get(1).getHash()));
        when(anchorPort.read(org)).thenReturn(Flux.just(anchor));
        when(repository.streamChain(org)).thenReturn(Flux.fromIterable(rewritten));
        StepVerifier.create(useCase.execute(org)).assertNext(r -> {
            assertFalse(r.valid());
            assertEquals(3L, r.firstBrokenSequence());
        }).verifyComplete();

        when(anchorPort.read(org)).thenReturn(Flux.just(new Anchor(org, 2, honest.get(1).getHash(), WHEN, "00")));
        when(repository.streamChain(org)).thenReturn(Flux.fromIterable(honest));
        StepVerifier.create(useCase.execute(org)).assertNext(r -> {
            assertFalse(r.valid());
            assertTrue(r.reason().contains("anchor store was tampered with"));
        }).verifyComplete();
    }

    // ------------------------------------------------------------ retrieve anchors

    @Test
    @DisplayName("Retrieve anchors: lists them oldest first, without the signature")
    void retrieveAnchors() {
        List<LedgerEntry> entries = chain(3);
        when(anchorPort.read(org)).thenReturn(Flux.just(anchorAt(entries, 1), anchorAt(entries, 3)));

        List<Long> positions = new RetrieveAnchorsUseCase(anchorPort).execute(org).map(a -> a.headSequence()).collectList().block();

        assertEquals(List.of(1L, 3L), positions);
    }
}
