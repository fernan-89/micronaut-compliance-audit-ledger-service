package com.thinklab.domain.repository;

import com.thinklab.domain.model.LedgerEntry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbound Port for the append-only ledger. There is deliberately no update and no delete: an entry, once
 * written, only ever changes by someone tampering with the store, and the chain exists to detect that.
 */
public interface LedgerEntryRepository {

    /**
     * Writes the entry at its chain position. Fails with
     * {@link com.thinklab.domain.exception.ChainConflictException} when that position of the tenant's chain
     * is already taken (a concurrent writer won the race).
     */
    Mono<LedgerEntry> append(LedgerEntry entry);

    /** The entry with the highest sequence of the tenant's chain, or empty for a tenant with no entries yet. */
    Mono<LedgerEntry> findLatest(UUID organisationId);

    Mono<LedgerEntry> findById(UUID id);

    /** Newest first. Every filter is optional; {@code limit} is applied by the store. */
    Flux<LedgerEntry> search(UUID organisationId, Filter filter, int limit);

    /** The whole chain of a tenant in ascending sequence order, for verification. */
    default Flux<LedgerEntry> streamChain(UUID organisationId) {
        return streamChainAfter(organisationId, 0);
    }

    /** The entries of a tenant's chain after {@code sequence}, ascending: verification from a trusted checkpoint (ADR-034). */
    Flux<LedgerEntry> streamChainAfter(UUID organisationId, long sequence);

    /** Every tenant that has at least one entry (the anchoring job walks these). */
    Flux<UUID> findOrganisationIds();

    record Filter(String actor, String action, String resourceType, String resourceId, Instant from, Instant to) {}
}
