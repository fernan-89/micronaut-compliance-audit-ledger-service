package com.thinklab.domain.model;

/**
 * Domain Service that walks a tenant's chain in sequence order and reports the first place it stops being
 * trustworthy (ADR-031). Stateful and single-use: feed every entry to {@link #accept}, then read {@link #result}.
 *
 * <p>An entry is accepted when (1) it sits at the next position (a gap means an entry was removed or never
 * written), (2) its {@code previousHash} equals the hash of the entry before it ({@link LedgerEntry#GENESIS_HASH}
 * for the first), and (3) its own hash still matches its content. After the first failure the rest is not
 * examined: everything past a broken link is untrustworthy anyway.
 */
public final class ChainVerifier {

    private long checked;
    private long expectedSequence = 1;
    private String expectedPreviousHash = LedgerEntry.GENESIS_HASH;
    private Long firstBrokenSequence;
    private String reason;

    public void accept(LedgerEntry entry) {
        if (firstBrokenSequence != null) {
            return;
        }
        checked++;
        if (entry.getSequence() != expectedSequence) {
            break_(entry, "Expected position " + expectedSequence + " but found " + entry.getSequence() + ": an entry is missing.");
        } else if (!entry.getPreviousHash().equals(expectedPreviousHash)) {
            break_(entry, "The link to the previous entry does not match: the chain was altered.");
        } else if (!entry.isIntact()) {
            break_(entry, "The content of the entry no longer matches its hash: it was altered after being written.");
        } else {
            expectedSequence++;
            expectedPreviousHash = entry.getHash();
        }
    }

    private void break_(LedgerEntry entry, String why) {
        this.firstBrokenSequence = entry.getSequence();
        this.reason = why;
    }

    public ChainVerification result() {
        long headSequence = expectedSequence - 1;
        return new ChainVerification(firstBrokenSequence == null, checked, headSequence,
                headSequence == 0 ? null : expectedPreviousHash, firstBrokenSequence, reason);
    }

    /**
     * @param valid                the whole chain checked out
     * @param entriesChecked       how many entries were examined (including the first broken one)
     * @param headSequence         position of the last trustworthy entry (0 for an empty chain)
     * @param headHash             hash of that entry, {@code null} for an empty chain
     * @param firstBrokenSequence  position where verification failed, {@code null} when valid
     * @param reason               why it failed, {@code null} when valid
     */
    public record ChainVerification(boolean valid, long entriesChecked, long headSequence, String headHash,
                                    Long firstBrokenSequence, String reason) {}
}
