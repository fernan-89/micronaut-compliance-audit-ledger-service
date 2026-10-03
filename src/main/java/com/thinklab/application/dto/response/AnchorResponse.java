package com.thinklab.application.dto.response;

import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;

/** A published anchor: "at this time, position N of the chain had this hash" (ADR-034). */
@Serdeable
public record AnchorResponse(long headSequence, String headHash, Instant anchoredAt) {
}
