package com.thinklab.application.dto.response;

import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.UUID;

/** Read model of a ledger entry, including the hashes that let a caller verify it independently. */
@Serdeable
public record LedgerEntryResponse(
        UUID id,
        UUID organisationId,
        long sequence,
        Instant occurredAt,
        Instant recordedAt,
        String source,
        String actor,
        String action,
        String resourceType,
        String resourceId,
        String detail,
        String recordedBy,
        String previousHash,
        String hash
) {}
