package com.thinklab.domain.model;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Signs and authenticates {@link Anchor}s: HMAC-SHA256 over an unambiguous encoding of the anchor's fields. A signer without a
 * key (anchoring not configured) can authenticate nothing and sign nothing.
 */
public final class AnchorSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private final String key;

    public AnchorSigner(String key) {
        this.key = key == null ? "" : key;
    }

    public boolean hasKey() {
        return !key.isBlank();
    }

    public Anchor sign(UUID organisationId, long headSequence, String headHash, Instant anchoredAt) {
        if (!hasKey()) {
            throw new IllegalStateException("Anchoring needs ledger.anchor.key: an anchor cannot be signed without it.");
        }
        Instant at = anchoredAt.truncatedTo(ChronoUnit.MILLIS);
        return new Anchor(organisationId, headSequence, headHash, at, mac(ALGORITHM, key, organisationId, headSequence, headHash, at));
    }

    /** True only when the anchor was signed with this key and has not been altered since. */
    public boolean isAuthentic(Anchor anchor) {
        if (!hasKey() || anchor.signature() == null) {
            return false;
        }
        String expected = mac(ALGORITHM, key, anchor.organisationId(), anchor.headSequence(), anchor.headHash(), anchor.anchoredAt());
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), anchor.signature().getBytes(StandardCharsets.UTF_8));
    }

    /** The algorithm is a parameter only so the impossible failure path is testable. */
    static String mac(String algorithm, String key, UUID organisationId, long headSequence, String headHash, Instant anchoredAt) {
        String canonical = organisationId + "|" + headSequence + "|" + headHash + "|" + anchoredAt;
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), algorithm));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(algorithm + " is required by the JVM specification.", e);
        }
    }
}
