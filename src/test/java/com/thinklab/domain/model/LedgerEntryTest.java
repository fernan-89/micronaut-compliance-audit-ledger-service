package com.thinklab.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerEntryTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final Instant WHEN = Instant.parse("2026-10-03T12:00:00.123456789Z");

    private LedgerEntry entry(long sequence, String previousHash, String action, String detail) {
        return LedgerEntry.createNew(UUID.randomUUID(), ORG, sequence, WHEN, "platform-gateway", "alice", action, "it-asset-registry",
                "asset-1", detail, "gateway", previousHash);
    }

    @Test
    @DisplayName("createNew commits to the content: 64-hex hash, millisecond timestamps, accessors")
    void createNew() {
        LedgerEntry e = entry(1, LedgerEntry.GENESIS_HASH, "PUT control/deploy", "status=204");

        assertEquals(64, e.getHash().length());
        assertTrue(e.getHash().matches("[0-9a-f]{64}"));
        assertEquals(Instant.parse("2026-10-03T12:00:00.123Z"), e.getOccurredAt());
        assertEquals(1, e.getSequence());
        assertEquals(ORG, e.getOrganisationId());
        assertEquals("platform-gateway", e.getSource());
        assertEquals("alice", e.getActor());
        assertEquals("PUT control/deploy", e.getAction());
        assertEquals("it-asset-registry", e.getResourceType());
        assertEquals("asset-1", e.getResourceId());
        assertEquals("status=204", e.getDetail());
        assertEquals("gateway", e.getRecordedBy());
        assertEquals(LedgerEntry.GENESIS_HASH, e.getPreviousHash());
        assertTrue(e.getRecordedAt() != null && e.getId() != null);
        assertTrue(e.isIntact());
    }

    @Test
    @DisplayName("the hash is deterministic and changes with any committed field, including the previous hash")
    void hashCommitsToEveryField() {
        LedgerEntry base = entry(1, LedgerEntry.GENESIS_HASH, "A", "d");
        String same = LedgerEntry.computeHash(ORG, 1, base.getOccurredAt(), "platform-gateway", "alice", "A", "it-asset-registry", "asset-1", "d", "gateway", LedgerEntry.GENESIS_HASH);

        assertEquals(same, base.getHash());
        assertNotEquals(base.getHash(), entry(2, LedgerEntry.GENESIS_HASH, "A", "d").getHash());
        assertNotEquals(base.getHash(), entry(1, "f".repeat(64), "A", "d").getHash());
        assertNotEquals(base.getHash(), entry(1, LedgerEntry.GENESIS_HASH, "B", "d").getHash());
        assertNotEquals(base.getHash(), entry(1, LedgerEntry.GENESIS_HASH, "A", "e").getHash());
        assertNotEquals(base.getHash(), entry(1, LedgerEntry.GENESIS_HASH, "A", null).getHash());
    }

    @Test
    @DisplayName("the encoding is unambiguous: shifting text between adjacent fields yields a different hash")
    void encodingIsUnambiguous() {
        String a = LedgerEntry.computeHash(ORG, 1, WHEN, "ab", "c", "x", "t", null, null, "r", LedgerEntry.GENESIS_HASH);
        String b = LedgerEntry.computeHash(ORG, 1, WHEN, "a", "bc", "x", "t", null, null, "r", LedgerEntry.GENESIS_HASH);
        String nullVsEmpty = LedgerEntry.computeHash(ORG, 1, WHEN, "a", "b", "x", "t", "", null, "r", LedgerEntry.GENESIS_HASH);
        String nullDetail = LedgerEntry.computeHash(ORG, 1, WHEN, "a", "b", "x", "t", null, null, "r", LedgerEntry.GENESIS_HASH);

        assertNotEquals(a, b);
        assertNotEquals(nullVsEmpty, nullDetail);
    }

    @Test
    @DisplayName("reconstitute keeps the stored hash as is, so a tampered entry reports isIntact() == false")
    void tamperingIsDetected() {
        LedgerEntry e = entry(1, LedgerEntry.GENESIS_HASH, "A", "d");

        LedgerEntry altered = LedgerEntry.reconstitute(e.getId(), ORG, 1, e.getOccurredAt(), e.getRecordedAt(), e.getSource(), "mallory",
                e.getAction(), e.getResourceType(), e.getResourceId(), e.getDetail(), e.getRecordedBy(), e.getPreviousHash(), e.getHash());
        LedgerEntry untouched = LedgerEntry.reconstitute(e.getId(), ORG, 1, e.getOccurredAt(), e.getRecordedAt(), e.getSource(), e.getActor(),
                e.getAction(), e.getResourceType(), e.getResourceId(), e.getDetail(), e.getRecordedBy(), e.getPreviousHash(), e.getHash());

        assertFalse(altered.isIntact());
        assertTrue(untouched.isIntact());
    }

    @Test
    @DisplayName("createNew rejects missing identity, a position below 1, blank or oversized text and a malformed previous hash")
    void creationGuards() {
        UUID id = UUID.randomUUID();
        String g = LedgerEntry.GENESIS_HASH;

        assertThrows(IllegalArgumentException.class, () -> LedgerEntry.createNew(null, ORG, 1, WHEN, "s", "a", "x", "t", null, null, "r", g));
        assertThrows(IllegalArgumentException.class, () -> LedgerEntry.createNew(id, null, 1, WHEN, "s", "a", "x", "t", null, null, "r", g));
        assertThrows(IllegalArgumentException.class, () -> LedgerEntry.createNew(id, ORG, 1, null, "s", "a", "x", "t", null, null, "r", g));
        assertThrows(IllegalArgumentException.class, () -> LedgerEntry.createNew(id, ORG, 0, WHEN, "s", "a", "x", "t", null, null, "r", g));
        assertThrows(IllegalArgumentException.class, () -> LedgerEntry.createNew(id, ORG, 1, WHEN, " ", "a", "x", "t", null, null, "r", g));
        assertThrows(IllegalArgumentException.class, () -> LedgerEntry.createNew(id, ORG, 1, WHEN, "s", null, "x", "t", null, null, "r", g));
        assertThrows(IllegalArgumentException.class, () -> LedgerEntry.createNew(id, ORG, 1, WHEN, "s", "a", "", "t", null, null, "r", g));
        assertThrows(IllegalArgumentException.class, () -> LedgerEntry.createNew(id, ORG, 1, WHEN, "s", "a", "x", " ", null, null, "r", g));
        assertThrows(IllegalArgumentException.class, () -> LedgerEntry.createNew(id, ORG, 1, WHEN, "s", "a", "x", "t", null, null, null, g));
        assertThrows(IllegalArgumentException.class, () -> LedgerEntry.createNew(id, ORG, 1, WHEN, "s".repeat(201), "a", "x", "t", null, null, "r", g));
        assertThrows(IllegalArgumentException.class, () -> LedgerEntry.createNew(id, ORG, 1, WHEN, "s", "a", "x", "t", "r".repeat(201), null, "r", g));
        assertThrows(IllegalArgumentException.class, () -> LedgerEntry.createNew(id, ORG, 1, WHEN, "s", "a", "x", "t", null, "d".repeat(1001), "r", g));
        assertThrows(IllegalArgumentException.class, () -> LedgerEntry.createNew(id, ORG, 1, WHEN, "s", "a", "x", "t", null, null, "r", null));
        assertThrows(IllegalArgumentException.class, () -> LedgerEntry.createNew(id, ORG, 1, WHEN, "s", "a", "x", "t", null, null, "r", "abc"));
        LedgerEntry.createNew(id, ORG, 1, WHEN, "s".repeat(200), "a", "x", "t", "r".repeat(200), "d".repeat(1000), "r", g);
    }

    @Test
    @DisplayName("reconstitute requires identity, the occurrence instant and both hashes")
    void reconstituteGuards() {
        UUID id = UUID.randomUUID();
        String h = LedgerEntry.GENESIS_HASH;

        assertThrows(IllegalArgumentException.class, () -> LedgerEntry.reconstitute(null, ORG, 1, WHEN, WHEN, "s", "a", "x", "t", null, null, "r", h, h));
        assertThrows(IllegalArgumentException.class, () -> LedgerEntry.reconstitute(id, null, 1, WHEN, WHEN, "s", "a", "x", "t", null, null, "r", h, h));
        assertThrows(IllegalArgumentException.class, () -> LedgerEntry.reconstitute(id, ORG, 1, null, WHEN, "s", "a", "x", "t", null, null, "r", h, h));
        assertThrows(IllegalArgumentException.class, () -> LedgerEntry.reconstitute(id, ORG, 1, WHEN, WHEN, "s", "a", "x", "t", null, null, "r", null, h));
        assertThrows(IllegalArgumentException.class, () -> LedgerEntry.reconstitute(id, ORG, 1, WHEN, WHEN, "s", "a", "x", "t", null, null, "r", h, null));
    }

    @Test
    @DisplayName("digestHex is SHA-256 and reports an unavailable algorithm as an IllegalStateException")
    void digest() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", LedgerEntry.digestHex("SHA-256", "abc"));
        assertThrows(IllegalStateException.class, () -> LedgerEntry.digestHex("NOT-AN-ALGORITHM", "abc"));
    }

    @Test
    @DisplayName("identity is the sovereign id")
    void identity() {
        LedgerEntry e = entry(1, LedgerEntry.GENESIS_HASH, "A", null);
        LedgerEntry same = LedgerEntry.reconstitute(e.getId(), ORG, 9, WHEN, WHEN, "s", "a", "x", "t", null, null, "r", LedgerEntry.GENESIS_HASH, "h");

        assertEquals(e, e);
        assertEquals(e, same);
        assertEquals(e.hashCode(), same.hashCode());
        assertNotEquals(e, entry(1, LedgerEntry.GENESIS_HASH, "A", null));
        assertNotEquals(e, "not an entry");
    }
}
