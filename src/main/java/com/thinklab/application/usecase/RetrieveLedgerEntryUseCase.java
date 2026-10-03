package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.LedgerEntryResponse;
import com.thinklab.application.mapper.LedgerEntryMapper;
import com.thinklab.domain.exception.LedgerEntryNotFoundException;
import com.thinklab.domain.repository.LedgerEntryRepository;
import jakarta.inject.Singleton;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Retrieves one ledger entry by its sovereign id (BIAN Behavior Qualifier: {@code retrieve}). Tenant-scoped: another
 * tenant's entry answers exactly like a missing one, since the ledger holds the most sensitive records on the platform.
 */
@Singleton
public class RetrieveLedgerEntryUseCase {

    private final LedgerEntryRepository repository;

    public RetrieveLedgerEntryUseCase(LedgerEntryRepository repository) {
        this.repository = repository;
    }

    public Mono<LedgerEntryResponse> execute(UUID organisationId, UUID id) {
        return repository.findById(id)
                .filter(entry -> entry.getOrganisationId().equals(organisationId))
                .switchIfEmpty(Mono.error(new LedgerEntryNotFoundException(id)))
                .map(LedgerEntryMapper::toResponse);
    }
}
