package com.thinklab.infrastructure.adapter.out.anchor;

import com.thinklab.domain.model.Anchor;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.UUID;

/**
 * The stored shape of an anchor (one JSON line in the file store, one JSON object in the object store), kept out of the domain.
 * {@code keyId} is absent in anchors published before keys were named (ADR-035).
 */
@Serdeable
@Introspected
record AnchorLine(String organisationId, long headSequence, String headHash, String anchoredAt, String signature, String keyId) {

    static AnchorLine of(Anchor anchor) {
        return new AnchorLine(anchor.organisationId().toString(), anchor.headSequence(), anchor.headHash(), anchor.anchoredAt().toString(),
                anchor.signature(), anchor.keyId());
    }

    Anchor toAnchor() {
        return new Anchor(UUID.fromString(organisationId), headSequence, headHash, Instant.parse(anchoredAt), signature, keyId);
    }
}
