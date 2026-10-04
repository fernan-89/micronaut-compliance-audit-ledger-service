# ADR-035: A Write-Once Anchor Store (Object Lock) and Named, Rotatable Anchor Keys

## Status
Accepted (builds on ADR-034)

## Context
ADR-034 publishes the head of each tenant's chain outside the database, in an append-only file that "should" sit on a write-once volume. Two things were left open: nothing proved the destination really was write-once (that depended on how a volume was mounted), and the single HMAC key signing anchors could never be changed without making every older anchor unverifiable.

## Decision
**Destination.** The anchor port gets a second adapter, selected by `ledger.anchor.sink` (`file`, the default and unchanged, or `s3`):
- `s3` writes each anchor as its **own object** in an S3-compatible bucket (AWS S3, MinIO, Ceph...) that has **Object Lock** enabled. Each object is locked in `COMPLIANCE` mode (configurable to `GOVERNANCE`) for `ledger.anchor.s3.retention-days` (default 3650): until then it cannot be overwritten or deleted, not even by the account that wrote it. The destination itself enforces write-once, instead of a mount option.
- Objects are named `<prefix><organisation>/<sequence zero-padded>-<millis>.json`, so a listing reads oldest first; each is the same JSON as a file line, with a Content-MD5 on upload.
- **Reading goes through object versions, not the plain listing.** On a versioned bucket (Object Lock requires it) a plain delete only adds a *delete marker* that hides the object from an ordinary listing - which would make an anchor store that silently lost its anchors look like one where nothing was anchored. The adapter lists versions and reads each locked version by id; delete markers are ignored for reading and logged at error level, because someone tried to remove an anchor.
- The bucket must be created with Object Lock (a creation-time choice): the service never creates it. The compose stack creates one on MinIO; production points at a real bucket. Credentials come from `ledger.anchor.s3.access-key`/`secret-key` or, when blank, the standard AWS credential chain.
- Publishing failures fail the publish (the anchor is not silently dropped), as with the file store.

**Key custody and rotation.**
- Every anchor now carries the **id of the key that signed it** (`keyId`), and the signature covers the id, so an anchor cannot be re-labelled as signed by another key.
- `ledger.anchor.key` is the current key and `ledger.anchor.key-id` its name (default `k1`); retired keys stay configured **only to verify**, as `ledger.anchor.previous-keys.<id>`. To rotate: set a new key under a new id, move the old key under `previous-keys`. New anchors use the new key; old anchors keep verifying. A key dropped from the configuration can no longer vouch for the anchors it signed (they then fail to authenticate, which is the intent for a key that is destroyed).
- Anchors published before key ids existed have none: they are verified, in the original encoding, by the current key or any retired one.
- The key is held outside the ledger's database (environment or secret store), as before; see `docs/runbook-anchor-key-rotation.md`.

## Consequences
- Positive: a tampered or deleted anchor is impossible rather than merely detectable (on a store that supports Object Lock); a leaked or aged key can be replaced without losing the history.
- Negative: reading anchors lists and fetches every version for the tenant, which is fine at one anchor an hour but grows with time (a cache or compaction is a later step); a COMPLIANCE lock cannot be undone, so a wrongly configured retention is permanent; the HMAC key is still symmetric (whoever holds it can forge), so asymmetric signing with the private key in a KMS is the next step if verifiers outside the ledger are needed.
