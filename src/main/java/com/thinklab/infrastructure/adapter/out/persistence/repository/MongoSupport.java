package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;

import java.util.Objects;

/** Shared Mongo plumbing for the ledger adapter and its index initializer. */
final class MongoSupport {

    /** Used only when {@code mongodb.uri} names no database. */
    static final String DEFAULT_DATABASE = "thinklab_compliance_audit_ledger_db";
    static final String ENTRIES_COLLECTION = "ledger_entries";

    /** The driver's default registry has no POJO codec; without this every read/write fails. */
    static final CodecRegistry POJO_CODEC_REGISTRY = CodecRegistries.fromRegistries(
            MongoClientSettings.getDefaultCodecRegistry(),
            CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build()));

    private MongoSupport() { }

    static String database(String mongoUri) {
        String configured = new ConnectionString(Objects.requireNonNull(mongoUri, "mongodb.uri cannot be null.")).getDatabase();
        return configured != null ? configured : DEFAULT_DATABASE;
    }
}
