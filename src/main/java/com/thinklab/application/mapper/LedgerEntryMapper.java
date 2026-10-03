package com.thinklab.application.mapper;

import com.thinklab.application.dto.response.ChainIntegrityResponse;
import com.thinklab.application.dto.response.LedgerEntryResponse;
import com.thinklab.domain.model.ChainVerifier.ChainVerification;
import com.thinklab.domain.model.LedgerEntry;

/** Static factory mapper for ledger DTOs. Enforces the DTO Isolation Pattern. */
public final class LedgerEntryMapper {

    private LedgerEntryMapper() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    public static LedgerEntryResponse toResponse(LedgerEntry entry) {
        return new LedgerEntryResponse(entry.getId(), entry.getOrganisationId(), entry.getSequence(), entry.getOccurredAt(),
                entry.getRecordedAt(), entry.getSource(), entry.getActor(), entry.getAction(), entry.getResourceType(),
                entry.getResourceId(), entry.getDetail(), entry.getRecordedBy(), entry.getPreviousHash(), entry.getHash());
    }

    public static ChainIntegrityResponse toResponse(ChainVerification verification) {
        return new ChainIntegrityResponse(verification.valid(), verification.entriesChecked(), verification.headSequence(),
                verification.headHash(), verification.firstBrokenSequence(), verification.reason());
    }
}
