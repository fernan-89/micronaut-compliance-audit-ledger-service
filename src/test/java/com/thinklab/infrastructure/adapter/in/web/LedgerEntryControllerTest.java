package com.thinklab.infrastructure.adapter.in.web;

import com.thinklab.application.dto.request.AppendLedgerEntryRequest;
import com.thinklab.application.dto.response.ChainIntegrityResponse;
import com.thinklab.application.dto.response.LedgerEntryResponse;
import com.thinklab.application.usecase.AppendLedgerEntryUseCase;
import com.thinklab.application.usecase.EvaluateChainIntegrityUseCase;
import com.thinklab.application.usecase.RetrieveLedgerEntriesUseCase;
import com.thinklab.application.usecase.RetrieveLedgerEntryUseCase;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LedgerEntryControllerTest {

    @Mock private AppendLedgerEntryUseCase append;
    @Mock private RetrieveLedgerEntryUseCase retrieveOne;
    @Mock private RetrieveLedgerEntriesUseCase retrieveAll;
    @Mock private EvaluateChainIntegrityUseCase evaluate;
    @InjectMocks private LedgerEntryController controller;

    private final UUID org = UUID.randomUUID();

    private LedgerEntryResponse response(UUID id) {
        return new LedgerEntryResponse(id, org, 1, Instant.now(), Instant.now(), "gw", "alice", "A", "t", "r", null, "gw", "0".repeat(64), "h");
    }

    @Test
    @DisplayName("initiate appends under the caller's tenant and answers 201")
    void initiate() {
        AppendLedgerEntryRequest request = new AppendLedgerEntryRequest("gw", "alice", "A", "t", "r", null, null);
        LedgerEntryResponse created = response(UUID.randomUUID());
        when(append.execute(org, request, "gateway")).thenReturn(Mono.just(created));

        StepVerifier.create(controller.initiate(org.toString(), "gateway", request))
                .assertNext(http -> {
                    assertEquals(HttpStatus.CREATED, http.getStatus());
                    assertEquals(created, http.body());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("a malformed tenant id is an IllegalArgumentException (400), raised inside the reactive chain")
    void malformedTenant() {
        AppendLedgerEntryRequest request = new AppendLedgerEntryRequest("gw", "alice", "A", "t", "r", null, null);

        StepVerifier.create(controller.initiate("not-a-uuid", "gateway", request)).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(controller.retrieveById(UUID.randomUUID(), "not-a-uuid")).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(controller.retrieveAll("not-a-uuid", null, null, null, null, null, null, null)).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(controller.evaluateIntegrity("not-a-uuid")).expectError(IllegalArgumentException.class).verify();
    }

    @Test
    @DisplayName("retrieveById is tenant-scoped and answers 200")
    void retrieveById() {
        UUID id = UUID.randomUUID();
        when(retrieveOne.execute(org, id)).thenReturn(Mono.just(response(id)));

        StepVerifier.create(controller.retrieveById(id, org.toString()))
                .assertNext(http -> assertEquals(HttpStatus.OK, http.getStatus()))
                .verifyComplete();
    }

    @Test
    @DisplayName("retrieveAll hands every filter to the use case and collects the page into a list")
    void retrieveAll() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-02-01T00:00:00Z");
        when(retrieveAll.execute(eq(org), eq("alice"), eq("A"), eq("t"), eq("r"), eq(from), eq(to), eq(10)))
                .thenReturn(Flux.just(response(UUID.randomUUID())));

        StepVerifier.create(controller.retrieveAll(org.toString(), "alice", "A", "t", "r", from, to, 10))
                .assertNext(list -> assertEquals(1, list.size()))
                .verifyComplete();
        verify(retrieveAll).execute(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("evaluateIntegrity returns the verdict for the caller's tenant")
    void evaluateIntegrity() {
        ChainIntegrityResponse verdict = new ChainIntegrityResponse(true, 3, 3, "h", null, null);
        when(evaluate.execute(org)).thenReturn(Mono.just(verdict));

        StepVerifier.create(controller.evaluateIntegrity(org.toString())).expectNext(verdict).verifyComplete();
    }
}
