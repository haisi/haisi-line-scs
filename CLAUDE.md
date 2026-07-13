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
`:8080` (see above) — no CORS configuration exists or is needed. `npm test` runs the Angular unit/
component tests via the `@angular/build:unit-test` builder on Vitest (Browser Mode, a real headless
Chromium via Playwright, configured in `angular.json`'s `test` target) — not Karma/Jasmine. It's a
regular Vitest suite: runnable from IntelliJ like any other test, watches by default in a TTY, and
runs once under `npm test -- --watch=false`. IntelliJ/WebStorm's own gutter "Debug" action does
*not* currently work for Vitest Browser Mode tests (a known upstream bug, unrelated to this repo's
setup) — to actually set a breakpoint and watch it hit live, use `npm run test:debug` instead: it
runs headed (a visible Chromium window, via the `debug` configuration in `angular.json`) and pauses
with Node's inspector listening (`--inspect-brk` under the hood) until you attach via
`chrome://inspect` (or WebStorm's "Attach to Node.js/Chrome" run configuration), then set
breakpoints in the attached DevTools' Sources panel — sourcemaps show the original `.ts`.

Component tests double as the fastest way to develop a component against fixed data without the
backend: e.g. `line-detail.component.spec.ts` fills `LineDetailComponent` via `TestBed` provider
overrides (`LineService`, `ActivatedRoute`, etc. — no HTTP mocking), asserts on the real rendered
DOM through `@vitest/browser`'s `page`, and calls `page.screenshot()` as a side effect of that same
test. Add another page/component to screenshot this way by adding another `*.component.spec.ts`
there, not a separate script.

`npm run lint` (or `npm run lint -- --fix`) runs ESLint — see `optimistic-locking-ui/eslint.config.js`
and the "Frontend code quality" section below for the exact rule set. `optimistic-locking-ui/pom.xml`
also binds it to `generate-resources`, right before the production Angular build, so any Maven
command that includes that module (a full reactor build, `-pl optimistic-locking-ui`, or `-am` from
`optimistic-locking-web`) fails on a lint error the same way it would fail on a compile error. The
`-pl optimistic-locking-web`-alone backend iteration loop described above never touches the UI
module at all, so it skips this too, same as it skips `ng build` itself.

**Always run `npm run lint` in `optimistic-locking-ui` after touching any frontend file, in the same
turn you make the change** — don't defer it to whenever the UI module next happens to get built. A
lint error left sitting silently blocks `./mvnw install -pl optimistic-locking-ui` from ever
producing a fresh jar, and the fast backend-only iteration loop above will happily keep resolving
that now-stale jar without complaint. That combination is exactly how the Line detail page once
shipped broken: the backend's API contract changed, the frontend was updated to match, but the
installed jar never refreshed because an unrelated lint error blocked the install — see
`check-line-detail.sh` below for the full story and the regression test that now guards against it.
A lint failure is not something to fix "later" in this repo.

`./mvnw package` (or `verify`/`install`) additionally screenshots the frontend into the API guide
(see the "Web UI" section of `index.adoc`) and runs a real end-to-end contract check, via three
`exec-maven-plugin` executions bound to `prepare-package` in `optimistic-locking-web/pom.xml`:
- `take-overview-screenshot.sh` boots the app under the `local` profile and screenshots the real,
  running Lines overview page — this one deliberately exercises the actual integration, landing in
  `generated-docs.directory/images` before the doc-rendering step embeds it.
- `check-line-detail.sh` also boots the app under the `local` profile, but asserts instead of
  screenshotting: it drives the real frontend bundle against the real backend's real HTTP responses
  and fails the build if the Line detail page doesn't render the data it should (see
  `optimistic-locking-ui/scripts/check-line-detail.mjs`'s own top comment). Exists because
  `line-detail.component.spec.ts`/`edit-line-dialog.component.spec.ts` mock `LineService` entirely
  (no real HTTP call, ever) and so cannot, by construction, notice the frontend and backend's JSON
  contract drifting apart — which is exactly what happened once: the per-point move API redesign
  changed `GET /lines/{id}`'s shape, the frontend was updated to match, but the *installed*
  `optimistic-locking-ui` jar the backend actually serves was never rebuilt (blocked, in that case,
  by an unrelated lint error elsewhere in the module — `./mvnw install -pl optimistic-locking-ui`
  fails outright if `npm run lint` fails, see the "Frontend code quality" section below), so the
  backend kept shipping the *old* frontend build against the *new* API and the detail page silently
  went blank. This check would have failed the build the moment that happened, instead of shipping
  it.
- `take-component-screenshots.sh` runs `optimistic-locking-ui`'s own `npm test -- --watch=false`
  (see above) and copies whatever `page.screenshot()` calls produced into
  `generated-docs.directory/images` — no backend, no JVM.

`./mvnw test` alone skips all three, same as it already skips the rest of the doc generation. This
needs a Playwright-installed Chromium
(`cd optimistic-locking-ui && npx playwright install chromium`, one-time) in addition to Node/npm.

There is no separate lint command configured for the backend; code style follows the
palantir-java-format IntelliJ settings checked into `.idea/`. The frontend's ESLint setup is
described above and in "Frontend code quality" below.

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
- **Service layer** (`LineService`): where optimistic-concurrency decisions are made (idempotency is
  a cross-cutting, filter-level concern now — see below, not this service). `create` is a PUT-style,
  client-generated-UUID create: identical retries are a no-op replay (200), a different body for an
  existing id is a genuine 409 (no precondition was ever stated for create). `move` (private, called
  by `moveLeft`/`moveRight`) runs gates in the exact order from the talk's "Order matters" pipeline:
  **(1)** `If-Match` is mandatory for a move (428 via `PreconditionRequired` if absent, 412 via
  `StaleStateIdentified` if stale); **(2)** business invariants on `Line` (422 via
  `BusinessRuleViolated`); **(3)** the forced-increment version CAS at transaction commit (409, via
  the controller advice above).
- **Idempotency (`shared/web/IdempotencyFilter`, `shared/idempotency/`)**: a generic, protocol-level
  mechanism, not baked into `LineService`/`LineController` at all — the cross-cutting concern the
  talk's `Idempotency-Key` section demonstrates now lives entirely in one `Filter`, wired into
  `ApiSecurityConfig`'s `apiSecurityFilterChain` after `BusinessPartnerFilter`. Support is opt-in per
  endpoint via the `@Idempotent` annotation (only `LineController`'s four move endpoints carry it)
  — matching Stripe's own idempotency API, which explicitly excludes GET/DELETE ("idempotent by
  definition, the header has no effect"); an unannotated endpoint ignores the header entirely, even
  if a client sends it, rather than defaulting new endpoints into this behavior by accident. The
  filter resolves the target `HandlerMethod` itself via `RequestMappingHandlerMapping` (the same
  technique Spring Security's own `MvcRequestMatcher` uses to inspect a handler before dispatch) to
  check for the annotation. For an opted-in request carrying the header, it fingerprints it (SHA-256
  of method + URI + `X-Partner-Id` + caller subject + body), and either replays a stored response,
  rejects a fingerprint reuse (422,
  `IdempotencyKeyReused`) or an in-flight duplicate (409, `IdempotencyKeyInUse`), or lets a fresh
  request through and stores what it produced (only 2xx responses; a non-2xx outcome abandons the
  reservation instead of freezing a failure forever). `IdempotencyService.reserve` claims a key by
  insert-first-wins on the `idempotency_record` table's primary key (`IdempotencyRecord` implements
  `Persistable` specifically so `save()` always attempts a real INSERT rather than a JPA `merge`,
  which an assigned, non-`@GeneratedValue` id would otherwise get — see that class's Javadoc); a
  concurrent racer's constraint violation is what produces the 409. A replay never invokes
  `LineController`/`LineService` at all, so it still can't duplicate a `ManualOperation` audit entry
  — the same property the old, `Line`-embedded version had, now achieved architecturally rather than
  by convention. **Trade-off worth knowing**: this moves the idempotency record's write out of the
  business mutation's own transaction (unlike the old design) — `reserve` commits before dispatch,
  `complete`/`abandon` commit after, so a crash in between can leave a stale reservation; a retry
  then re-executes for real rather than replaying, but still lands on the ordinary 412 path (not a
  double-apply) since the version CAS above remains the actual safety net. `IdempotencyHousekeeping`
  (`shared/web/`) deletes records older than 24h hourly, guarded by ShedLock (`@SchedulerLock`, see
  `SchedulingConfig`) so only one instance runs it when scaled horizontally — its `shedlock` table
  shape matches jeap's own `jeap-messaging-outbox` library's convention, even though this repo
  doesn't depend on it.
- **Persistence**: `schema.sql` defines the H2 schema by hand (no Flyway/Liquibase); JPA entities map
  onto it directly. The app runs against an in-memory H2 database with no external services.
- **Logging & tracing**: `jeap-spring-boot-monitoring-starter` (Micrometer Tracing bridged to
  OpenTelemetry, actuator, Prometheus) plus the transitively-included `jeap-spring-boot-logging-starter`
  (MDC bridge) and `jeap-spring-boot-rest-request-tracing` (`RestRequestTracer`, one summary log
  line per request/response) give every request its own `traceId`/`spanId` in the MDC of every log
  line it causes. `application.properties` turns on `RestRequestTracer`'s `DEBUG` logging (silent by
  default) and samples every request (`management.tracing.sampling.probability=1.0`, vs. Spring
  Boot's own 1-in-10 default) so the concept stays visible at this app's low traffic volume; see the
  "Non-functional concern: logging & tracing" section of the API guide (`index.adoc`) and
  `LineControllerITTest.LoggingAndTracing` for the executable proof (a Logback `ListAppender`
  attached to the `RestRequestTracer` logger around a real HTTP call).
- `LineCommand` (`CreateLine`/`MovePoint`) is a sealed interface used to carry the create/move
  intent into `LineService`. `DeleteLine` is still an unused stub — delete takes its id/`If-Match`
  as plain parameters instead, since there was never a need to route it through a command object.
- **Frontend (`optimistic-locking-ui`)**: an Angular 20 (standalone components, hash-based routing)
  app built on `@quadrel-enterprise-ui/framework`. It lists `Line`s, shows one's details, and gates
  the delete action on the fetched resource's `_links.delete` HATEOAS link being present — a true
  server-computed visibility gate, not a client-side re-implementation of `line_#delete`. Built via
  `exec-maven-plugin` (`npm ci && npm run lint && npm run build`, output straight into
  `target/classes/static`) and consumed by `optimistic-locking-web` as a plain jar dependency, so
  Spring Boot's default static-resource handling serves it from `/`. Routing is hash-based
  (`/#/lines/:id`) specifically
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

## Frontend code quality

`optimistic-locking-ui/eslint.config.js` (ESLint 9 flat config, scaffolded via
`ng add @angular-eslint/schematics` and hand-extended) is deliberately stringent: `@typescript-eslint/no-explicit-any`
is an error (on top of `strictTypeChecked`'s own `no-unsafe-*` rules, which catch `any` leaking in
from untyped call sites even where it's never written explicitly), and `strictTypeChecked` +
`stylisticTypeChecked` (typescript-eslint's type-aware rule sets, enabled via `projectService: true`
so they pick up the right `tsconfig` per file) run alongside `angular-eslint`'s `tsRecommended` and
`eslint-plugin-unicorn`'s `recommended` set. `eslint-config-prettier` sits last in the `extends`
chain to defer all formatting to the project's existing Prettier config (see `package.json`)
instead of fighting it.

Airbnb's config was deliberately **not** used: `eslint-config-airbnb-typescript` and its Angular
shims are still eslintrc-format packages with no first-party flat-config or ESLint 9 support, and
their rules are largely plain-JS/React style conventions that `eslint-config-prettier` +
`stylisticTypeChecked` already cover better for a TypeScript/Angular codebase.

A handful of `unicorn`/`typescript-eslint` defaults are turned off or reconfigured directly in
`eslint.config.js`, each with a comment explaining why — e.g. `unicorn/no-null` (Angular/RxJS/the
DOM use `null` idiomatically), `unicorn/prevent-abbreviations` (renames established short names
like `params`), `unicorn/prefer-top-level-await` (this project's default Angular CLI browser
support matrix predates it), and `@typescript-eslint/no-extraneous-class` with
`allowWithDecorator: true` (a `@Component`/`@Injectable`-decorated class with no members of its own
is normal Angular, not the anti-pattern the rule otherwise catches). Treat any future rule-disable
the same way: a one-line comment on *why*, not just *that*.

`npm run lint -- --fix` auto-fixes what it can; anything left needs a real code change (see recent
git history for examples — e.g. replacing a non-null assertion on a route param with a function
that throws a clear error instead of asserting past a `null`).
