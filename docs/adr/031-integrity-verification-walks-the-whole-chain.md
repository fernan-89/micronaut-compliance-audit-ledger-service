# ADR-031: Integrity Is Verified by Walking the Whole Chain, Streaming

## Status
Accepted

## Context
A chain only has value if someone can ask "is it still intact?" and get an answer that points at the damage.

## Decision
- `GET /compliance-audit-ledger/v1/integrity-check/evaluate` streams the tenant's chain in sequence order into a
  `ChainVerifier` and answers `{valid, entriesChecked, headSequence, headHash, firstBrokenSequence, reason}`.
- An entry is accepted only if (1) it is at the expected next position - a gap means an entry is missing,
  including a removed first entry; (2) its `previousHash` equals the previous entry's hash (the genesis hash for
  the first); (3) its own hash still matches its content. Verification stops at the first failure and reports
  it: everything after a broken link is untrustworthy anyway, so continuing would only bury the cause.
- The stored hash is **read as stored and never recomputed on load**. If the mapper recomputed it, tampering
  would heal itself on every read and the check would always pass.
- The walk is a fold over a `Flux`, so memory stays flat for any ledger length. The cost is linear in the
  chain's length and the endpoint is read-only.
- `valid` with `entriesChecked = 0` is a legitimate answer for a tenant with no entries.

## Consequences
- Positive: the answer is specific (where, and what kind of damage), not just a boolean.
- Positive: it needs no state beyond the chain itself, so it can run anywhere, any time, and a periodic job
  can store `headHash` externally (ADR-030) to cover the "rewrite the whole tail" case.
- Negative: it is O(n) per call. A very long chain would want a checkpointed verification (verify from a
  trusted earlier `headSequence`); the verifier is already shaped for that, but it is not exposed in v1.
