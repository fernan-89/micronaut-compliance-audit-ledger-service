# ADR-030: Each Tenant's Ledger Is a Hash Chain Guarded by a Unique Position

## Status
Accepted

## Context
Every service already keeps an audit trail inside its own aggregates, but a trail that lives next to the data
it describes can be edited together with it, and nothing ties one service's trail to another's. Compliance
asks for a record that makes after-the-fact tampering *detectable*, even by someone with database access.
It does not ask for tamper-*proof* storage, which no application can promise.

## Decision
- A `LedgerEntry` is immutable and carries `sequence` (its position in the tenant's chain, from 1),
  `previousHash` (the hash of the entry before it; 64 zeros for the first) and `hash`: SHA-256 over every
  committed field - tenant, position, `occurredAt`, source, actor, action, resource type and id, detail,
  who recorded it, and `previousHash`. Each field is encoded as `length:value` (`-` for null) so shifting
  text between neighbouring fields cannot produce the same digest.
- One chain **per tenant**, so tenants never contend and one tenant's volume never slows another's appends.
- `recordedAt` is set by the server and deliberately **not** hashed, and timestamps are truncated to
  milliseconds: the store keeps milliseconds, and a hash that did not survive a round trip would flag every
  entry as tampered.
- Position safety is the database's job: a **unique index on `(organisationId, sequence)`**. An append reads
  the head, builds the next entry and inserts; a writer that loses the race gets a duplicate-key error,
  translated to `ChainConflictException`, re-reads the new head and tries again (8 attempts, one Sovereign ID
  for all of them). Persistent contention surfaces as `409 ERR-LED-00409`, which is safe to retry.
  There is no lock and no coordinator, so a fork is impossible by construction, not by convention.
- The repository port has **no update and no delete**, and the controller has no `PUT`/`DELETE`.
  Corrections are new entries.

## Consequences
- Positive: altering any field of an entry, rewriting an entry (even recomputing its hash), or removing one
  breaks verification at a known position (ADR-031).
- Positive: appends need no distributed lock; contention costs a retry, not a stall.
- Negative: a writer with database access who rewrites the *entire* tail of a chain, recomputing every hash,
  produces a chain that verifies. Defending against that needs the head hash anchored somewhere the writer
  cannot reach (a periodic export, a notary, a write-once store); `integrity-check/evaluate` returns the
  `headHash` precisely so an operator can do that. Not built in v1.
- Negative: the chain serialises a tenant's appends at the database, so one tenant's sustained write rate is
  bounded by round trips to it. Fine for audit traffic; not a general event store.
