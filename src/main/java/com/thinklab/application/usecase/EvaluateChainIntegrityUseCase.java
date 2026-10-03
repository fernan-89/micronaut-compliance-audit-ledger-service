package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.ChainIntegrityResponse;
import com.thinklab.application.mapper.LedgerEntryMapper;
import com.thinklab.domain.model.ChainVerifier;
import com.thinklab.domain.repository.LedgerEntryRepository;
import jakarta.inject.Singleton;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Walks a tenant's whole chain and reports whether it is intact (BIAN Behavior Qualifier:
 * {@code integrity-check/evaluate}, ADR-031). The chain is streamed in sequence order and folded into a
 * {@link ChainVerifier}, so memory stays flat however long the ledger is; the cost is linear in its length.
 */
@Singleton
public class EvaluateChainIntegrityUseCase {

    private final LedgerEntryRepository repository;

    public EvaluateChainIntegrityUseCase(LedgerEntryRepository repository) {
        this.repository = repository;
    }

    public Mono<ChainIntegrityResponse> execute(UUID organisationId) {
        return Mono.defer(() -> {
            ChainVerifier verifier = new ChainVerifier();
            return repository.streamChain(organisationId)
                    .doOnNext(verifier::accept)
                    .then(Mono.fromSupplier(verifier::result));
        }).map(LedgerEntryMapper::toResponse);
    }
}
