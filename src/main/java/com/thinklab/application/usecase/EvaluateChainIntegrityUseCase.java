package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.ChainIntegrityResponse;
import com.thinklab.application.mapper.LedgerEntryMapper;
import com.thinklab.domain.model.Anchor;
import com.thinklab.domain.model.AnchorSigner;
import com.thinklab.domain.model.ChainVerifier;
import com.thinklab.domain.port.AnchorPort;
import com.thinklab.domain.repository.LedgerEntryRepository;
import jakarta.inject.Singleton;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Walks a tenant's whole chain and reports whether it is intact (BIAN Behavior Qualifier:
 * {@code integrity-check/evaluate}, ADR-031) and, when anchors have been published, whether it still agrees with every one of
 * them (ADR-034) - the check that catches a chain rewritten with all its hashes recomputed, or truncated. The chain is streamed
 * in sequence order and folded into a {@link ChainVerifier}, so memory stays flat however long the ledger is; the cost is linear
 * in its length. Anchors that do not authenticate (wrong key, edited) are themselves reported as tampering.
 */
@Singleton
public class EvaluateChainIntegrityUseCase {

    private final LedgerEntryRepository repository;
    private final AnchorPort anchorPort;
    private final AnchorSigner signer;

    public EvaluateChainIntegrityUseCase(LedgerEntryRepository repository, AnchorPort anchorPort, AnchorSigner signer) {
        this.repository = repository;
        this.anchorPort = anchorPort;
        this.signer = signer;
    }

    public Mono<ChainIntegrityResponse> execute(UUID organisationId) {
        return Mono.defer(() -> anchorPort.read(organisationId).collectList().flatMap(anchors -> {
            List<Anchor> authentic = anchors.stream().filter(signer::isAuthentic).toList();
            ChainVerifier verifier = new ChainVerifier(authentic);
            return repository.streamChain(organisationId)
                    .doOnNext(verifier::accept)
                    .then(Mono.fromSupplier(() -> {
                        anchors.stream().filter(anchor -> !signer.isAuthentic(anchor)).findFirst().ifPresent(verifier::rejectAnchor);
                        return verifier.result();
                    }));
        })).map(LedgerEntryMapper::toResponse);
    }
}
