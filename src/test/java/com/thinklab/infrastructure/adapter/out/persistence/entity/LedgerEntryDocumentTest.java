package com.thinklab.infrastructure.adapter.out.persistence.entity;

import com.thinklab.domain.model.LedgerEntry;
import com.thinklab.infrastructure.adapter.out.persistence.entity.LedgerEntryDocument.LedgerPersistenceMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerEntryDocumentTest {

    @Test
    @DisplayName("an entry survives the round trip field for field, hash included, and is still intact")
    void roundTrip() {
        UUID org = UUID.randomUUID();
        LedgerEntry entry = LedgerEntry.createNew(UUID.randomUUID(), org, 4, Instant.parse("2026-10-03T12:00:00.321Z"), "gw", "alice", "A", "t", "r", "d",
                "gateway", "f".repeat(64));

        LedgerEntryDocument doc = LedgerPersistenceMapper.toDocument(entry);
        LedgerEntry back = LedgerPersistenceMapper.toDomain(doc);

        assertEquals(entry.getId(), doc.getId());
        assertEquals(org, doc.getOrganisationId());
        assertEquals(4, doc.getSequence());
        assertEquals(entry.getOccurredAt(), doc.getOccurredAt());
        assertEquals(entry.getRecordedAt(), doc.getRecordedAt());
        assertEquals("gw", doc.getSource());
        assertEquals("alice", doc.getActor());
        assertEquals("A", doc.getAction());
        assertEquals("t", doc.getResourceType());
        assertEquals("r", doc.getResourceId());
        assertEquals("d", doc.getDetail());
        assertEquals("gateway", doc.getRecordedBy());
        assertEquals("f".repeat(64), doc.getPreviousHash());
        assertEquals(entry.getHash(), doc.getHash());
        assertEquals(entry.getHash(), back.getHash());
        assertTrue(back.isIntact());
    }

    @Test
    @DisplayName("a document edited in the store is read back as stored, so the entry reports itself as altered")
    void tamperedDocumentStaysTampered() {
        LedgerEntry entry = LedgerEntry.createNew(UUID.randomUUID(), UUID.randomUUID(), 1, Instant.parse("2026-10-03T12:00:00Z"), "gw", "alice", "A", "t",
                null, null, "gateway", LedgerEntry.GENESIS_HASH);
        LedgerEntryDocument doc = LedgerPersistenceMapper.toDocument(entry);
        doc.setActor("mallory");

        assertFalse(LedgerPersistenceMapper.toDomain(doc).isIntact());
    }

    @Test
    @DisplayName("the mapper is a non-instantiable utility class")
    void mapperIsUtility() throws Exception {
        Constructor<LedgerPersistenceMapper> constructor = LedgerPersistenceMapper.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        InvocationTargetException failure = assertThrows(InvocationTargetException.class, constructor::newInstance);

        assertInstanceOf(UnsupportedOperationException.class, failure.getCause());
    }
}
