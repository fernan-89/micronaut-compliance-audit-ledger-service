package com.thinklab.infrastructure.adapter.out.persistence.entity;

import com.thinklab.domain.model.LedgerEntry;
import io.micronaut.core.annotation.Introspected;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.UUID;

/** Infrastructure-specific MongoDB representation of a LedgerEntry (keeps the domain annotation-free). */
@Introspected
public class LedgerEntryDocument {

    @BsonId
    private UUID id;
    private UUID organisationId;
    private long sequence;
    private Instant occurredAt;
    private Instant recordedAt;
    private String source;
    private String actor;
    private String action;
    private String resourceType;
    private String resourceId;
    private String detail;
    private String recordedBy;
    private String previousHash;
    private String hash;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrganisationId() { return organisationId; }
    public void setOrganisationId(UUID organisationId) { this.organisationId = organisationId; }
    public long getSequence() { return sequence; }
    public void setSequence(long sequence) { this.sequence = sequence; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
    public Instant getRecordedAt() { return recordedAt; }
    public void setRecordedAt(Instant recordedAt) { this.recordedAt = recordedAt; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }
    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public String getRecordedBy() { return recordedBy; }
    public void setRecordedBy(String recordedBy) { this.recordedBy = recordedBy; }
    public String getPreviousHash() { return previousHash; }
    public void setPreviousHash(String previousHash) { this.previousHash = previousHash; }
    public String getHash() { return hash; }
    public void setHash(String hash) { this.hash = hash; }

    public static final class LedgerPersistenceMapper {

        private LedgerPersistenceMapper() { throw new UnsupportedOperationException(); }

        public static LedgerEntryDocument toDocument(LedgerEntry entry) {
            LedgerEntryDocument doc = new LedgerEntryDocument();
            doc.setId(entry.getId());
            doc.setOrganisationId(entry.getOrganisationId());
            doc.setSequence(entry.getSequence());
            doc.setOccurredAt(entry.getOccurredAt());
            doc.setRecordedAt(entry.getRecordedAt());
            doc.setSource(entry.getSource());
            doc.setActor(entry.getActor());
            doc.setAction(entry.getAction());
            doc.setResourceType(entry.getResourceType());
            doc.setResourceId(entry.getResourceId());
            doc.setDetail(entry.getDetail());
            doc.setRecordedBy(entry.getRecordedBy());
            doc.setPreviousHash(entry.getPreviousHash());
            doc.setHash(entry.getHash());
            return doc;
        }

        /** Reads the entry exactly as stored: the hash is NOT recomputed, so tampering stays visible to the verifier. */
        public static LedgerEntry toDomain(LedgerEntryDocument doc) {
            return LedgerEntry.reconstitute(doc.getId(), doc.getOrganisationId(), doc.getSequence(), doc.getOccurredAt(),
                    doc.getRecordedAt(), doc.getSource(), doc.getActor(), doc.getAction(), doc.getResourceType(), doc.getResourceId(),
                    doc.getDetail(), doc.getRecordedBy(), doc.getPreviousHash(), doc.getHash());
        }
    }
}
