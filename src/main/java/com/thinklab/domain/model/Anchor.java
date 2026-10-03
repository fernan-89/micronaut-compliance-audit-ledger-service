package com.thinklab.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A commitment to the head of one tenant's chain, published OUTSIDE the database that holds the chain (ADR-034): "at
 * {@code anchoredAt}, position {@code headSequence} had hash {@code headHash}". Whoever can rewrite the ledger's database cannot
 * also rewrite what was published elsewhere, so a later chain that disagrees with an anchor was rewritten - even if every hash
 * in it is internally consistent.
 *
 * <p>{@code signature} is an HMAC over the other fields under a key the database does not hold, so an anchor store that is not
 * truly write-once still cannot be edited undetected. {@code keyId} names the key that signed it (ADR-035), so the key can be
 * rotated without losing the ability to verify older anchors; an anchor published before key ids existed has none.
 */
public record Anchor(UUID organisationId, long headSequence, String headHash, Instant anchoredAt, String signature, String keyId) {

    /** An anchor without a key id: how every anchor was published before keys were named (ADR-035). */
    public Anchor(UUID organisationId, long headSequence, String headHash, Instant anchoredAt, String signature) {
        this(organisationId, headSequence, headHash, anchoredAt, signature, null);
    }
}
