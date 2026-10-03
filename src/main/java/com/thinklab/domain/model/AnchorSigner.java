package com.thinklab.domain.model;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Signs and authenticates {@link Anchor}s: HMAC-SHA256 over an unambiguous encoding of the anchor's fields. A signer without a
 * key (anchoring not configured) can authenticate nothing and sign nothing.
 *
 * <p><b>Key rotation (ADR-035):</b> new anchors are signed with the current key and name it ({@code keyId}); the signature covers
 * the key id, so an anchor cannot be re-labelled as signed by another key. Older keys stay configured only to VERIFY: an anchor is
 * authenticated with the key its {@code keyId} names, current or retired. An anchor with no key id was published before keys were
 * named; it is authenticated, in the original encoding, with any configured key (the key of that time is, by now, the current or a
 * retired one).
 */
public final class AnchorSigner {

    public static final String DEFAULT_KEY_ID = "k1";
    private static final String ALGORITHM = "HmacSHA256";

    private final String keyId;
    private final String key;
    private final Map<String, String> verificationKeys = new LinkedHashMap<>();

    /** A signer with one key, named {@value #DEFAULT_KEY_ID}. */
    public AnchorSigner(String key) {
        this(DEFAULT_KEY_ID, key, Map.of());
    }

    /**
     * @param keyId        the id of the current key, written into every anchor it signs
     * @param key          the current key (blank = anchoring is not configured)
     * @param retiredKeys  keys that no longer sign but still verify the anchors they signed, by key id
     */
    public AnchorSigner(String keyId, String key, Map<String, String> retiredKeys) {
        this.keyId = keyId == null || keyId.isBlank() ? DEFAULT_KEY_ID : keyId.trim();
        this.key = key == null ? "" : key;
        retiredKeys.forEach((id, retired) -> {
            if (retired != null && !retired.isBlank()) {
                verificationKeys.put(id, retired);
            }
        });
        if (hasKey()) {
            verificationKeys.put(this.keyId, this.key);
        }
    }

    public boolean hasKey() {
        return !key.isBlank();
    }

    /** The id of the key new anchors are signed with. */
    public String keyId() {
        return keyId;
    }

    public Anchor sign(UUID organisationId, long headSequence, String headHash, Instant anchoredAt) {
        if (!hasKey()) {
            throw new IllegalStateException("Anchoring needs ledger.anchor.key: an anchor cannot be signed without it.");
        }
        Instant at = anchoredAt.truncatedTo(ChronoUnit.MILLIS);
        return new Anchor(organisationId, headSequence, headHash, at, mac(ALGORITHM, key, canonical(organisationId, headSequence, headHash, at, keyId)), keyId);
    }

    /** True only when the anchor was signed with a key this signer knows (the one it names) and has not been altered since. */
    public boolean isAuthentic(Anchor anchor) {
        if (verificationKeys.isEmpty() || anchor.signature() == null) {
            return false;
        }
        if (anchor.keyId() == null || anchor.keyId().isBlank()) {
            String legacy = canonical(anchor.organisationId(), anchor.headSequence(), anchor.headHash(), anchor.anchoredAt(), null);
            return verificationKeys.values().stream().anyMatch(candidate -> matches(candidate, legacy, anchor.signature()));
        }
        String named = verificationKeys.get(anchor.keyId());
        return named != null && matches(named, canonical(anchor.organisationId(), anchor.headSequence(), anchor.headHash(), anchor.anchoredAt(), anchor.keyId()), anchor.signature());
    }

    private static boolean matches(String key, String canonical, String signature) {
        return MessageDigest.isEqual(mac(ALGORITHM, key, canonical).getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
    }

    /** The original encoding when there is no key id, and the same fields plus the key id when there is one. */
    private static String canonical(UUID organisationId, long headSequence, String headHash, Instant anchoredAt, String keyId) {
        String base = organisationId + "|" + headSequence + "|" + headHash + "|" + anchoredAt;
        return keyId == null ? base : base + "|" + keyId;
    }

    /** The algorithm is a parameter only so the impossible failure path is testable. */
    static String mac(String algorithm, String key, String canonical) {
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), algorithm));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(algorithm + " is required by the JVM specification.", e);
        }
    }
}
