package com.thinklab.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Core Domain Model representing the LedgerEntry Control Record of the {@code compliance-audit-ledger}
 * Service Domain: one immutable fact about something that happened ("who did what to which resource,
 * when"), linked to the tenant's previous entry by hash so that altering or removing any entry
 * afterwards is detectable (ADR-030).
 *
 * <p>The aggregate is append-only by construction: there are no mutators, and the repository port has
 * no update or delete. {@code hash} commits to every business field plus {@code previousHash}; it
 * deliberately excludes {@code recordedAt} (set by the server on append) so that the value survives a
 * round trip through a store with millisecond precision, and timestamps are truncated to milliseconds
 * for the same reason.
 *
 * <p>Strictly pure Java. Agnostic of frameworks, databases, or web layers.
 */
public final class LedgerEntry {

    /** The {@code previousHash} of the first entry of every tenant's chain. */
    public static final String GENESIS_HASH = "0".repeat(64);

    public static final int MAX_FIELD_LENGTH = 200;
    public static final int MAX_DETAIL_LENGTH = 1000;

    private final UUID id;
    private final UUID organisationId;
    private final long sequence;
    private final Instant occurredAt;
    private final Instant recordedAt;
    private final String source;
    private final String actor;
    private final String action;
    private final String resourceType;
    private final String resourceId;
    private final String detail;
    private final String recordedBy;
    private final String previousHash;
    private final String hash;

    private LedgerEntry(UUID id, UUID organisationId, long sequence, Instant occurredAt, Instant recordedAt, String source,
                        String actor, String action, String resourceType, String resourceId, String detail, String recordedBy,
                        String previousHash, String hash) {
        this.id = id;
        this.organisationId = organisationId;
        this.sequence = sequence;
        this.occurredAt = occurredAt;
        this.recordedAt = recordedAt;
        this.source = source;
        this.actor = actor;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.detail = detail;
        this.recordedBy = recordedBy;
        this.previousHash = previousHash;
        this.hash = hash;
    }

    /**
     * Static factory for the append (BIAN Behavior Qualifier: {@code initiate}). The UUID comes from the
     * Hash Token Registry; {@code sequence} and {@code previousHash} come from the current head of the
     * tenant's chain (position 1 and {@link #GENESIS_HASH} for an empty chain).
     */
    public static LedgerEntry createNew(UUID id, UUID organisationId, long sequence, Instant occurredAt, String source, String actor,
                                        String action, String resourceType, String resourceId, String detail, String recordedBy,
                                        String previousHash) {
        if (id == null || organisationId == null || occurredAt == null) {
            throw new IllegalArgumentException("ID, Organisation ID and the occurrence instant are mandatory for LedgerEntry creation.");
        }
        if (sequence < 1) {
            throw new IllegalArgumentException("The chain position starts at 1.");
        }
        requireText(source, "Source");
        requireText(actor, "Actor");
        requireText(action, "Action");
        requireText(resourceType, "Resource type");
        requireText(recordedBy, "Executor");
        requireMax(resourceId, "Resource id", MAX_FIELD_LENGTH);
        requireMax(detail, "Detail", MAX_DETAIL_LENGTH);
        SensitiveDataGuard.assertClean("Source", source);
        SensitiveDataGuard.assertClean("Actor", actor);
        SensitiveDataGuard.assertClean("Action", action);
        SensitiveDataGuard.assertClean("Resource type", resourceType);
        SensitiveDataGuard.assertClean("Resource id", resourceId);
        SensitiveDataGuard.assertClean("Detail", detail);
        SensitiveDataGuard.assertClean("Executor", recordedBy);
        if (previousHash == null || previousHash.length() != 64) {
            throw new IllegalArgumentException("previousHash must be a 64-character hex digest (use GENESIS_HASH for the first entry).");
        }
        Instant occurred = occurredAt.truncatedTo(ChronoUnit.MILLIS);
        String hash = computeHash(organisationId, sequence, occurred, source, actor, action, resourceType, resourceId, detail,
                recordedBy, previousHash);
        return new LedgerEntry(id, organisationId, sequence, occurred, Instant.now().truncatedTo(ChronoUnit.MILLIS), source, actor,
                action, resourceType, resourceId, detail, recordedBy, previousHash, hash);
    }

    /** Reconstitutes an entry exactly as stored - without recomputing (or trusting) its hash. */
    public static LedgerEntry reconstitute(UUID id, UUID organisationId, long sequence, Instant occurredAt, Instant recordedAt,
                                           String source, String actor, String action, String resourceType, String resourceId,
                                           String detail, String recordedBy, String previousHash, String hash) {
        if (id == null || organisationId == null || occurredAt == null || previousHash == null || hash == null) {
            throw new IllegalArgumentException("ID, Organisation ID, the occurrence instant and both hashes are mandatory to reconstitute a LedgerEntry.");
        }
        return new LedgerEntry(id, organisationId, sequence, occurredAt, recordedAt, source, actor, action, resourceType, resourceId,
                detail, recordedBy, previousHash, hash);
    }

    /** True when the stored hash still matches the content: nothing in this entry was altered after it was written. */
    public boolean isIntact() {
        return hash.equals(computeHash(organisationId, sequence, occurredAt, source, actor, action, resourceType, resourceId, detail,
                recordedBy, previousHash));
    }

    /**
     * SHA-256 over an unambiguous encoding of every committed field (each as {@code length:value}, {@code -} for
     * null), so no two different entries can serialise to the same string.
     */
    static String computeHash(UUID organisationId, long sequence, Instant occurredAt, String source, String actor, String action,
                              String resourceType, String resourceId, String detail, String recordedBy, String previousHash) {
        StringBuilder canonical = new StringBuilder();
        for (String field : new String[]{organisationId.toString(), Long.toString(sequence), occurredAt.toString(), source, actor,
                action, resourceType, resourceId, detail, recordedBy, previousHash}) {
            canonical.append(field == null ? "-" : field.length() + ":" + field).append('|');
        }
        return digestHex("SHA-256", canonical.toString());
    }

    /** Hex digest of {@code text}; the algorithm is a parameter only so the impossible failure path is testable. */
    static String digestHex(String algorithm, String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " is required by the JVM specification.", e);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is mandatory for LedgerEntry creation.");
        }
        requireMax(value, name, MAX_FIELD_LENGTH);
    }

    private static void requireMax(String value, String name, int max) {
        if (value != null && value.length() > max) {
            throw new IllegalArgumentException(name + " must not exceed " + max + " characters.");
        }
    }

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public long getSequence() { return sequence; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getRecordedAt() { return recordedAt; }
    public String getSource() { return source; }
    public String getActor() { return actor; }
    public String getAction() { return action; }
    public String getResourceType() { return resourceType; }
    public String getResourceId() { return resourceId; }
    public String getDetail() { return detail; }
    public String getRecordedBy() { return recordedBy; }
    public String getPreviousHash() { return previousHash; }
    public String getHash() { return hash; }

    @Override
    public boolean equals(Object o) {
        return o instanceof LedgerEntry other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
