package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.thinklab.domain.exception.ChainConflictException;
import com.thinklab.domain.model.LedgerEntry;
import com.thinklab.domain.repository.LedgerEntryRepository;
import com.thinklab.infrastructure.adapter.out.persistence.entity.LedgerEntryDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.LedgerEntryDocument.LedgerPersistenceMapper;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * MongoDB Reactive adapter for {@link LedgerEntryRepository}. Insert-only: the chain position is guarded by the
 * unique index {@code (organisationId, sequence)}, which turns a lost race into {@link ChainConflictException}.
 */
@Singleton
public class LedgerEntryMongoRepositoryAdapter implements LedgerEntryRepository {

    private static final Logger log = LoggerFactory.getLogger(LedgerEntryMongoRepositoryAdapter.class);
    private static final String FIELD_ID = "_id";
    private static final String FIELD_ORGANISATION = "organisationId";
    private static final String FIELD_SEQUENCE = "sequence";
    private static final String FIELD_OCCURRED_AT = "occurredAt";

    private final MongoClient mongoClient;
    private final String database;

    public LedgerEntryMongoRepositoryAdapter(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri) {
        this.mongoClient = mongoClient;
        this.database = MongoSupport.database(mongoUri);
    }

    private MongoCollection<LedgerEntryDocument> getCollection() {
        return mongoClient.getDatabase(database)
                .getCollection(MongoSupport.ENTRIES_COLLECTION, LedgerEntryDocument.class)
                .withCodecRegistry(MongoSupport.POJO_CODEC_REGISTRY);
    }

    @Override
    public Mono<LedgerEntry> append(LedgerEntry entry) {
        log.debug("[PERSISTENCE] Appending ledger entry {} at position {} of organisation {}", entry.getId(), entry.getSequence(), entry.getOrganisationId());
        return Mono.from(getCollection().insertOne(LedgerPersistenceMapper.toDocument(entry)))
                .map(result -> entry)
                .onErrorMap(LedgerEntryMongoRepositoryAdapter::isChainPositionTaken, e -> new ChainConflictException(String.format(
                        "Position %d of the ledger of organisation [%s] was taken by a concurrent writer; retry.",
                        entry.getSequence(), entry.getOrganisationId())));
    }

    private static boolean isChainPositionTaken(Throwable error) {
        return error instanceof MongoWriteException write
                && write.getError().getCategory() == ErrorCategory.DUPLICATE_KEY
                && write.getError().getMessage().contains(LedgerIndexInitializer.CHAIN_INDEX);
    }

    @Override
    public Mono<LedgerEntry> findLatest(UUID organisationId) {
        return Mono.from(getCollection().find(Filters.eq(FIELD_ORGANISATION, organisationId)).sort(Sorts.descending(FIELD_SEQUENCE)).first())
                .map(LedgerPersistenceMapper::toDomain);
    }

    @Override
    public Mono<LedgerEntry> findById(UUID id) {
        return Mono.from(getCollection().find(Filters.eq(FIELD_ID, id)).first()).map(LedgerPersistenceMapper::toDomain);
    }

    @Override
    public Flux<LedgerEntry> search(UUID organisationId, Filter filter, int limit) {
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq(FIELD_ORGANISATION, organisationId));
        if (filter.actor() != null) {
            filters.add(Filters.eq("actor", filter.actor()));
        }
        if (filter.action() != null) {
            filters.add(Filters.eq("action", filter.action()));
        }
        if (filter.resourceType() != null) {
            filters.add(Filters.eq("resourceType", filter.resourceType()));
        }
        if (filter.resourceId() != null) {
            filters.add(Filters.eq("resourceId", filter.resourceId()));
        }
        if (filter.from() != null) {
            filters.add(Filters.gte(FIELD_OCCURRED_AT, filter.from()));
        }
        if (filter.to() != null) {
            filters.add(Filters.lt(FIELD_OCCURRED_AT, filter.to()));
        }
        return Flux.from(getCollection().find(Filters.and(filters)).sort(Sorts.descending(FIELD_SEQUENCE)).limit(limit))
                .map(LedgerPersistenceMapper::toDomain);
    }

    @Override
    public Flux<LedgerEntry> streamChainAfter(UUID organisationId, long sequence) {
        return Flux.from(getCollection().find(Filters.and(Filters.eq(FIELD_ORGANISATION, organisationId), Filters.gt(FIELD_SEQUENCE, sequence)))
                        .sort(Sorts.ascending(FIELD_SEQUENCE)))
                .map(LedgerPersistenceMapper::toDomain);
    }

    @Override
    public Flux<UUID> findOrganisationIds() {
        return Flux.from(getCollection().distinct(FIELD_ORGANISATION, UUID.class));
    }
}
