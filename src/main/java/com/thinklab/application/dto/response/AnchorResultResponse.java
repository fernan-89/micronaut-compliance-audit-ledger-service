package com.thinklab.application.dto.response;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/**
 * Outcome of an anchoring attempt: {@code PUBLISHED} (a new anchor), {@code UNCHANGED} (nothing new since the last anchor) or
 * {@code REFUSED} (the chain or the anchor store failed verification, so no anchor was published - sealing a broken chain would
 * make the damage permanent).
 */
@Serdeable
public record AnchorResultResponse(String status, @Nullable AnchorResponse anchor, @Nullable String reason) {
}
