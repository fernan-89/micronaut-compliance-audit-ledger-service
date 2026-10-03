package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.LedgerEntryResponse;
import com.thinklab.application.mapper.LedgerEntryMapper;
import com.thinklab.domain.repository.LedgerEntryRepository;
import com.thinklab.domain.repository.LedgerEntryRepository.Filter;
import jakarta.inject.Singleton;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.UUID;

/**
 * Lists a tenant's entries, newest first (BIAN Behavior Qualifier: {@code retrieve}, collection). The page is
 * always bounded: {@code limit} defaults to {@value #DEFAULT_LIMIT} and is clamped to 1..{@value #MAX_LIMIT}, since a
 * ledger only grows.
 */
@Singleton
public class RetrieveLedgerEntriesUseCase {

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 500;

    private final LedgerEntryRepository repository;

    public RetrieveLedgerEntriesUseCase(LedgerEntryRepository repository) {
        this.repository = repository;
    }

    public Flux<LedgerEntryResponse> execute(UUID organisationId, String actor, String action, String resourceType, String resourceId,
                                             Instant from, Instant to, Integer limit) {
        int bounded = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        return repository.search(organisationId, new Filter(actor, action, resourceType, resourceId, from, to), bounded)
                .map(LedgerEntryMapper::toResponse);
    }
}
