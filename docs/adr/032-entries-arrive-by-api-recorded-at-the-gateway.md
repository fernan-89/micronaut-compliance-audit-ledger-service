# ADR-032: Entries Arrive Through an API; the Gateway Records Every Mutation

## Status
Accepted

## Context
Platform-wide coverage normally means every service publishing an audit event for every change - a change to
all of them, and a promise each must keep forever. The platform already has one place every external
mutation passes through: the gateway.

## Decision
- The ledger itself is source-agnostic: `POST /initiate` with `source`, `actor`, `action`, `resourceType`,
  optional `resourceId`, `detail` and `occurredAt` (never more than a few minutes in the future). A future
  collector, a service with its own business-level events, or a human can append the same way.
- `platform-gateway-service` is the first writer (ADR-023 of the gateway): after forwarding a `POST`,
  `PUT`, `PATCH` or `DELETE` that carries a tenant it appends one entry - who (executor), what (method and
  path with identifiers masked), which resource, the HTTP status. It is **off by default**
  (`gateway.audit.enabled`), asynchronous and **fail-open**: a slow or unavailable ledger never delays or fails
  the request it describes. Appends are queued and sent one at a time, in the order the requests completed: a ledger position is
  assigned on arrival, so parallel sends could let a later request overtake an earlier one (found live: an `initiate` landed at
  position 4 of 4).
- `X-Executor` on the append names the writer (`platform-gateway`), `actor` names who acted; both are stored
  and hashed.

## Consequences
- Positive: every mutation that goes through the edge is on the chain with no change to the thirteen services.
- Negative: recording at the edge is **API-call audit**, not business-event audit: it says that someone called
  `PUT /it-asset-registry/v1/{id}/control/deploy` and got `204`, not what the asset looked like before.
  Traffic that bypasses the gateway (service to service, direct database edits) is not recorded; the
  integrity check exists for the second case.
- Negative: fail-open means a ledger outage leaves a hole that the chain cannot show (there is nothing to
  be missing from it). The gateway logs each dropped entry and counts it; a deployment that cannot accept
  holes should gate on the ledger being healthy rather than rely on this.
- Negative: requests without a tenant (sign-in, which carries the organisation in the body) are not recorded
  in v1.
