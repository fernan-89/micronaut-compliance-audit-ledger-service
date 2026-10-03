package com.thinklab.domain.model;

import com.thinklab.domain.model.ChainVerifier.ChainVerification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Anchor signing and the verifier's use of anchors (ADR-034). */
class AnchorTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final Instant WHEN = Instant.parse("2026-10-03T12:00:00Z");
    private final AnchorSigner signer = new AnchorSigner("anchor-key");

    private static List<LedgerEntry> chain(int length) {
        List<LedgerEntry> entries = new ArrayList<>();
        String previous = LedgerEntry.GENESIS_HASH;
        for (int i = 1; i <= length; i++) {
            LedgerEntry e = LedgerEntry.createNew(UUID.randomUUID(), ORG, i, WHEN.plusSeconds(i), "gw", "alice", "A" + i, "t", "r" + i, null, "gw", previous);
            entries.add(e);
            previous = e.getHash();
        }
        return entries;
    }

    private Anchor anchorAt(List<LedgerEntry> chain, int position) {
        LedgerEntry e = chain.get(position - 1);
        return signer.sign(ORG, e.getSequence(), e.getHash(), WHEN.plusSeconds(100));
    }

    // ------------------------------------------------------------ signer

    @Test
    @DisplayName("a signed anchor authenticates; any altered field, another key, a missing signature or no key does not")
    void signing() {
        Anchor anchor = signer.sign(ORG, 5, "ab".repeat(32), Instant.parse("2026-10-03T12:00:00.123456Z"));

        assertEquals(Instant.parse("2026-10-03T12:00:00.123Z"), anchor.anchoredAt());
        assertTrue(signer.hasKey());
        assertTrue(signer.isAuthentic(anchor));
        assertFalse(signer.isAuthentic(new Anchor(ORG, 6, anchor.headHash(), anchor.anchoredAt(), anchor.signature())));
        assertFalse(signer.isAuthentic(new Anchor(ORG, 5, "cd".repeat(32), anchor.anchoredAt(), anchor.signature())));
        assertFalse(signer.isAuthentic(new Anchor(UUID.randomUUID(), 5, anchor.headHash(), anchor.anchoredAt(), anchor.signature())));
        assertFalse(signer.isAuthentic(new Anchor(ORG, 5, anchor.headHash(), anchor.anchoredAt().plusMillis(1), anchor.signature())));
        assertFalse(signer.isAuthentic(new Anchor(ORG, 5, anchor.headHash(), anchor.anchoredAt(), null)));
        assertFalse(new AnchorSigner("another-key").isAuthentic(anchor));
    }

    @Test
    @DisplayName("without a key nothing is signed and nothing authenticates")
    void noKey() {
        for (AnchorSigner keyless : new AnchorSigner[]{new AnchorSigner(""), new AnchorSigner("  "), new AnchorSigner(null)}) {
            assertFalse(keyless.hasKey());
            assertThrows(IllegalStateException.class, () -> keyless.sign(ORG, 1, "h", WHEN));
            assertFalse(keyless.isAuthentic(signer.sign(ORG, 1, "h", WHEN)));
        }
    }

    @Test
    @DisplayName("the HMAC failure path is an IllegalStateException")
    void macFailure() {
        assertThrows(IllegalStateException.class, () -> AnchorSigner.mac("NOT-AN-ALGORITHM", "k", "canonical"));
    }

    // ------------------------------------------------------------ verifier with anchors

    @Test
    @DisplayName("a chain that agrees with its anchors is valid and reports how many it was confirmed against")
    void agreesWithAnchors() {
        List<LedgerEntry> entries = chain(5);
        ChainVerifier verifier = new ChainVerifier(List.of(anchorAt(entries, 2), anchorAt(entries, 5)));
        entries.forEach(verifier::accept);

        ChainVerification result = verifier.result();

        assertTrue(result.valid());
        assertEquals(2, result.anchorsVerified());
        assertEquals(5, result.headSequence());
    }

    @Test
    @DisplayName("a chain rewritten with every hash recomputed is internally consistent but caught by the anchor")
    void rewriteWithRecomputedHashesIsCaughtByTheAnchor() {
        List<LedgerEntry> honest = chain(5);
        Anchor anchor = anchorAt(honest, 5);
        // The attacker rewrites entry 3 and recomputes every hash after it, so (1)-(3) of the verifier all pass.
        List<LedgerEntry> forged = new ArrayList<>(honest.subList(0, 2));
        String previous = forged.get(1).getHash();
        for (int i = 3; i <= 5; i++) {
            LedgerEntry e = LedgerEntry.createNew(UUID.randomUUID(), ORG, i, WHEN.plusSeconds(i), "gw", i == 3 ? "mallory" : "alice", "A" + i, "t", "r" + i, null, "gw", previous);
            forged.add(e);
            previous = e.getHash();
        }
        ChainVerifier withoutAnchors = new ChainVerifier(List.of());
        forged.forEach(withoutAnchors::accept);
        assertTrue(withoutAnchors.result().valid(), "without an anchor the forgery is undetectable");

        ChainVerifier withAnchor = new ChainVerifier(List.of(anchor));
        forged.forEach(withAnchor::accept);
        ChainVerification result = withAnchor.result();

        assertFalse(result.valid());
        assertEquals(5, result.firstBrokenSequence());
        assertTrue(result.reason().contains("anchored outside the database"));
        assertEquals(0, result.anchorsVerified());
    }

    @Test
    @DisplayName("a truncated chain is caught: an anchor exists for a position the chain no longer reaches")
    void truncationIsCaught() {
        List<LedgerEntry> entries = chain(5);
        ChainVerifier verifier = new ChainVerifier(List.of(anchorAt(entries, 5)));
        entries.subList(0, 3).forEach(verifier::accept);

        ChainVerification result = verifier.result();

        assertFalse(result.valid());
        assertEquals(4, result.firstBrokenSequence());
        assertEquals(3, result.headSequence());
        assertTrue(result.reason().contains("anchor was published for position 5"));
    }

    @Test
    @DisplayName("an anchor beyond a chain that is already broken does not replace the first failure")
    void firstFailureWins() {
        List<LedgerEntry> entries = chain(4);
        entries.remove(1);
        ChainVerifier verifier = new ChainVerifier(List.of(anchorAt(chain(4), 4)));
        entries.forEach(verifier::accept);

        ChainVerification result = verifier.result();

        assertEquals(3, result.firstBrokenSequence());
        assertTrue(result.reason().contains("missing"));
    }

    @Test
    @DisplayName("an anchor that does not authenticate is reported as tampering with the anchor store, once, and never overrides an earlier failure")
    void rejectedAnchor() {
        List<LedgerEntry> entries = chain(3);
        Anchor forged = new Anchor(ORG, 2, entries.get(1).getHash(), WHEN, "00");
        ChainVerifier verifier = new ChainVerifier(List.of());
        entries.forEach(verifier::accept);
        verifier.rejectAnchor(forged);
        verifier.rejectAnchor(new Anchor(ORG, 3, "x", WHEN, "00"));

        ChainVerification result = verifier.result();

        assertFalse(result.valid());
        assertEquals(2, result.firstBrokenSequence());
        assertTrue(result.reason().contains("anchor store was tampered with"));

        List<LedgerEntry> broken = chain(3);
        broken.remove(0);
        ChainVerifier alreadyBroken = new ChainVerifier(List.of());
        broken.forEach(alreadyBroken::accept);
        alreadyBroken.rejectAnchor(forged);
        assertTrue(alreadyBroken.result().reason().contains("missing"));
    }

    @Test
    @DisplayName("verification can start from a trusted checkpoint: the next entry must be the following position and link to the checkpoint hash")
    void checkpoint() {
        List<LedgerEntry> entries = chain(5);
        LedgerEntry checkpoint = entries.get(2);

        ChainVerifier fromCheckpoint = new ChainVerifier(4, checkpoint.getHash(), List.of());
        entries.subList(3, 5).forEach(fromCheckpoint::accept);
        ChainVerification ok = fromCheckpoint.result();
        assertTrue(ok.valid());
        assertEquals(2, ok.entriesChecked());
        assertEquals(5, ok.headSequence());

        ChainVerifier wrongLink = new ChainVerifier(4, "f".repeat(64), List.of());
        entries.subList(3, 5).forEach(wrongLink::accept);
        assertFalse(wrongLink.result().valid());

        ChainVerifier nothingNew = new ChainVerifier(6, entries.get(4).getHash(), List.of());
        ChainVerification empty = nothingNew.result();
        assertTrue(empty.valid());
        assertEquals(0, empty.entriesChecked());
        assertEquals(5, empty.headSequence());
        assertNull(new ChainVerifier(List.of()).result().headHash());
    }
}
