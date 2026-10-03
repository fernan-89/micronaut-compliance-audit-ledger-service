# ADR-033: Data Protection - What the Ledger May Hold

## Status
Accepted

## Context
The ledger is immutable by design (ADR-030). That is the opposite of what privacy law expects of personal data
(erasure, minimisation, retention limits), and the opposite of what card-data and health-data rules expect of
credentials and records (never store them at all). Whatever enters a chain stays there, readable by anyone who may
read the ledger. The decision therefore has to be made at the door, not afterwards.

This ADR records the technical safeguards the platform applies. It is not a compliance certification: PCI DSS,
LGPD and HIPAA are satisfied by an organisation's whole programme (processes, contracts, access reviews, audits), not by
one service. What the ledger can do is make sure it never becomes the place where those programmes are broken.

## Decision
**1. The ledger records facts about actions, never the data the actions carried.**
- Allowed: tenant id, an opaque actor identifier, the method and path with identifiers masked, the Service Domain, an
  opaque resource id, the outcome (an HTTP status), timestamps, and the hashes.
- Never: passwords or any credential, tokens (JWT, API keys, `Authorization`), request or response bodies, query strings
  (they carry search terms and tokens), headers other than the tenant and executor, card numbers, email addresses and other
  direct contact data, or health/clinical content. A resource is referred to by an opaque identifier, never by what it contains.

**2. Personal identifiers are pseudonymised before they arrive.** A person is `login:<hex>` / `user:<hex>`: HMAC-SHA256 of the
identifier under a secret key, truncated to 128 bits. Correlation across entries works (the same person always maps to the same
pseudonym); reversal and dictionary attacks do not, without the key. A plain unkeyed hash of an email is **not** a pseudonym -
it can be brute-forced from a list of addresses - so a gateway without a key records `login:unkeyed` (the attempt and its
outcome, no per-person correlation) rather than a bare hash. The key lives in configuration (`GATEWAY_AUDIT_PSEUDONYM_KEY`), never
in the ledger.

**3. The ledger refuses what it should not hold (defence in depth).** `SensitiveDataGuard` rejects, with a 400 that names the
field but never echoes the value, any text field that contains an email address, a Luhn-valid 13-19 digit number (a payment card
number), a JWT, or a `password`/`secret`/`token`/`authorization`/`api-key` assignment. The gateway filters first; this makes a
careless or compromised writer fail loudly instead of writing something that could never be removed. It is a tripwire for the
common leaks, not a classifier: it cannot recognise a person's name or a diagnosis, so writers must send opaque identifiers.

**4. Erasure is by crypto-shredding, not by editing the chain.** Because the chain holds pseudonyms and no direct identifiers,
an erasure request (LGPD art. 18, GDPR art. 17) is honoured by destroying the pseudonymisation key (or the key for that subject):
the entries remain, the hashes still verify, and the pseudonyms can no longer be tied to a person. Deleting or rewriting entries is
deliberately impossible.

**5. Reads are tenant-scoped and the ledger is not exposed to end users.** Another tenant's entry answers like a missing one.
Operational access to the ledger and its database must be limited and itself audited; retention and archival are policy decisions
of the operator (the chain supports verifying from a trusted checkpoint, see ADR-031).

## Consequences
- Positive: the worst a database leak of the ledger can reveal is who-did-what-when under pseudonyms, with no credentials,
  tokens, card numbers or contact data to misuse.
- Positive: erasure and immutability stop being in conflict.
- Negative: investigators cannot read a person's identity from the ledger; they must resolve a pseudonym through whoever holds
  the key. That is the point, and it needs an operational procedure (who may resolve, when, logged).
- Negative: losing the key makes every pseudonym permanently unresolvable (including for legitimate investigations), and rotating
  it breaks correlation across the rotation. Key custody and rotation policy are the operator's responsibility; v1 has a single key.
- Negative: the guard can reject a legitimate entry by a false positive (an identifier that happens to look like an email or pass
  the Luhn check). The writer's drop counter makes it visible; the alternative, accepting it, is worse.
  (Found in CI: the first version checked only Luhn, and about one 13-digit number in ten passes it - including millisecond timestamps
  used in resource ids - so a Postman run was rejected one time in ten. The rule now also requires a real scheme length and prefix; 16-19
  digit identifiers starting 2-6 can still collide by chance, so numeric resource ids of that shape should not be used.)
- Out of scope for v1: field-level encryption of the ledger at rest beyond what the database provides, a retention/archival job,
  and a documented key-escrow procedure.
