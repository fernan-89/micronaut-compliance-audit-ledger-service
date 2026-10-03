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
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainVerifierTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final Instant WHEN = Instant.parse("2026-10-03T12:00:00Z");

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

    private static ChainVerification verify(List<LedgerEntry> entries) {
        ChainVerifier verifier = new ChainVerifier();
        entries.forEach(verifier::accept);
        return verifier.result();
    }

    private static LedgerEntry tampered(LedgerEntry e, String actor, String hash, String previousHash, long sequence) {
        return LedgerEntry.reconstitute(e.getId(), ORG, sequence, e.getOccurredAt(), e.getRecordedAt(), e.getSource(), actor, e.getAction(),
                e.getResourceType(), e.getResourceId(), e.getDetail(), e.getRecordedBy(), previousHash, hash);
    }

    @Test
    @DisplayName("an empty chain is valid, with no head")
    void empty() {
        ChainVerification result = verify(List.of());

        assertTrue(result.valid());
        assertEquals(0, result.entriesChecked());
        assertEquals(0, result.headSequence());
        assertNull(result.headHash());
        assertNull(result.firstBrokenSequence());
        assertNull(result.reason());
    }

    @Test
    @DisplayName("an untouched chain is valid and reports its head")
    void intact() {
        List<LedgerEntry> entries = chain(5);

        ChainVerification result = verify(entries);

        assertTrue(result.valid());
        assertEquals(5, result.entriesChecked());
        assertEquals(5, result.headSequence());
        assertEquals(entries.get(4).getHash(), result.headHash());
    }

    @Test
    @DisplayName("an entry whose content was altered is the first broken one, and the head stops before it")
    void alteredContent() {
        List<LedgerEntry> entries = chain(4);
        LedgerEntry original = entries.get(2);
        entries.set(2, tampered(original, "mallory", original.getHash(), original.getPreviousHash(), 3));

        ChainVerification result = verify(entries);

        assertFalse(result.valid());
        assertEquals(3, result.firstBrokenSequence());
        assertEquals(3, result.entriesChecked());
        assertEquals(2, result.headSequence());
        assertEquals(entries.get(1).getHash(), result.headHash());
        assertTrue(result.reason().contains("altered after being written"));
    }

    @Test
    @DisplayName("a rewritten entry with a recomputed hash still breaks the link of the entry after it")
    void rewrittenWithRecomputedHash() {
        List<LedgerEntry> entries = chain(3);
        LedgerEntry forged = LedgerEntry.createNew(entries.get(1).getId(), ORG, 2, entries.get(1).getOccurredAt(), "gw", "mallory", "A2", "t", "r2", null, "gw",
                entries.get(0).getHash());
        entries.set(1, forged);

        ChainVerification result = verify(entries);

        assertFalse(result.valid());
        assertEquals(3, result.firstBrokenSequence());
        assertTrue(result.reason().contains("link to the previous entry"));
    }

    @Test
    @DisplayName("a removed entry shows up as a gap, and so does a removed first entry")
    void gaps() {
        List<LedgerEntry> middle = chain(4);
        middle.remove(1);
        ChainVerification gap = verify(middle);
        assertFalse(gap.valid());
        assertEquals(3, gap.firstBrokenSequence());
        assertTrue(gap.reason().contains("Expected position 2 but found 3"));

        List<LedgerEntry> head = chain(3);
        head.remove(0);
        ChainVerification noGenesis = verify(head);
        assertFalse(noGenesis.valid());
        assertEquals(2, noGenesis.firstBrokenSequence());
        assertEquals(0, noGenesis.headSequence());
        assertNull(noGenesis.headHash());
    }

    @Test
    @DisplayName("after the first failure the rest is not examined")
    void stopsAtFirstFailure() {
        List<LedgerEntry> entries = chain(5);
        LedgerEntry original = entries.get(1);
        entries.set(1, tampered(original, "mallory", original.getHash(), original.getPreviousHash(), 2));

        ChainVerification result = verify(entries);

        assertEquals(2, result.entriesChecked());
        assertEquals(2, result.firstBrokenSequence());
    }
}
