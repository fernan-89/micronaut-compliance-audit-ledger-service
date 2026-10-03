package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.MongoTimeoutException;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.reactivestreams.client.MongoClient;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Objects;

/**
 * Creates, at startup, the unique chain index {@code (organisationId, sequence)} - the atomic guard that makes a fork
 * of a tenant's chain impossible (ADR-030) - and the lookup index for "everything that happened to this resource".
 * Fail-open like the kit's initializer: errors are logged and the application still starts.
 */
@Singleton
@Requires(property = "thinklab.mongo.create-indexes", notEquals = "false")
public class LedgerIndexInitializer implements ApplicationEventListener<StartupEvent> {

    static final String CHAIN_INDEX = "organisationId_1_sequence_1";
    static final String RESOURCE_INDEX = "organisationId_1_resourceType_1_resourceId_1";

    private static final Logger log = LoggerFactory.getLogger(LedgerIndexInitializer.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final MongoClient mongoClient;
    private final String database;
    private final Duration timeout;

    @Inject
    public LedgerIndexInitializer(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri) {
        this(mongoClient, mongoUri, TIMEOUT);
    }

    /** Test seam: how long to wait for the server. */
    LedgerIndexInitializer(MongoClient mongoClient, String mongoUri, Duration timeout) {
        this.mongoClient = Objects.requireNonNull(mongoClient, "Infrastructure constraint violated: MongoClient cannot be null.");
        this.database = MongoSupport.database(mongoUri);
        this.timeout = timeout;
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        Objects.requireNonNull(event, "Application constraint violated: StartupEvent cannot be null.");
        ensure(new Document("organisationId", 1).append("sequence", 1), CHAIN_INDEX, true);
        ensure(new Document("organisationId", 1).append("resourceType", 1).append("resourceId", 1), RESOURCE_INDEX, false);
    }

    private void ensure(Document keys, String name, boolean unique) {
        try {
            Mono.from(mongoClient.getDatabase(database).getCollection(MongoSupport.ENTRIES_COLLECTION)
                    .createIndex(keys, new IndexOptions().unique(unique).name(name))).block(timeout);
            log.info("[MONGO_INDEXES] Ensured index [{}] on [{}.{}]", name, database, MongoSupport.ENTRIES_COLLECTION);
        } catch (MongoTimeoutException e) {
            log.error("[MONGO_INDEXES] MongoDB unreachable; index [{}] was not created. Reason: {}", name, e.getMessage());
        } catch (RuntimeException e) {
            log.error("[MONGO_INDEXES] Could not create index [{}] on [{}.{}]: {}", name, database, MongoSupport.ENTRIES_COLLECTION, e.getMessage());
        }
    }
}
