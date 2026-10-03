package com.thinklab.infrastructure.adapter.in;

import com.thinklab.application.usecase.AnchorChainHeadsUseCase;
import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Anchors every tenant's chain head on a schedule (ADR-034): every {@code ledger.anchor.interval} (default one hour), after a
 * short initial delay. Only exists when {@code ledger.anchor.enabled} is true. A refused or failed tenant is logged at error level by
 * the use case and never stops the others.
 */
@Singleton
@Requires(property = "ledger.anchor.enabled", value = "true")
public class AnchorScheduler {

    private static final Logger log = LoggerFactory.getLogger(AnchorScheduler.class);

    private final AnchorChainHeadsUseCase useCase;

    public AnchorScheduler(AnchorChainHeadsUseCase useCase) {
        this.useCase = useCase;
    }

    @Scheduled(fixedDelay = "${ledger.anchor.interval:1h}", initialDelay = "${ledger.anchor.initial-delay:1m}")
    public void run() {
        useCase.executeAll().subscribe(
                result -> log.info("[ANCHOR] {} {}", result.status(), result.anchor()),
                failure -> log.error("[ANCHOR] The anchoring pass failed: {}", failure.getMessage()));
    }
}
