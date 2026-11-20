# ADR 036: The repository port has no lookup without the organisation

## Status
Accepted (2026-11-20). Part of the platform tenant audit, group C (`thinklab-platform/docs/TENANT-AUDIT-2026-11.md`); same rule as the nine services of group A.

## Context
The routes of this service already took `X-Tenant-Id`, and the use case loaded the ledger entry by id and compared the organisation afterwards: another tenant's was already a 404, so nothing leaked. But the port still offered `findById(id)` without an organisation, so a new use case could forget the comparison.

## Decision
`findById(id, organisationId)` is the only lookup by id: the adapter filters on `_id` and `organisationId` together, and the comparison in the use case is gone. Another tenant's object is still the same as none (404). Writes that follow a scoped load keep using the id. No change to routes, headers or responses.

## Consequences
- Positive: the scope cannot be forgotten by a future use case; one place does the filtering.
- Negative: none for callers. The hash chain and the anchors read the ledger by sequence, not by id, and are untouched.
