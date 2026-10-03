package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.MongoTimeoutException;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.reactivestreams.client.FindPublisher;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import com.thinklab.infrastructure.adapter.out.persistence.entity.LedgerEntryDocument;
import io.micronaut.context.event.StartupEvent;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class LedgerIndexInitializerTest {

    private final StartupEvent startup = mock(StartupEvent.class);

    @Test
    @DisplayName("startup creates the unique chain index and the resource lookup index with the exact key order")
    void createsIndexes() {
        MongoClient client = mock(MongoClient.class);
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> entries = mock(MongoCollection.class);
        when(client.getDatabase("tenant_ledger")).thenReturn(database);
        when(database.getCollection("ledger_entries")).thenReturn(entries);
        when(entries.createIndex(any(), any(IndexOptions.class))).thenReturn(Mono.just("ok"));

        new LedgerIndexInitializer(client, "mongodb://mongo:27017/tenant_ledger").onApplicationEvent(startup);

        ArgumentCaptor<Bson> keys = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<IndexOptions> options = ArgumentCaptor.forClass(IndexOptions.class);
        verify(entries, times(2)).createIndex(keys.capture(), options.capture());
        assertEquals(new Document("organisationId", 1).append("sequence", 1), keys.getAllValues().get(0));
        assertTrue(options.getAllValues().get(0).isUnique());
        assertEquals(LedgerIndexInitializer.CHAIN_INDEX, options.getAllValues().get(0).getName());
        assertEquals(new Document("organisationId", 1).append("resourceType", 1).append("resourceId", 1), keys.getAllValues().get(1));
        assertFalse(options.getAllValues().get(1).isUnique());
        assertEquals(LedgerIndexInitializer.RESOURCE_INDEX, options.getAllValues().get(1).getName());
    }

    @Test
    @DisplayName("fail-open: an unreachable server or a rejected index is logged, never propagated")
    void failOpen() {
        MongoClient client = mock(MongoClient.class);
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> collection = mock(MongoCollection.class);
        when(client.getDatabase("ledger_db")).thenReturn(database);
        when(database.getCollection(any())).thenReturn(collection);
        when(collection.createIndex(any(), any(IndexOptions.class)))
                .thenReturn(Mono.error(new MongoTimeoutException("no server")))
                .thenReturn(Mono.error(new IllegalStateException("E11000 existing duplicates")));
        LedgerIndexInitializer initializer = new LedgerIndexInitializer(client, "mongodb://mongo:27017/ledger_db", Duration.ofSeconds(1));

        assertDoesNotThrow(() -> initializer.onApplicationEvent(startup));
    }

    @Test
    @DisplayName("collaborators, mongodb.uri and the startup event are null-checked")
    void guards() {
        MongoClient client = mock(MongoClient.class);
        assertThrows(NullPointerException.class, () -> new LedgerIndexInitializer(null, "mongodb://mongo:27017/a"));
        assertThrows(NullPointerException.class, () -> new LedgerIndexInitializer(client, null));
        assertThrows(NullPointerException.class, () -> new LedgerIndexInitializer(client, "mongodb://mongo:27017/a").onApplicationEvent(null));
    }

    @Test
    @DisplayName("MongoSupport is a static utility holder (its private constructor is only there to stop instantiation)")
    void mongoSupportIsUtility() throws Exception {
        Constructor<MongoSupport> constructor = MongoSupport.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        assertInstanceOf(MongoSupport.class, constructor.newInstance());
    }

    @Test
    @DisplayName("the adapter reads the database named in mongodb.uri, falling back to the service default; the uri is mandatory")
    void databaseResolution() {
        MongoClient client = mock(MongoClient.class);
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<LedgerEntryDocument> collection = mock(MongoCollection.class);
        FindPublisher<LedgerEntryDocument> find = mock(FindPublisher.class);
        when(client.getDatabase("thinklab_compliance_audit_ledger_db")).thenReturn(database);
        when(database.getCollection("ledger_entries", LedgerEntryDocument.class)).thenReturn(collection);
        when(collection.withCodecRegistry(any())).thenReturn(collection);
        when(collection.find(any(Bson.class))).thenReturn(find);
        when(find.first()).thenReturn(Mono.empty());

        new LedgerEntryMongoRepositoryAdapter(client, "mongodb://mongo:27017").findById(UUID.randomUUID()).block();

        verify(client).getDatabase("thinklab_compliance_audit_ledger_db");
        assertThrows(NullPointerException.class, () -> new LedgerEntryMongoRepositoryAdapter(client, null));
    }
}
