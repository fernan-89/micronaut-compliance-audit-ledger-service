package com.thinklab.application.dto.response;

import io.micronaut.serde.annotation.Serdeable;

/** Outcome of walking a tenant's whole chain (BIAN Behavior Qualifier: {@code integrity-check/evaluate}). */
@Serdeable
public record ChainIntegrityResponse(
        boolean valid,
        long entriesChecked,
        long headSequence,
        String headHash,
        Long firstBrokenSequence,
        String reason,
        long anchorsVerified
) {}
