package com.thinklab.domain.model;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Domain Service that walks a tenant's chain in sequence order and reports the first place it stops being
 * trustworthy (ADR-031, ADR-034). Stateful and single-use: feed every entry to {@link #accept}, then read {@link #result}.
 *
 * <p>An entry is accepted when (1) it sits at the next position (a gap means an entry was removed or never
 * written), (2) its {@code previousHash} equals the hash of the entry before it ({@link LedgerEntry#GENESIS_HASH}
 * for the first), (3) its own hash still matches its content, and (4) if an {@link Anchor} was published for its position, the
 * hash equals the anchored one. (4) is what catches a chain rewritten with every hash recomputed, which (1)-(3) cannot. After
 * the first failure the rest is not examined: everything past a broken link is untrustworthy anyway.
 *
 * <p>A verifier can start from a trusted checkpoint (the last verified anchor) instead of the beginning, so verification of a long
 * ledger does not have to re-walk what was already proven.
 */
public final class ChainVerifier {

    private final Map<Long, String> anchoredHeads = new HashMap<>();
    private long checked;
    private long expectedSequence;
    private String expectedPreviousHash;
    private Long firstBrokenSequence;
    private String reason;

    /** Verifies from position 1, comparing against the given (already authenticated) anchors. */
    public ChainVerifier(Collection<Anchor> anchors) {
        this(1, LedgerEntry.GENESIS_HASH, anchors);
    }

    /** Verifies from a trusted checkpoint: the next entry must be {@code nextSequence} and link to {@code previousHash}. */
    public ChainVerifier(long nextSequence, String previousHash, Collection<Anchor> anchors) {
        this.expectedSequence = nextSequence;
        this.expectedPreviousHash = previousHash;
        anchors.forEach(anchor -> anchoredHeads.put(anchor.headSequence(), anchor.headHash()));
    }

    public void accept(LedgerEntry entry) {
        if (firstBrokenSequence != null) {
            return;
        }
        checked++;
        if (entry.getSequence() != expectedSequence) {
            breakAt(entry.getSequence(), "Expected position " + expectedSequence + " but found " + entry.getSequence() + ": an entry is missing.");
        } else if (!entry.getPreviousHash().equals(expectedPreviousHash)) {
            breakAt(entry.getSequence(), "The link to the previous entry does not match: the chain was altered.");
        } else if (!entry.isIntact()) {
            breakAt(entry.getSequence(), "The content of the entry no longer matches its hash: it was altered after being written.");
        } else if (anchoredHeads.containsKey(entry.getSequence()) && !anchoredHeads.get(entry.getSequence()).equals(entry.getHash())) {
            breakAt(entry.getSequence(), "The entry differs from the hash anchored outside the database: the chain was rewritten after it was anchored.");
        } else {
            expectedSequence++;
            expectedPreviousHash = entry.getHash();
        }
    }

    /** An anchor whose signature does not verify: the anchor store itself was tampered with (or signed with another key). */
    public void rejectAnchor(Anchor anchor) {
        if (firstBrokenSequence == null) {
            breakAt(anchor.headSequence(), "The anchor published for position " + anchor.headSequence() + " does not authenticate: the anchor store was tampered with.");
        }
    }

    private void breakAt(long sequence, String why) {
        this.firstBrokenSequence = sequence;
        this.reason = why;
    }

    public ChainVerification result() {
        long headSequence = expectedSequence - 1;
        if (firstBrokenSequence == null) {
            long beyond = anchoredHeads.keySet().stream().filter(sequence -> sequence > headSequence).min(Long::compare).orElse(0L);
            if (beyond > 0) {
                breakAt(headSequence + 1, "The chain ends at position " + headSequence + " but an anchor was published for position " + beyond
                        + ": entries were removed after anchoring.");
            }
        }
        // A valid result implies no anchor lies beyond the head (checked just above), so each one was compared and agreed.
        long verifiedAnchors = firstBrokenSequence != null ? 0 : anchoredHeads.size();
        return new ChainVerification(firstBrokenSequence == null, checked, headSequence,
                headSequence == 0 ? null : expectedPreviousHash, firstBrokenSequence, reason, verifiedAnchors);
    }

    /**
     * @param valid                the whole chain checked out (and agrees with every anchor)
     * @param entriesChecked       how many entries were examined (including the first broken one)
     * @param headSequence         position of the last trustworthy entry (0 for an empty chain)
     * @param headHash             hash of that entry, {@code null} for an empty chain
     * @param firstBrokenSequence  position where verification failed, {@code null} when valid
     * @param reason               why it failed, {@code null} when valid
     * @param anchorsVerified      how many published anchors the chain was confirmed against (0 when invalid or unanchored)
     */
    public record ChainVerification(boolean valid, long entriesChecked, long headSequence, String headHash,
                                    Long firstBrokenSequence, String reason, long anchorsVerified) {}
}
