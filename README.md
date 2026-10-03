# micronaut-compliance-audit-ledger-service

BIAN-aligned Service Domain **compliance-audit-ledger** (Control Record: `LedgerEntry`), port `8094`.

An append-only, tamper-evident record of who did what to which resource, when. Each tenant has its own hash chain:
every entry commits to its content and to the hash of the entry before it, so altering or removing an entry
afterwards is detected and located (ADR-030, ADR-031). It is the platform's audit trail you can hand to an auditor.

## What it guarantees, and what it does not

- **Append-only.** There is no update and no delete: not in the API, not in the repository port.
- **No forks.** A unique `(organisationId, sequence)` index makes two writers racing for the same position impossible;
  the loser retries against the new head.
- **Tamper-evident, not tamper-proof.** Edit an entry, rewrite it, or delete one straight in the database and
  `integrity-check/evaluate` reports the first broken position. Someone who rewrites a chain's whole tail and recomputes
  every hash produces a chain that verifies; anchor the returned `headHash` somewhere out of reach to close that (ADR-030).

## BIAN Behavior Qualifier Contract

`X-Tenant-Id` is mandatory on every call and scopes it; `X-Executor` (who recorded the entry) is mandatory on `initiate`.

### LedgerEntry - `/compliance-audit-ledger/v1`

| Behavior Qualifier | Route |
|---|---|
| initiate (append) | `POST /compliance-audit-ledger/v1/initiate` |
| retrieve | `GET /compliance-audit-ledger/v1/{id}/retrieve` |
| retrieve (collection) | `GET /compliance-audit-ledger/v1/retrieve?actor=&action=&resourceType=&resourceId=&from=&to=&limit=` |
| integrity-check/evaluate | `GET /compliance-audit-ledger/v1/integrity-check/evaluate` |

The collection answers newest first, always bounded (`limit` defaults to 100, at most 500). `from` is inclusive and
`to` exclusive, on `occurredAt`.

```bash
curl -X POST http://localhost:8094/compliance-audit-ledger/v1/initiate \
  -H "Content-Type: application/json" -H "X-Tenant-Id: <organisationId>" -H "X-Executor: platform-gateway" \
  -d '{"source":"platform-gateway","actor":"alice","action":"PUT control/deploy","resourceType":"it-asset-registry","resourceId":"<assetId>","detail":"status=204"}'
```

The response carries `sequence`, `previousHash` and `hash`.

```bash
curl http://localhost:8094/compliance-audit-ledger/v1/integrity-check/evaluate -H "X-Tenant-Id: <organisationId>"
# {"valid":true,"entriesChecked":42,"headSequence":42,"headHash":"..."}
# {"valid":false,"entriesChecked":17,"headSequence":16,"headHash":"...","firstBrokenSequence":17,"reason":"..."}
```

## Where entries come from

Anything can append. The platform gateway records every mutating request that carries a tenant when
`gateway.audit.enabled=true` (asynchronous and fail-open, ADR-032). That is API-call audit: it records that a call
was made and how it ended, not a before/after of the data.

## Error catalog

| Code | HTTP | Meaning |
|---|---|---|
| `ERR-LED-00404` | 404 | Entry not found (or belongs to another tenant) |
| `ERR-LED-00409` | 409 | The chain position stayed contended after the bounded retries; safe to retry |
| `ERR-VALIDATION-00400` | 400 | Payload/header/identifier validation failure, including an entry dated in the future |
| `ERR-INTERNAL-00500` | 500 | Unexpected technical failure |

## Build and test

```bash
./gradlew check      # unit tests, 100% line and branch coverage gate, and the Testcontainers integration suite (needs Docker)
```

The integration suite proves, against a real MongoDB, that concurrent writers cannot fork the chain and that an entry
edited or deleted in the store is caught.

## License

Licensed under the [PolyForm Strict License 1.0.0](LICENSE): you may read and use this software for noncommercial purposes only. Modifying it, creating derivative works, redistributing it and any commercial use are not permitted without a separate written license. This software is not open source.
