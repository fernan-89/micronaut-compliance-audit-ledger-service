# Runbook: anchor key custody and rotation

The key that signs anchors (`ledger.anchor.key`) proves that an anchor was published by the ledger and not edited since. It must **never be stored in, or readable with the credentials of, the ledger's database** - otherwise whoever can rewrite the chain could also re-sign anchors. See ADR-034 and ADR-035.

## Where the key lives
- An environment variable of the ledger process, filled from your secret store (`LEDGER_ANCHOR_KEY`). Not in the image, not in the repository, not in the database.
- Use at least 32 random characters. Give it a name: `LEDGER_ANCHOR_KEY_ID` (default `k1`).
- Keep an offline, access-controlled copy of every key that ever signed an anchor you still need to verify.

## Rotate (planned, e.g. yearly, or when someone with access leaves)
1. Generate a new key and pick a new id (`k2`).
2. Configure the ledger with the new key as current and the old one as retired:
   ```
   LEDGER_ANCHOR_KEY=<new key>
   LEDGER_ANCHOR_KEY_ID=k2
   LEDGER_ANCHOR_PREVIOUS_KEYS=k1=<old key>      # comma separated id=key pairs (keys must not contain commas)
   ```
3. Restart the ledger. Check: `GET /compliance-audit-ledger/v1/integrity-check/evaluate` still says valid with the same `anchorsVerified` (old anchors verify under `k1`), and the next `POST anchor/initiate` publishes an anchor whose `keyId` is `k2` in the store.
4. Keep `previous-keys` for as long as you need to verify the anchors the old key signed (the object-lock retention, by default ten years).

## The key leaked (or may have)
Anchors signed with it could have been forged *from then on*. Rotate as above immediately, then: read the anchors published since the suspected date straight from the object store, compare each `headHash` with the chain, and treat any that do not match a real chain position as forged. The Object Lock store itself cannot have been rewritten, so the anchors that were genuinely published are all still there; a forged one would have to be a *new* object, visible in the store's version history with its upload time.

## Retire a key for good (destruction)
Remove it from `previous-keys`. The anchors it signed will stop authenticating (the chain then reports them), which is what you want for a key that was destroyed on purpose (for example as part of an erasure decision). Do it only with a written decision.

## Store credentials
The ledger needs only `PutObject`, `ListBucketVersions` and `GetObjectVersion` on the anchor bucket - never `DeleteObjectVersion` or `BypassGovernanceRetention`. Keep the bucket's own administrator separate from the ledger's operators.
