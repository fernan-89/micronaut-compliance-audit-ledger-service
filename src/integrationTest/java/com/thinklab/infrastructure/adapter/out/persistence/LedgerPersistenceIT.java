package com.thinklab.infrastructure.adapter.out.persistence;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mongodb.reactivestreams.client.MongoClient;
import com.thinklab.application.dto.request.AppendLedgerEntryRequest;
import com.thinklab.application.dto.response.ChainIntegrityResponse;
import com.thinklab.application.dto.response.LedgerEntryResponse;
import com.thinklab.application.usecase.AppendLedgerEntryUseCase;
import com.thinklab.application.usecase.EvaluateChainIntegrityUseCase;
import com.thinklab.domain.exception.ChainConflictException;
import com.thinklab.domain.model.LedgerEntry;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.LedgerEntryRepository;
import com.thinklab.domain.repository.LedgerEntryRepository.Filter;
import com.thinklab.infrastructure.adapter.out.integration.hashservice.HashServiceAdapter;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ledger against a real MongoDB, proving what no mock can: that the unique chain index really makes a fork
 * impossible under concurrent writers, and that the verifier really catches an entry edited or removed straight in
 * the store (ADR-030/031).
 *
 * <p>{@code packages = "com.thinklab"}: otherwise Micronaut Data MongoDB stops mapping {@code @Id} to {@code _id}
 * for entities outside the test's own package (see party-authentication's integration tests).
 */
