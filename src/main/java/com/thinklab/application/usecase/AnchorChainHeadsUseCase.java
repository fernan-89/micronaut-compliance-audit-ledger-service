package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.AnchorResponse;
import com.thinklab.application.dto.response.AnchorResultResponse;
import com.thinklab.domain.model.Anchor;
import com.thinklab.domain.model.AnchorSigner;
import com.thinklab.domain.model.ChainVerifier;
import com.thinklab.domain.model.ChainVerifier.ChainVerification;
import com.thinklab.domain.port.AnchorPort;
import com.thinklab.domain.repository.LedgerEntryRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Publishes the head of a tenant's chain outside the database (BIAN Behavior Qualifier: {@code anchor/initiate}, ADR-034).
 *
 * <p>It never seals a broken chain. Before anchoring it verifies everything written since the last anchor - starting from that
 * anchor's hash, which it first authenticates - and only a chain that links cleanly gets a new anchor. Otherwise the outcome is
 * {@code REFUSED} with the reason, and the attempt is logged at error level: publishing the head of a tampered chain would turn the
 * tampering into a signed fact. Nothing new since the last anchor is {@code UNCHANGED}.
 */
@Singleton
public class AnchorChainHeadsUseCase {

    private static final Logger log = LoggerFactory.getLogger(AnchorChainHeadsUseCase.class);

    private final LedgerEntryRepository repository;
    private final AnchorPort anchorPort;
    private final AnchorSigner signer;

    public AnchorChainHeadsUseCase(LedgerEntryRepository repository, AnchorPort anchorPort, AnchorSigner signer) {
        this.repository = repository;
        this.anchorPort = anchorPort;
        this.signer = signer;
    }

    /** One pass over every tenant that has entries; a failure for one tenant never stops the others. */
    public Flux<AnchorResultResponse> executeAll() {
        return repository.findOrganisationIds().concatMap(organisationId -> executeFor(organisationId)
                .doOnNext(result -> {
                    if ("REFUSED".equals(result.status())) {
                        log.error("[ANCHOR] NOT anchoring organisation {}: {}", organisationId, result.reason());
                    }
                })
                .onErrorResume(failure -> {
                    log.error("[ANCHOR] Anchoring organisation {} failed: {}", organisationId, failure.getMessage());
                    return Mono.empty();
                }));
    }

    public Mono<AnchorResultResponse> executeFor(UUID organisationId) {
        return Mono.defer(() -> anchorPort.read(organisationId).collectList().flatMap(anchors -> {
            Anchor last = anchors.isEmpty() ? null : anchors.get(anchors.size() - 1);
            if (last != null && !signer.isAuthentic(last)) {
                return Mono.just(refused("The latest anchor does not authenticate: the anchor store was tampered with."));
            }
            ChainVerifier verifier = last == null
                    ? new ChainVerifier(List.of())
                    : new ChainVerifier(last.headSequence() + 1, last.headHash(), List.of());
            long after = last == null ? 0 : last.headSequence();
            return repository.streamChainAfter(organisationId, after)
                    .doOnNext(verifier::accept)
                    .then(Mono.fromSupplier(verifier::result))
                    .flatMap(result -> decide(organisationId, last, result));
        }));
    }

    private Mono<AnchorResultResponse> decide(UUID organisationId, Anchor last, ChainVerification result) {
        if (!result.valid()) {
            return Mono.just(refused(result.reason()));
        }
        if (result.entriesChecked() == 0) {
            return last == null ? Mono.just(new AnchorResultResponse("UNCHANGED", null, null)) : confirmHeadStillMatches(organisationId, last);
        }
        Anchor anchor = signer.sign(organisationId, result.headSequence(), result.headHash(), Instant.now());
        return anchorPort.publish(anchor).thenReturn(new AnchorResultResponse("PUBLISHED", toResponse(anchor), null));
    }

    /**
     * Nothing was written since the last anchor, so there is nothing new to verify - but the head must STILL be what was anchored:
     * a chain truncated below the anchor, or rewritten at the anchored position, also streams no new entries, and calling that
     * "unchanged" would hide it (found live: a truncated chain answered UNCHANGED).
     */
    private Mono<AnchorResultResponse> confirmHeadStillMatches(UUID organisationId, Anchor last) {
        return repository.findLatest(organisationId)
                .map(head -> head.getSequence() == last.headSequence() && head.getHash().equals(last.headHash())
                        ? new AnchorResultResponse("UNCHANGED", toResponse(last), null)
                        : refused("The chain no longer matches the latest anchor (position " + last.headSequence() + "): entries were removed or rewritten after anchoring."))
                .defaultIfEmpty(refused("The chain is empty but an anchor was published for position " + last.headSequence() + ": entries were removed after anchoring."));
    }

    private static AnchorResultResponse refused(String reason) {
        return new AnchorResultResponse("REFUSED", null, reason);
    }

    static AnchorResponse toResponse(Anchor anchor) {
        return new AnchorResponse(anchor.headSequence(), anchor.headHash(), anchor.anchoredAt());
    }
}
