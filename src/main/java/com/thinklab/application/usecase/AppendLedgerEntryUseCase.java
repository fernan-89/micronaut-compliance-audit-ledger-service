package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.AppendLedgerEntryRequest;
import com.thinklab.application.dto.response.LedgerEntryResponse;
import com.thinklab.application.mapper.LedgerEntryMapper;
import com.thinklab.domain.exception.ChainConflictException;
import com.thinklab.domain.model.LedgerEntry;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.LedgerEntryRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Orchestrates an append to a tenant's chain (BIAN Behavior Qualifier: {@code initiate}, ADR-030).
 *
 * <p>Flow: validate the request into a throw-away entry (fails fast with 400, before any I/O), obtain the
 * Sovereign ID once, then loop read-head -> build -> insert. The insert is guarded by a unique index on
 * {@code (organisationId, sequence)}, so two writers racing for the same position cannot both win: the loser
 * gets {@link ChainConflictException}, re-reads the new head and tries again, a bounded number of times.
 */
@Singleton
public class AppendLedgerEntryUseCase {

    private static final Logger log = LoggerFactory.getLogger(AppendLedgerEntryUseCase.class);

    static final int MAX_ATTEMPTS = 8;
    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    private final HashServicePort hashServicePort;
    private final LedgerEntryRepository repository;

    public AppendLedgerEntryUseCase(HashServicePort hashServicePort, LedgerEntryRepository repository) {
        this.hashServicePort = hashServicePort;
        this.repository = repository;
    }

    public Mono<LedgerEntryResponse> execute(UUID organisationId, AppendLedgerEntryRequest request, String executor) {
        log.info("[USE CASE] Appending ledger entry for organisation: {} action: {} resource: {}/{}",
                organisationId, request.action(), request.resourceType(), request.resourceId());

        Instant now = Instant.now();
        Instant occurredAt = request.occurredAt() == null ? now : request.occurredAt();
        if (occurredAt.isAfter(now.plus(MAX_CLOCK_SKEW))) {
            throw new IllegalArgumentException("An entry cannot be dated in the future (occurredAt " + occurredAt + ").");
        }
        // Validates every invariant up-front (IllegalArgumentException -> 400) without any I/O.
        build(UUID.randomUUID(), organisationId, 1, LedgerEntry.GENESIS_HASH, request, occurredAt, executor);

        return Mono.defer(() -> hashServicePort.generateSovereignId("ledger-entry-creation"))
                .flatMap(sovereignId -> Mono.defer(() -> repository.findLatest(organisationId)
                                .map(head -> build(sovereignId, organisationId, head.getSequence() + 1, head.getHash(), request, occurredAt, executor))
                                .switchIfEmpty(Mono.fromSupplier(() -> build(sovereignId, organisationId, 1, LedgerEntry.GENESIS_HASH, request, occurredAt, executor)))
                                .flatMap(repository::append))
                        .retryWhen(Retry.max(MAX_ATTEMPTS - 1).filter(ChainConflictException.class::isInstance)
                                .onRetryExhaustedThrow((spec, signal) -> signal.failure())))
                .map(LedgerEntryMapper::toResponse);
    }

    private static LedgerEntry build(UUID id, UUID organisationId, long sequence, String previousHash, AppendLedgerEntryRequest request,
                                     Instant occurredAt, String executor) {
        return LedgerEntry.createNew(id, organisationId, sequence, occurredAt, request.source(), request.actor(), request.action(),
                request.resourceType(), request.resourceId(), request.detail(), executor, previousHash);
    }

}
