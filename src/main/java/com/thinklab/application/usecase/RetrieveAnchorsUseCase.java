package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.AnchorResponse;
import com.thinklab.domain.port.AnchorPort;
import jakarta.inject.Singleton;
import reactor.core.publisher.Flux;

import java.util.UUID;

/** Lists the anchors published for a tenant, oldest first (BIAN Behavior Qualifier: {@code anchor/retrieve}, ADR-034). */
@Singleton
public class RetrieveAnchorsUseCase {

    private final AnchorPort anchorPort;

    public RetrieveAnchorsUseCase(AnchorPort anchorPort) {
        this.anchorPort = anchorPort;
    }

    public Flux<AnchorResponse> execute(UUID organisationId) {
        return anchorPort.read(organisationId).map(AnchorChainHeadsUseCase::toResponse);
    }
}
