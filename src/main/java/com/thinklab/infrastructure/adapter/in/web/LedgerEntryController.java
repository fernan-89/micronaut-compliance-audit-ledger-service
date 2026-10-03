package com.thinklab.infrastructure.adapter.in.web;

import com.thinklab.application.dto.request.AppendLedgerEntryRequest;
import com.thinklab.application.dto.response.AnchorResponse;
import com.thinklab.application.dto.response.AnchorResultResponse;
import com.thinklab.application.dto.response.ChainIntegrityResponse;
import com.thinklab.application.dto.response.LedgerEntryResponse;
import com.thinklab.application.usecase.AnchorChainHeadsUseCase;
import com.thinklab.application.usecase.AppendLedgerEntryUseCase;
import com.thinklab.application.usecase.EvaluateChainIntegrityUseCase;
import com.thinklab.application.usecase.RetrieveAnchorsUseCase;
import com.thinklab.application.usecase.RetrieveLedgerEntriesUseCase;
import com.thinklab.application.usecase.RetrieveLedgerEntryUseCase;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Inbound Adapter for the {@code compliance-audit-ledger} Service Domain (BIAN Behavior Qualifier routes, ADR-013).
 *
 * <p>Append-only: there is no {@code PUT} and no {@code DELETE} on this controller, by design. The only write is
 * {@code initiate}; everything else reads or evaluates. {@code X-Tenant-Id} scopes every call, {@code X-Executor}
 * (mandatory on the write) names who recorded the entry.
 */
@Controller("/compliance-audit-ledger/v1")
public class LedgerEntryController {

    private static final Logger log = LoggerFactory.getLogger(LedgerEntryController.class);
    static final String TENANT_HEADER = "X-Tenant-Id";
    static final String EXECUTOR_HEADER = "X-Executor";

    private final AppendLedgerEntryUseCase appendLedgerEntryUseCase;
    private final RetrieveLedgerEntryUseCase retrieveLedgerEntryUseCase;
    private final RetrieveLedgerEntriesUseCase retrieveLedgerEntriesUseCase;
    private final EvaluateChainIntegrityUseCase evaluateChainIntegrityUseCase;
    private final AnchorChainHeadsUseCase anchorChainHeadsUseCase;
    private final RetrieveAnchorsUseCase retrieveAnchorsUseCase;

    public LedgerEntryController(AppendLedgerEntryUseCase appendLedgerEntryUseCase,
                                 RetrieveLedgerEntryUseCase retrieveLedgerEntryUseCase,
                                 RetrieveLedgerEntriesUseCase retrieveLedgerEntriesUseCase,
                                 EvaluateChainIntegrityUseCase evaluateChainIntegrityUseCase,
                                 AnchorChainHeadsUseCase anchorChainHeadsUseCase,
                                 RetrieveAnchorsUseCase retrieveAnchorsUseCase) {
        this.appendLedgerEntryUseCase = appendLedgerEntryUseCase;
        this.retrieveLedgerEntryUseCase = retrieveLedgerEntryUseCase;
        this.retrieveLedgerEntriesUseCase = retrieveLedgerEntriesUseCase;
        this.evaluateChainIntegrityUseCase = evaluateChainIntegrityUseCase;
        this.anchorChainHeadsUseCase = anchorChainHeadsUseCase;
        this.retrieveAnchorsUseCase = retrieveAnchorsUseCase;
    }

    /** Behavior Qualifier: {@code initiate}. Appends one entry to the tenant's chain. */
    @Post("/initiate")
    public Mono<HttpResponse<LedgerEntryResponse>> initiate(
            @Header(TENANT_HEADER) @NotBlank String tenantId,
            @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Body @Valid AppendLedgerEntryRequest request
    ) {
        log.info("[ACTION: APPEND_LEDGER_ENTRY] [EXECUTOR: {}] organisation: {} action: {}", executor, tenantId, request.action());
        return Mono.defer(() -> appendLedgerEntryUseCase.execute(UUID.fromString(tenantId), request, executor)).map(HttpResponse::created);
    }

    /** Behavior Qualifier: {@code retrieve}. One entry of the caller's tenant. */
    @Get("/{id}/retrieve")
    public Mono<HttpResponse<LedgerEntryResponse>> retrieveById(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId) {
        return Mono.defer(() -> retrieveLedgerEntryUseCase.execute(UUID.fromString(tenantId), id)).map(HttpResponse::ok);
    }

    /** Behavior Qualifier: {@code retrieve} (collection). Newest first; filters are optional, the page is always bounded. */
    @Get("/retrieve")
    public Mono<List<LedgerEntryResponse>> retrieveAll(
            @Header(TENANT_HEADER) @NotBlank String tenantId,
            @QueryValue @Nullable String actor,
            @QueryValue @Nullable String action,
            @QueryValue @Nullable String resourceType,
            @QueryValue @Nullable String resourceId,
            @QueryValue @Nullable Instant from,
            @QueryValue @Nullable Instant to,
            @QueryValue @Nullable Integer limit
    ) {
        return Mono.defer(() -> retrieveLedgerEntriesUseCase
                .execute(UUID.fromString(tenantId), actor, action, resourceType, resourceId, from, to, limit).collectList());
    }

    /** Behavior Qualifier: {@code integrity-check/evaluate}. Walks the tenant's whole chain and says whether it is intact. */
    @Get("/integrity-check/evaluate")
    public Mono<ChainIntegrityResponse> evaluateIntegrity(@Header(TENANT_HEADER) @NotBlank String tenantId) {
        return Mono.defer(() -> evaluateChainIntegrityUseCase.execute(UUID.fromString(tenantId)));
    }

    /** Behavior Qualifier: {@code anchor/initiate}. Publishes the head of the tenant's chain outside the database (ADR-034): 201 when a new anchor was published, 200 when nothing is new or the chain was refused. */
    @Post(value = "/anchor/initiate", consumes = MediaType.ALL)
    public Mono<HttpResponse<AnchorResultResponse>> anchor(
            @Header(TENANT_HEADER) @NotBlank String tenantId,
            @Header(EXECUTOR_HEADER) @NotBlank String executor
    ) {
        log.info("[ACTION: ANCHOR_CHAIN_HEAD] [EXECUTOR: {}] organisation: {}", executor, tenantId);
        return Mono.defer(() -> anchorChainHeadsUseCase.executeFor(UUID.fromString(tenantId)))
                .map(result -> "PUBLISHED".equals(result.status()) ? HttpResponse.created(result) : HttpResponse.ok(result));
    }

    /** Behavior Qualifier: {@code anchor/retrieve}. The anchors published for the tenant, oldest first. */
    @Get("/anchor/retrieve")
    public Mono<List<AnchorResponse>> retrieveAnchors(@Header(TENANT_HEADER) @NotBlank String tenantId) {
        return Mono.defer(() -> retrieveAnchorsUseCase.execute(UUID.fromString(tenantId)).collectList());
    }
}
