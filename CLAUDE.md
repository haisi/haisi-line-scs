# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

A teaching repo (see `README.adoc` and `presentation/optimistic-locking-idempotence.md`) pairing a
conference talk with a working Spring Boot example. The talk covers:

- Optimistic locking with JPA `@Version`, and why the version must live on the **aggregate root**,
  not on child rows.
- Mapping concurrency conflicts to HTTP status codes (`ETag`, `If-Match`, `If-None-Match`).
- Designing idempotent HTTP APIs (`Idempotency-Key`).

The `Line` domain (a line with a `LeftPoint` and `RightPoint`) in `optimistic-locking-web/src/main`
is the running example used to demonstrate these ideas in code, backed by an IT test in
`optimistic-locking-web/src/test`. When working on domain code here, prefer solutions that keep
demonstrating these lessons clearly over "cleaner" designs that would obscure them (e.g. don't add
`@Version` to `LeftPoint`/`RightPoint` — that's the exact mistake the talk warns against).

An `optimistic-locking-ui` module (Angular, built on `@quadrel-enterprise-ui/framework`) puts a
frontend in front of this API — see the Architecture section below.

Status-code decision rule used throughout the code (from the talk's cheat sheet):
- Concurrent writers collide, client sent no precondition → **409 Conflict**
- Client sent `If-Match` and it's stale → **412 Precondition Failed**
- Business rule violated → **422 Unprocessable**
- `If-Match` required but missing → **428 Precondition Required**

## Commands

Two-module Maven build (see Architecture below): `optimistic-locking-web` (the Spring Boot app,
Java 25) + `optimistic-locking-ui` (the Angular frontend). Building `optimistic-locking-ui`
requires Node.js/npm on `PATH` (it shells out via `exec-maven-plugin`, no bundled Node download).

```
./mvnw install -pl optimistic-locking-ui                              # one-time: builds+installs the Angular jar
./mvnw test -pl optimistic-locking-web                                # run all backend tests
./mvnw test -pl optimistic-locking-web -Dtest=LineControllerITTest    # run a single test class
./mvnw test -pl optimistic-locking-web -Dtest=LineControllerITTest#isIdempotent   # single test method
./mvnw spring-boot:run -pl optimistic-locking-web                     # run the app (H2 in-memory DB)
./mvnw spring-boot:run -pl optimistic-locking-web -Dspring-boot.run.profiles=local  # + demo auth/seed data, see below
./mvnw clean package                                                  # build both modules' jars
```

Once `optimistic-locking-ui` has been installed once, `-pl optimistic-locking-web` alone is
enough for backend-only iteration (Maven resolves the UI jar from the local repo, no npm re-run).

For frontend-only iteration, `cd optimistic-locking-ui && npm start` runs `ng serve` with a dev
proxy (`proxy.conf.json`) forwarding `/lines`, `/dev`, `/docs` to a backend already running on
`:8080` (see above) — no CORS configuration exists or is needed. `npm test` runs the Angular unit
tests (Karma/Jasmine).

`./mvnw package` (or `verify`/`install`) additionally screenshots the frontend into the API guide
(see the "Web UI" section of `index.adoc`), via two `exec-maven-plugin` executions bound to
`prepare-package` in `optimistic-locking-web/pom.xml`, both landing in
`generated-docs.directory/images` before the doc-rendering step embeds them:
- `take-overview-screenshot.sh` boots the app under the `local` profile and screenshots the real,
  running Lines overview page — this one deliberately exercises the actual integration.
- `optimistic-locking-ui/scripts/screenshot-components.mjs` screenshots everything else (currently
  just the line detail page) against the built frontend *alone*: no backend, no JVM — it serves
  `optimistic-locking-ui`'s own built static assets and mocks each page's HTTP calls directly via
  Playwright's request interception. Add another page/component to screenshot this way by adding
  an entry to that script's `specs` array, not by wiring up a new Maven execution.

`./mvnw test` alone skips both, same as it already skips the rest of the doc generation. This
needs a Playwright-installed Chromium
(`cd optimistic-locking-ui && npx playwright install chromium`, one-time) in addition to Node/npm.

There is no separate lint command configured for the backend; code style follows the
palantir-java-format IntelliJ settings checked into `.idea/`.

## Architecture

- **Aggregate: `Line`** (`line/Line.java`) — the JPA aggregate root, `@Version`-locked via
  `lockVersion`. It owns `LeftPoint`/`RightPoint` as `@OneToOne(cascade = ALL, orphanRemoval = true)`
  children. Children deliberately have **no** `@Version` of their own — the consistency boundary is
  the aggregate, not the row. `Line.moveLeft`/`moveRight` enforce both business invariants
  ("left never exceeds right", "at most 5 updates total, summed across both points") *before*
  mutating, then delegate to the child's dumb `move(int)` mutator — children hold no validation logic
  of their own. `canMoveLeft()`/`canMoveRight()` expose the same checks so the web layer can reflect
  them as HATEOAS affordances.
- **Forced version increment**: a move only touches a child row, so Hibernate won't auto-bump
  `Line.lockVersion` on its own. `LineRepository.findForUpdate` loads with
  `@Lock(OPTIMISTIC_FORCE_INCREMENT)` specifically so `LineService.move` gets a real version CAS at
  commit even though only `LeftPoint`/`RightPoint` changed.
- **`Line.getLockVersion()`** returns the version pre-formatted as a quoted ETag string (`"\"" +
  lockVersion + "\""`) — this is what the controller puts straight into the `ETag` header.
- **Identity**: `LineId` is a `record` wrapping a `UUID`, implementing jMolecules'
  `Identifier`/`AggregateRoot` DDD marker interfaces (see `org.jmolecules.ddd.types`) for
  documentation/architecture-testing purposes rather than runtime behavior.
- **Web layer** (`line/web/`): `LineController` exposes `GET/PUT/DELETE /lines/{id}`,
  `PUT /lines/{id}/left` and `PUT /lines/{id}/right` (move), and a minimal paginated `GET /lines`. It
  translates aggregate version into `ETag`/`If-Match`/`If-None-Match` HTTP semantics directly (no
  separate mapper layer). `LineRepresentationModelProcessor` adds a self-link plus conditional
  `move-left`/`move-right` links (built via `WebMvcLinkBuilder.linkTo(methodOn(...))`) that disappear
  exactly when the corresponding move would violate a business rule. `OptimisticLockingExceptionHandler`
  is the one `@RestControllerAdvice` in the app, mapping `ObjectOptimisticLockingFailureException` to
  409 — every other status comes from a domain exception in `shared`/`line` annotated `@ResponseStatus`
  (see `StaleStateIdentified` for the pattern).
- **Service layer** (`LineService`): where optimistic-concurrency and idempotency decisions are made.
  `create` is a PUT-style, client-generated-UUID create: identical retries are a no-op replay (200),
  a different body for an existing id is a genuine 409 (no precondition was ever stated for create).
  `move` (private, called by `moveLeft`/`moveRight`) runs gates in the exact order from the talk's
  "Order matters" pipeline: **(1)** `Idempotency-Key` lookup first — a recognised key short-circuits
  straight to a replay, skipping every gate below; a reused key with a different fingerprint is 422
  (`IdempotencyKeyReused`); **(2)** `If-Match` is mandatory for a move (428 via `PreconditionRequired`
  if absent, 412 via `StaleStateIdentified` if stale); **(3)** business invariants on `Line` (422 via
  `BusinessRuleViolated`); **(4)** the forced-increment version CAS at transaction commit (409, via
  the controller advice above). The idempotency record is staged in the *same* transaction as the
  mutation, so a lost CAS rolls both back together.
- **Idempotency storage**: `IdempotencyKey` (id = the client's `Idempotency-Key`, plus a fingerprint of
  `side:by`) is intentionally minimal — a replay re-reads the *current* line state rather than storing
  a full response snapshot, which is enough as long as nothing else touches the line between the
  original call and its retry.
- **Persistence**: `schema.sql` defines the H2 schema by hand (no Flyway/Liquibase); JPA entities map
  onto it directly. The app runs against an in-memory H2 database with no external services.
- `LineCommand` (`CreateLine`/`MovePoint`) is a sealed interface used to carry the create/move
  intent into `LineService`. `DeleteLine` is still an unused stub — delete takes its id/`If-Match`
  as plain parameters instead, since there was never a need to route it through a command object.
- **Frontend (`optimistic-locking-ui`)**: an Angular 20 (standalone components, hash-based routing)
  app built on `@quadrel-enterprise-ui/framework`. It lists `Line`s, shows one's details, and gates
  the delete action on the fetched resource's `_links.delete` HATEOAS link being present — a true
  server-computed visibility gate, not a client-side re-implementation of `line_#delete`. Built via
  `exec-maven-plugin` (`npm ci && npm run build`, output straight into `target/classes/static`) and
  consumed by `optimistic-locking-web` as a plain jar dependency, so Spring Boot's default
  static-resource handling serves it from `/`. Routing is hash-based (`/#/lines/:id`) specifically
  because `LineController` already owns the path `/lines/**`; a path-based Angular route there
  would collide with the real REST endpoint on a hard refresh.
- **Local dev auth (`shared/web/localdev/`)**: the backend's OAuth2 issuer is a documented
  placeholder (see `application.properties`), so nothing can normally obtain a bearer token to call
  `/lines/**`. Running with `-Dspring-boot.run.profiles=local` activates `LocalDevAuthConfig`
  (imports jeap's own `JeapOAuth2IntegrationTestResourceConfiguration` test support as real runtime
  beans) and `DevLoginController`, which mints demo JWTs for two fixed identities —
  `viewer` (`line_#read` for partner `acme`) and `administrator` (also `line_#delete`) — via
  `POST /dev/login?identity=...`. The Angular app's identity switcher calls this to demonstrate the
  delete button appearing/disappearing. `LocalDataSeeder` (same profile) seeds a handful of `acme`
  lines on startup so the list isn't empty. None of this is reachable without explicitly opting into
  the `local` profile.
