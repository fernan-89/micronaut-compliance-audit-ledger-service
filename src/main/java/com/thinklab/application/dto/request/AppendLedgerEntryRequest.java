package com.thinklab.application.dto.request;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * DTO for appending to the ledger (BIAN Behavior Qualifier: {@code initiate}). organisationId travels via
 * the {@code X-Tenant-Id} header and the writer's identity via {@code X-Executor}, not the body.
 */
@Serdeable
public record AppendLedgerEntryRequest(

        @NotBlank(message = "Source is required")
        @Size(max = 200, message = "Source must not exceed 200 characters")
        String source,

        @NotBlank(message = "Actor is required")
        @Size(max = 200, message = "Actor must not exceed 200 characters")
        String actor,

        @NotBlank(message = "Action is required")
        @Size(max = 200, message = "Action must not exceed 200 characters")
        String action,

        @NotBlank(message = "Resource type is required")
        @Size(max = 200, message = "Resource type must not exceed 200 characters")
        String resourceType,

        @Size(max = 200, message = "Resource id must not exceed 200 characters")
        String resourceId,

        @Size(max = 1000, message = "Detail must not exceed 1000 characters")
        String detail,

        /** When it happened; defaults to now. An entry cannot claim to be from the future. */
        Instant occurredAt
) {}
