package com.thinklab.domain.port;

import com.thinklab.domain.model.Anchor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Outbound Port to the place anchors are published (ADR-034). It must be somewhere the ledger's own database credentials cannot
 * rewrite: an append-only file on a write-once volume, an object store with object lock, a notary or timestamping service.
 * Append-only by contract: there is no way to replace or remove an anchor.
 */
public interface AnchorPort {

    /** Appends the anchor. */
    Mono<Void> publish(Anchor anchor);

    /** Every anchor published for the tenant, oldest first (empty when there are none or anchoring is not configured). */
    Flux<Anchor> read(UUID organisationId);
}
