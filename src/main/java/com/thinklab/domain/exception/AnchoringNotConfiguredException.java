package com.thinklab.domain.exception;

/**
 * Domain Exception: an anchor was requested but {@code ledger.anchor.enabled} is off (ADR-034).
 *
 * <p>RFC 7807 mapping: HTTP 503 Service Unavailable - the capability is not available in this deployment; the request is fine.
 */
public class AnchoringNotConfiguredException extends BusinessException {

    public AnchoringNotConfiguredException() {
        super("ERR-LED-00503", "Anchoring is not configured on this deployment (set ledger.anchor.enabled, ledger.anchor.directory and ledger.anchor.key).");
    }
}