@MicronautTest(packages = "com.thinklab", transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LedgerPersistenceIT implements TestPropertyProvider {

    private static final String DATABASE = "it_compliance_audit_ledger_it";
    private static final String EXECUTOR = "gateway";

    /** The hash service is another process; the ledger only needs a fresh UUID from it. */
    @Singleton
    @Replaces(HashServiceAdapter.class)
    static class FixedHashService implements HashServicePort {
        @Override
        public Mono<UUID> generateSovereignId(String purpose) {
            return Mono.fromSupplier(UUID::randomUUID);
        }

        @Override
        public Mono<String> hashSensitiveData(String rawData) {
            return Mono.just(rawData);
        }
    }

    @Override
    public Map<String, String> getProperties() {
        return Map.of("mongodb.uri", MongoContainer.uri(DATABASE));
    }

    @Inject LedgerEntryRepository repository;
    @Inject AppendLedgerEntryUseCase append;
    @Inject EvaluateChainIntegrityUseCase integrity;
    @Inject MongoClient mongoClient;

    private LedgerEntryResponse appendOne(UUID organisation, String actor, String action, String resourceId) {
        return append.execute(organisation, new AppendLedgerEntryRequest("platform-gateway", actor, action, "it-asset-registry", resourceId, "status=204", null), EXECUTOR).block();
    }

    private ChainIntegrityResponse verify(UUID organisation) {
        return integrity.execute(organisation).block();
    }

    private void editInStore(UUID organisation, long sequence, org.bson.conversions.Bson update) {
        Flux.from(mongoClient.getDatabase(DATABASE).getCollection("ledger_entries")
                .updateOne(Filters.and(Filters.eq("organisationId", organisation), Filters.eq("sequence", sequence)), update)).blockLast();
    }

    @Test
    @DisplayName("appends form a gapless chain: positions 1..n, each linked to the hash before it, verified valid")
    void chainGrows() {
        UUID org = UUID.randomUUID();

        LedgerEntryResponse first = appendOne(org, "alice", "PUT control/ready", "a-1");
        LedgerEntryResponse second = appendOne(org, "bob", "PUT control/deploy", "a-1");
        LedgerEntryResponse third = appendOne(org, "alice", "POST initiate", "a-2");

        assertEquals(List.of(1L, 2L, 3L), List.of(first.sequence(), second.sequence(), third.sequence()));
        assertEquals(LedgerEntry.GENESIS_HASH, first.previousHash());
        assertEquals(first.hash(), second.previousHash());
        assertEquals(second.hash(), third.previousHash());
        ChainIntegrityResponse verdict = verify(org);
        assertTrue(verdict.valid());
        assertEquals(3, verdict.entriesChecked());
        assertEquals(third.hash(), verdict.headHash());
    }

    @Test
    @DisplayName("chains are per tenant: two tenants both start at position 1 and never see each other's entries")
    void tenantsAreIsolated() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();

        LedgerEntryResponse a = appendOne(orgA, "alice", "A", "r-1");
        LedgerEntryResponse b = appendOne(orgB, "bob", "A", "r-1");

        assertEquals(1, a.sequence());
        assertEquals(1, b.sequence());
        assertEquals(LedgerEntry.GENESIS_HASH, b.previousHash());
        assertEquals(1, repository.search(orgA, new Filter(null, null, null, null, null, null), 10).collectList().block().size());
        assertTrue(verify(orgA).valid() && verify(orgB).valid());
        assertEquals(0, verify(UUID.randomUUID()).entriesChecked());
    }

    @Test
    @DisplayName("concurrent writers can never fork the chain: whatever succeeds is gapless and verifies; the rest is a 409")
    void concurrentAppendsNeverFork() {
        UUID org = UUID.randomUUID();

        List<Object> outcomes = Flux.range(0, 24)
                .flatMap(i -> append.execute(org, new AppendLedgerEntryRequest("platform-gateway", "writer-" + i, "A", "t", "r-" + i, null, null), EXECUTOR)
                        .cast(Object.class)
                        .onErrorResume(ChainConflictException.class, e -> Mono.just(e)), 6)
                .collectList().block();

        List<LedgerEntryResponse> written = outcomes.stream().filter(LedgerEntryResponse.class::isInstance).map(LedgerEntryResponse.class::cast).toList();
        assertTrue(written.size() >= 1, "at least one writer must win");
        assertEquals(written.size(), written.stream().map(LedgerEntryResponse::sequence).collect(Collectors.toSet()).size(), "no position was handed out twice");
        assertEquals(Set.copyOf(java.util.stream.LongStream.rangeClosed(1, written.size()).boxed().toList()),
                written.stream().map(LedgerEntryResponse::sequence).collect(Collectors.toSet()), "positions are gapless");
        ChainIntegrityResponse verdict = verify(org);
        assertTrue(verdict.valid(), String.valueOf(verdict.reason()));
        assertEquals(written.size(), verdict.entriesChecked());
    }

    @Test
    @DisplayName("the unique chain index refuses a second entry at the same position with ChainConflictException")
    void chainIndexRejectsAFork() {
        UUID org = UUID.randomUUID();
        LedgerEntryResponse first = appendOne(org, "alice", "A", "r-1");

        LedgerEntry duplicatePosition = LedgerEntry.createNew(UUID.randomUUID(), org, first.sequence(), Instant.now(), "gw", "mallory", "A", "t", null, null, "gw",
                LedgerEntry.GENESIS_HASH);

        assertThrows(ChainConflictException.class, () -> repository.append(duplicatePosition).block());
    }

    @Test
    @DisplayName("an entry edited straight in the store is caught at its position, and the head stops just before it")
    void editedEntryIsCaught() {
        UUID org = UUID.randomUUID();
        appendOne(org, "alice", "A", "r-1");
        appendOne(org, "alice", "B", "r-2");
        LedgerEntryResponse third = appendOne(org, "alice", "C", "r-3");
        assertTrue(verify(org).valid());

        editInStore(org, 2, Updates.set("actor", "mallory"));

        ChainIntegrityResponse verdict = verify(org);
        assertFalse(verdict.valid());
        assertEquals(2L, verdict.firstBrokenSequence());
        assertEquals(1, verdict.headSequence());
        assertNotNull(verdict.reason());
        assertEquals(3, third.sequence());
    }

    @Test
    @DisplayName("an entry deleted from the store shows up as a gap")
    void deletedEntryIsCaught() {
        UUID org = UUID.randomUUID();
        appendOne(org, "alice", "A", "r-1");
        appendOne(org, "alice", "B", "r-2");
        appendOne(org, "alice", "C", "r-3");

        Flux.from(mongoClient.getDatabase(DATABASE).getCollection("ledger_entries")
                .deleteOne(Filters.and(Filters.eq("organisationId", org), Filters.eq("sequence", 2L)))).blockLast();

        ChainIntegrityResponse verdict = verify(org);
        assertFalse(verdict.valid());
        assertEquals(3L, verdict.firstBrokenSequence());
        assertTrue(verdict.reason().contains("missing"));
    }

    @Test
    @DisplayName("search filters by actor, action, resource and time, newest first, bounded by the limit")
    void searchFilters() {
        UUID org = UUID.randomUUID();
        appendOne(org, "alice", "PUT control/ready", "a-1");
        appendOne(org, "bob", "PUT control/deploy", "a-1");
        appendOne(org, "alice", "POST initiate", "a-2");

        assertEquals(List.of(3L, 1L), repository.search(org, new Filter("alice", null, null, null, null, null), 10).map(LedgerEntry::getSequence).collectList().block());
        assertEquals(List.of(2L, 1L), repository.search(org, new Filter(null, null, "it-asset-registry", "a-1", null, null), 10).map(LedgerEntry::getSequence).collectList().block());
        assertEquals(List.of(2L), repository.search(org, new Filter(null, "PUT control/deploy", null, null, null, null), 10).map(LedgerEntry::getSequence).collectList().block());
        assertEquals(List.of(3L, 2L), repository.search(org, new Filter(null, null, null, null, null, null), 2).map(LedgerEntry::getSequence).collectList().block());
        assertEquals(3, repository.search(org, new Filter(null, null, null, null, Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600)), 10).count().block());
        assertEquals(0, repository.search(org, new Filter(null, null, null, null, Instant.now().plusSeconds(3600), null), 10).count().block());
    }

    @Test
    @DisplayName("findById and findLatest read back exactly what was appended, hash included")
    void readBack() {
        UUID org = UUID.randomUUID();
        LedgerEntryResponse written = appendOne(org, "alice", "A", "r-1");

        LedgerEntry byId = repository.findById(written.id()).block();
        LedgerEntry latest = repository.findLatest(org).block();

        assertEquals(written.hash(), byId.getHash());
        assertTrue(byId.isIntact());
        assertEquals(byId.getId(), latest.getId());
        assertEquals(null, repository.findLatest(UUID.randomUUID()).block());
    }

    @Test
    @DisplayName("the declared indexes exist, the chain index being unique")
    void indexesExist() {
        appendOne(UUID.randomUUID(), "alice", "A", "r-1");

        List<Document> indexes = Flux.from(mongoClient.getDatabase(DATABASE).getCollection("ledger_entries").listIndexes()).collectList().block();
        Document chain = indexes.stream().filter(i -> "organisationId_1_sequence_1".equals(i.getString("name"))).findFirst().orElseThrow();

        assertEquals(Boolean.TRUE, chain.getBoolean("unique"));
        assertTrue(indexes.stream().anyMatch(i -> "organisationId_1_resourceType_1_resourceId_1".equals(i.getString("name"))));
    }
}
