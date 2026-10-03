# ADR-034: Anchoring the Chain Head Outside the Database

## Status
Accepted (closes the limit recorded in ADR-030 and ADR-031)

## Context
The hash chain detects an entry that is edited, rewritten or removed - as long as the attacker leaves the rest of the chain
alone. Someone with write access to the database can do better: rewrite entry N and recompute the hash of every entry after it.
The result is internally consistent and verifies perfectly. Nothing inside the database can tell the difference, because the
attacker controls everything inside it.

The only defence is a fact the attacker cannot rewrite: the head of the chain, published somewhere their database credentials do
not reach.

## Decision
- An **anchor** is `(organisationId, headSequence, headHash, anchoredAt, signature)`: "at this time, position N of this tenant's
  chain had this hash". The signature is HMAC-SHA256 over the other fields under `ledger.anchor.key`, a key the database never
  holds, so an anchor store that is not truly write-once is still tamper-evident.
- Anchors are published through an `AnchorPort`. v1 ships a **file adapter**: one JSON line per anchor, appended and forced to disk,
  in `<ledger.anchor.directory>/<organisationId>.jsonl`. The directory is meant to be a write-once volume or a mount of an object
  store with object lock; the port is the seam for a notary or timestamping service later. Without
  `ledger.anchor.enabled=true` there is no destination (`NoAnchorAdapter`), nothing is anchored, and `anchor/initiate` answers
  `503 ERR-LED-00503`.
- **Publishing**: `AnchorScheduler` anchors every tenant that has entries every `ledger.anchor.interval` (default 1 h), and
  `POST /anchor/initiate` does it on demand for the caller's tenant (201 when a new anchor is published, 200 otherwise).
- **It never seals a broken chain.** Before anchoring, the entries written since the last anchor are verified, starting from that
  anchor's (authenticated) hash; only a chain that links cleanly gets a new anchor. When nothing is new, the current head must still
  equal the latest anchor, otherwise the answer is `REFUSED`, not `UNCHANGED` (found live: a truncated chain first answered
  `UNCHANGED`). A refusal is logged at error level: publishing the head of a tampered chain would turn the tampering into a signed fact.
- **Verification uses the anchors.** `integrity-check/evaluate` additionally requires that the entry at every anchored position has
  the anchored hash, that the chain still reaches the highest anchored position, and that every anchor authenticates. That catches
  the rewritten tail (the hash differs at the anchored position), truncation ("entries were removed after anchoring") and an edited
  or forged anchor store ("the anchor store was tampered with"), and reports `anchorsVerified`.
- Verification and anchoring can start from a trusted checkpoint (the last anchor) rather than re-walking the whole chain, which is
  what keeps the hourly pass cheap on a long ledger. The full evaluation endpoint still walks everything, on purpose.

## Consequences
- Positive: rewriting history now requires also rewriting the anchor store *and* forging its HMAC, or getting there before the next
  anchor. The window of exposure is the anchoring interval.
- Positive: the guarantee is checkable by anyone holding the key and the anchors, without trusting the ledger service.
- Negative: **the protection is only as good as the anchor destination.** A plain directory on the same host and the same
  credentials gives the HMAC's tamper evidence but not write-once; production should mount a volume or bucket the ledger can append
  to but not overwrite, and keep `ledger.anchor.key` in a secret store. The development defaults in compose and the local stack
  are not secure and say so.
- Negative: entries written after the last anchor are not yet protected (up to one interval).
- Negative: the single signing key is a single point of loss and of compromise; rotation (re-anchoring, or accepting several keys) is
  not built in v1. Losing the key makes existing anchors unverifiable, which `integrity-check/evaluate` reports as tampering.
- Negative: the file adapter is local to one ledger instance; running several ledger instances needs a shared destination.
