package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.MongoClientSettings;
import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.reactivestreams.client.FindPublisher;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import com.thinklab.domain.exception.ChainConflictException;
import com.thinklab.domain.model.LedgerEntry;
import com.thinklab.domain.repository.LedgerEntryRepository.Filter;
import com.thinklab.infrastructure.adapter.out.persistence.entity.LedgerEntryDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.LedgerEntryDocument.LedgerPersistenceMapper;
import org.bson.BsonDocument;
import org.bson.BsonObjectId;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class LedgerEntryMongoRepositoryAdapterTest {

    private static final CodecRegistry REGISTRY = CodecRegistries.withUuidRepresentation(
            CodecRegistries.fromRegistries(MongoClientSettings.getDefaultCodecRegistry(),
                    CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build())),
            org.bson.UuidRepresentation.STANDARD);

    @Mock private MongoClient mongoClient;
    @Mock private MongoDatabase mongoDatabase;
    @Mock private MongoCollection<LedgerEntryDocument> mongoCollection;

    private LedgerEntryMongoRepositoryAdapter adapter;
    private UUID organisationId;
    private LedgerEntry entry;

    @BeforeEach
    void setUp() {
        when(mongoClient.getDatabase("ledger_db")).thenReturn(mongoDatabase);
        when(mongoDatabase.getCollection("ledger_entries", LedgerEntryDocument.class)).thenReturn(mongoCollection);
        when(mongoCollection.withCodecRegistry(any())).thenReturn(mongoCollection);
        adapter = new LedgerEntryMongoRepositoryAdapter(mongoClient, "mongodb://localhost:27017/ledger_db");
        organisationId = UUID.randomUUID();
        entry = LedgerEntry.createNew(UUID.randomUUID(), organisationId, 1, Instant.parse("2026-10-03T12:00:00Z"), "gw", "alice", "A", "t", "r", "d", "gw",
                LedgerEntry.GENESIS_HASH);
    }

    private static BsonDocument render(Bson bson) {
        return bson.toBsonDocument(BsonDocument.class, REGISTRY);
    }

    private static MongoWriteException writeError(int code, String message) {
        return new MongoWriteException(new WriteError(code, message, new BsonDocument()), new ServerAddress());
    }

    /** A FindPublisher whose chained sort/limit return itself and which streams the given documents. */
    private FindPublisher<LedgerEntryDocument> findReturning(Flux<LedgerEntryDocument> documents) {
        FindPublisher<LedgerEntryDocument> publisher = mock(FindPublisher.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(publisher);
        when(publisher.sort(any())).thenReturn(publisher);
        org.mockito.Mockito.lenient().when(publisher.limit(anyInt())).thenReturn(publisher);
        doAnswer(invocation -> {
            org.reactivestreams.Subscriber<LedgerEntryDocument> subscriber = invocation.getArgument(0);
            documents.subscribe(subscriber);
            return null;
        }).when(publisher).subscribe(any());
        return publisher;
    }

    @Test
    @DisplayName("append inserts the mapped document, hash included, and emits the entry")
    void appendSuccess() {
        when(mongoCollection.insertOne(any(LedgerEntryDocument.class))).thenReturn(Mono.just(InsertOneResult.acknowledged(new BsonObjectId(new ObjectId()))));

        StepVerifier.create(adapter.append(entry)).expectNext(entry).verifyComplete();

        ArgumentCaptor<LedgerEntryDocument> captor = ArgumentCaptor.forClass(LedgerEntryDocument.class);
        verify(mongoCollection).insertOne(captor.capture());
        assertEquals(entry.getHash(), captor.getValue().getHash());
        assertEquals(1, captor.getValue().getSequence());
    }

    @Test
    @DisplayName("append maps a duplicate on the chain index to ChainConflictException")
    void appendLostTheRace() {
        when(mongoCollection.insertOne(any(LedgerEntryDocument.class))).thenReturn(Mono.error(writeError(11000,
                "E11000 duplicate key error index: organisationId_1_sequence_1 dup key")));

        StepVerifier.create(adapter.append(entry)).expectError(ChainConflictException.class).verify();
    }

    @Test
    @DisplayName("append propagates other write errors, including a duplicate on another index")
    void appendOtherErrors() {
        MongoWriteException duplicateId = writeError(11000, "E11000 duplicate key error index: _id_ dup key");
        MongoWriteException validation = writeError(121, "Document failed validation index: organisationId_1_sequence_1");
        IllegalStateException down = new IllegalStateException("mongo down");
        when(mongoCollection.insertOne(any(LedgerEntryDocument.class))).thenReturn(Mono.error(duplicateId)).thenReturn(Mono.error(validation)).thenReturn(Mono.error(down));

        StepVerifier.create(adapter.append(entry)).expectErrorMatches(e -> e == duplicateId).verify();
        StepVerifier.create(adapter.append(entry)).expectErrorMatches(e -> e == validation).verify();
        StepVerifier.create(adapter.append(entry)).expectErrorMatches(e -> e == down).verify();
    }

    @Test
    @DisplayName("findLatest asks for the tenant's highest sequence, or completes empty")
    void findLatest() {
        FindPublisher<LedgerEntryDocument> publisher = mock(FindPublisher.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(publisher);
        when(publisher.sort(any())).thenReturn(publisher);
        when(publisher.first()).thenReturn(Mono.just(LedgerPersistenceMapper.toDocument(entry))).thenReturn(Mono.empty());

        StepVerifier.create(adapter.findLatest(organisationId)).expectNextMatches(e -> e.getHash().equals(entry.getHash())).verifyComplete();
        StepVerifier.create(adapter.findLatest(organisationId)).verifyComplete();

        ArgumentCaptor<Bson> sort = ArgumentCaptor.forClass(Bson.class);
        verify(publisher, org.mockito.Mockito.times(2)).sort(sort.capture());
        assertEquals(-1, render(sort.getValue()).getInt32("sequence").getValue());
    }

    @Test
    @DisplayName("findById maps the document back, or completes empty")
    void findById() {
        FindPublisher<LedgerEntryDocument> publisher = mock(FindPublisher.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(publisher);
        when(publisher.first()).thenReturn(Mono.just(LedgerPersistenceMapper.toDocument(entry))).thenReturn(Mono.empty());

        StepVerifier.create(adapter.findById(entry.getId())).expectNextMatches(e -> e.getId().equals(entry.getId())).verifyComplete();
        StepVerifier.create(adapter.findById(entry.getId())).verifyComplete();
    }

    @Test
    @DisplayName("search always filters by tenant, adds every given filter, sorts newest first and applies the limit")
    void searchWithEveryFilter() {
        FindPublisher<LedgerEntryDocument> publisher = findReturning(Flux.just(LedgerPersistenceMapper.toDocument(entry)));
        Instant from = Instant.parse("2026-10-01T00:00:00Z");
        Instant to = Instant.parse("2026-10-04T00:00:00Z");

        StepVerifier.create(adapter.search(organisationId, new Filter("alice", "A", "t", "r", from, to), 25)).expectNextCount(1).verifyComplete();

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(mongoCollection).find(filter.capture());
        String rendered = render(filter.getValue()).toJson();
        assertTrue(rendered.contains("organisationId") && rendered.contains("alice") && rendered.contains("\"action\"") && rendered.contains("resourceType")
                && rendered.contains("resourceId") && rendered.contains("$gte") && rendered.contains("$lt"), rendered);
        verify(publisher).limit(25);
    }

    @Test
    @DisplayName("search without optional filters only constrains the tenant")
    void searchTenantOnly() {
        findReturning(Flux.empty());

        StepVerifier.create(adapter.search(organisationId, new Filter(null, null, null, null, null, null), 100)).verifyComplete();

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(mongoCollection).find(filter.capture());
        String rendered = render(filter.getValue()).toJson();
        assertTrue(rendered.contains("organisationId") && !rendered.contains("actor") && !rendered.contains("occurredAt"), rendered);
    }

    @Test
    @DisplayName("streamChain reads the tenant's chain in ascending sequence order")
    void streamChain() {
        FindPublisher<LedgerEntryDocument> publisher = findReturning(Flux.just(LedgerPersistenceMapper.toDocument(entry)));

        StepVerifier.create(adapter.streamChain(organisationId)).expectNextCount(1).verifyComplete();

        ArgumentCaptor<Bson> sort = ArgumentCaptor.forClass(Bson.class);
        verify(publisher).sort(sort.capture());
        assertEquals(1, render(sort.getValue()).getInt32("sequence").getValue());
    }

}
