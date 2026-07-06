---
status: "accepted"
date: 2026-07-06
decision-makers: [Hasan Selman Kara]
---

# X-Partner-Id HTTP header for business-partner scoping

## Context and Problem Statement

This API's bearer tokens are per-user, not per-business-partner: a single authenticated caller's
token can carry semantic roles for several distinct business partners at once (e.g.
`line_#create@acme`, `line_#read@other-partner`). Yet every operation on a `Line` is single-tenant:
a line belongs to exactly one business partner, and a write must be authorized against exactly one
partner's role. Given a token that may be valid for multiple partners, how does a single HTTP
request state which one of those partners it is acting as, so that authorization checks and (for
create) the persisted `businessPartnerId` are unambiguous?

## Decision Drivers

* Authorization is evaluated per-request, so the partner context can't be left implicit or inferred
  silently from the token alone.
* Must not silently default to "every partner the caller happens to hold a role for" -- an implicit
  union would let a request touch a partner's data the caller didn't intend to name.
* Must stay compatible with a business-partner-*independent* caller class (e.g. the "AdBAZG"
  user-role administrator) that isn't scoped to any single business partner at all.
* Low friction for a typical REST client: any HTTP client can set a header without reshaping the URL
  space per partner.

## Considered Options

* `X-Partner-Id` custom HTTP request header, carrying the partner id explicitly on every request.
* Encode the business partner in the URL path, e.g. `/partners/{partnerId}/lines/{id}`.
* Infer the business partner implicitly from the token: use it directly if the caller holds a role
  for exactly one partner, and reject/disambiguate otherwise.
* A `businessPartnerId` query parameter instead of a header.

## Decision Outcome

Chosen option: "`X-Partner-Id` custom HTTP request header", because it keeps the resource URL
(`/lines/{id}`) stable regardless of which partner is acting, mirrors how other cross-cutting
request metadata (auth, idempotency) is already carried in this API, and makes the partner context
an explicit, request-scoped fact that both `BusinessPartnerFilter` and each endpoint's
`hasRoleForPartner` check can evaluate independently, rather than relying on an implicit, potentially
surprising inference from token contents alone.

### Consequences

* Good, because every request is self-describing: authorization decisions never depend on "the one
  partner the caller happens to hold a role for" -- multi-partner callers must always be explicit.
* Good, because the resource URL stays partner-agnostic (`/lines/{id}`), so a line's identity doesn't
  change if it were ever reassigned to a different partner.
* Good, because callers who hold a partner-independent role (the delete/"AdBAZG" case) never need to
  send the header at all -- it's optional at the transport level, not baked into every route.
* Bad, because it's an extra header every business-partner-scoped client must remember to send;
  forgetting it is, from the client's point of view, indistinguishable from being denied (both
  surface as `403`).
* Bad, because the header's value is trusted only after two separate checks
  (`BusinessPartnerFilter`'s affiliation gate, then the endpoint's own `hasRoleForPartner`); a new
  endpoint that reads `partnerId` without going through `hasRoleForPartner` would silently
  reintroduce the exact ambiguity this ADR exists to prevent.

### Confirmation

`BusinessPartnerFilterTest` and `LineControllerITTest$RolesAndBusinessPartners`/`$Authorization`
assert both checks (affiliation vs. role) independently, and exercise the partner-independent path.
`BusinessPartnerFilter` and the `LineController` methods that accept `X-Partner-Id` are annotated
`@io.github.adr.linked.ADR(1)` so this decision stays discoverable from the code that implements it.

## Pros and Cons of the Options

### `X-Partner-Id` header

* Good, because it decouples the resource URL from the caller's tenancy context.
* Good, because it's consistent with how `Idempotency-Key` and `If-Match` are already used in this
  API as request-scoped metadata headers.
* Neutral, because it requires the two-stage check described above to be trustworthy.
* Bad, because an omitted header must be handled per-endpoint (mandatory for business-partner roles,
  optional for partner-independent ones), rather than being structurally impossible to omit.

### Business partner in the URL path

* Good, because the URL itself is self-documenting about which partner a resource read/write is
  scoped to.
* Bad, because it would require every route to be duplicated/nested under `/partners/{partnerId}/`,
  which doesn't fit the partner-independent caller class (delete) at all.
* Bad, because a line's canonical URL would then encode a tenancy fact that a HATEOAS `self` link
  ideally shouldn't need to carry, given `LineId` alone already identifies the aggregate.

### Implicit inference from the token

* Good, because it requires zero extra client-side work for a caller affiliated with only one
  partner.
* Bad, because it's ambiguous (and therefore either arbitrary or an error) the moment a caller holds
  roles for more than one partner -- the common case this ADR exists to support.
* Bad, because it makes the authorization outcome depend on token contents a client can't see or
  reason about at request-construction time.

### `businessPartnerId` query parameter

* Neutral, because it's mechanically similar to the header option.
* Bad, because query parameters are more likely to end up logged (access logs, browser history,
  proxies) and are less conventionally reserved for cross-cutting metadata than a header.

## More Information

See `BusinessPartnerFilter`, `LineController`, and `LineAuthorization` for the implementation; see
the "Business-partner scoping" section of the published API guide for the caller-facing explanation
of this same decision.
