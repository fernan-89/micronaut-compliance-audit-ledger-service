package com.thinklab.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Named anchor keys and their rotation (ADR-035): what verifies after the signing key changes, and what never does. */
class AnchorKeyRotationTest {

    private static final UUID ORG = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final Instant WHEN = Instant.parse("2026-10-03T12:00:00.123Z");
    private static final String HASH = "ab".repeat(32);

    @Test
    @DisplayName("an anchor names the key that signed it, and that key authenticates it")
    void anchorsNameTheirKey() {
        AnchorSigner signer = new AnchorSigner("k2", "second-key", Map.of());

        Anchor anchor = signer.sign(ORG, 5, HASH, WHEN);

        assertEquals("k2", anchor.keyId());
        assertEquals("k2", signer.keyId());
        assertTrue(signer.isAuthentic(anchor));
    }

    @Test
    @DisplayName("a single-key signer calls its key k1, and a blank id means k1 too")
    void defaultKeyId() {
        assertEquals("k1", new AnchorSigner("only-key").keyId());
        assertEquals("k1", new AnchorSigner(null, "only-key", Map.of()).keyId());
        assertEquals("k1", new AnchorSigner("  ", "only-key", Map.of()).keyId());
        assertEquals("k1", new AnchorSigner("only-key").sign(ORG, 1, HASH, WHEN).keyId());
    }

    @Test
    @DisplayName("after a rotation the new key signs and the retired key still verifies what it signed")
    void rotation() {
        Anchor oldAnchor = new AnchorSigner("k1", "first-key", Map.of()).sign(ORG, 5, HASH, WHEN);
        AnchorSigner rotated = new AnchorSigner("k2", "second-key", Map.of("k1", "first-key"));

        Anchor newAnchor = rotated.sign(ORG, 9, "cd".repeat(32), WHEN.plusSeconds(60));

        assertEquals("k2", newAnchor.keyId());
        assertTrue(rotated.isAuthentic(oldAnchor));
        assertTrue(rotated.isAuthentic(newAnchor));
    }

    @Test
    @DisplayName("a key that was dropped from the configuration can no longer vouch for its anchors")
    void aDroppedKeyVerifiesNothing() {
        Anchor oldAnchor = new AnchorSigner("k1", "first-key", Map.of()).sign(ORG, 5, HASH, WHEN);

        assertFalse(new AnchorSigner("k2", "second-key", Map.of()).isAuthentic(oldAnchor));
    }

    @Test
    @DisplayName("an anchor cannot be re-labelled as signed by another key, and an unknown key id authenticates nothing")
    void relabelling() {
        AnchorSigner signer = new AnchorSigner("k2", "second-key", Map.of("k1", "first-key"));
        Anchor anchor = signer.sign(ORG, 5, HASH, WHEN);

        assertFalse(signer.isAuthentic(new Anchor(ORG, 5, HASH, anchor.anchoredAt(), anchor.signature(), "k1")));
        assertFalse(signer.isAuthentic(new Anchor(ORG, 5, HASH, anchor.anchoredAt(), anchor.signature(), "k9")));
        assertFalse(signer.isAuthentic(new Anchor(ORG, 5, HASH, anchor.anchoredAt(), null, "k2")));
    }

    @Test
    @DisplayName("an anchor published before keys were named is verified, in its original encoding, by the current key or a retired one")
    void legacyAnchors() {
        Instant at = WHEN.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        String canonical = ORG + "|5|" + HASH + "|" + at;
        Anchor signedByCurrent = new Anchor(ORG, 5, HASH, at, AnchorSigner.mac("HmacSHA256", "second-key", canonical));
        Anchor signedByRetired = new Anchor(ORG, 5, HASH, at, AnchorSigner.mac("HmacSHA256", "first-key", canonical));
        Anchor signedByStranger = new Anchor(ORG, 5, HASH, at, AnchorSigner.mac("HmacSHA256", "stranger", canonical));
        AnchorSigner rotated = new AnchorSigner("k2", "second-key", Map.of("k1", "first-key"));

        assertNull(signedByCurrent.keyId());
        assertTrue(rotated.isAuthentic(signedByCurrent));
        assertTrue(rotated.isAuthentic(signedByRetired));
        assertFalse(rotated.isAuthentic(signedByStranger));
        assertTrue(rotated.isAuthentic(new Anchor(ORG, 5, HASH, at, signedByCurrent.signature(), " ")));
        assertFalse(rotated.isAuthentic(new Anchor(ORG, 6, HASH, at, signedByCurrent.signature())));
    }

    @Test
    @DisplayName("retired keys that are blank or null are ignored; with no key at all nothing signs and nothing authenticates")
    void blankKeys() {
        Map<String, String> retired = new HashMap<>();
        retired.put("k0", " ");
        retired.put("k1", null);
        AnchorSigner keyless = new AnchorSigner("k2", "", retired);
        Anchor anchor = new AnchorSigner("k2", "second-key", Map.of()).sign(ORG, 5, HASH, WHEN);

        assertFalse(keyless.hasKey());
        assertFalse(keyless.isAuthentic(anchor));
        assertTrue(new AnchorSigner("k2", "second-key", retired).isAuthentic(anchor));
    }
}
