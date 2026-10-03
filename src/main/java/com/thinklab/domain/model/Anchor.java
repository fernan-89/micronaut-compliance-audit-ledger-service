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
 * truly write-once still cannot be edited undetected.
 */
public record Anchor(UUID organisationId, long headSequence, String headHash, Instant anchoredAt, String signature) {
}
