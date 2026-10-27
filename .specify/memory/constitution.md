# fanout-worker Constitution (CaseWare take-home)

Each principle states its strength (MUST mandatory, SHOULD recommended) and its scope. When two
principles conflict, the lower number wins, except V (Processing integrity) and IX (Safe
concurrency), which never yield. "HU" means user story (e.g. HU-10).

## Core Principles

### I. Readability first (NON-NEGOTIABLE)

Scope: all production and test code.

- MUST: each method has a single responsibility, ≤ 30 lines and cognitive complexity ≤ 10.
- MUST: a change to an existing method does not push its length or complexity over the limit; if
  it needs to grow, extract a method first.
- MUST: English identifiers that reveal intent; no abbreviations except domain ones.
- MUST: no exception is swallowed: no empty `catch`, and no `catch` that only logs and carries on
  without deciding what happens to the affected work.
- MUST: the code compiles without warnings (`-Xlint:all -Werror`, already in `build.gradle.kts`).
- SHOULD: comments explain why, not what.

Rationale: the reviewer judges the work in little time; the cost of understanding the code is
reading it.

### II. Simple first

Scope: all code.

- MUST: an interface exists only for an external boundary (queue, result store, index, engagement
  system, alerts) or when there are 2 real variants.
- MUST: a new design pattern is justified in the plan's *Complexity Tracking*: the problem present
  in the story and why the simple alternative was rejected.
- MUST: no runtime dependency outside the JDK; a new test dependency is justified in the plan.
- MUST: nothing is built outside the story's scope; anything found outside it is recorded in the
  spec.
- SHOULD: prefer the standard library (`java.util.concurrent`, `java.time`, `java.util.random`)
  over custom code.

Rationale: the brief says "do not over-optimize" and sets a ~4 h budget.

### III. Tests first (NON-NEGOTIABLE)

Scope: all production code.

- MUST: red, green, refactor cycle (`hu-flow:tdd-cycle`); the test is seen failing before
  implementing. Every bug starts with a test that reproduces it.
- MUST: levels: `unit` (domain and application without adapters) and `component` (the worker with
  the in-memory adapters, end to end).
- MUST: only an external boundary is replaced. The default double is the in-memory adapter;
  Mockito is allowed only on an output port, when the in-memory adapter cannot cause the case (e.g.
  a one-off failure). Our own logic is never mocked.
- MUST: deterministic tests: clock (`Clock`) and randomness (`RandomGenerator`) injected; no
  `Thread.sleep`; every wait in a concurrent test has a maximum time (`await` with timeout); no
  dependence on execution order.
- MUST: minimum cases per behaviour: happy path, empty, expected error and boundary (`<` versus
  `<=`).
- MUST: 100 % of tests green; line coverage ≥ 90 % (JaCoCo) and mutation score ≥ 90 % (PIT) on the
  story's code. An equivalent mutant is documented in `progress.md` with its reason.
- SHOULD: test names `<when>_<then>` in English, Arrange-Act-Assert, one reason to fail per test;
  AssertJ with exact values.

Rationale: the component is concurrent and retries; without deterministic tests, an intermittent
failure cannot be told apart from a real one.

### IV. Hexagonal design

Scope: all code.

- MUST: packages `domain` → `application` → `infrastructure.input` / `infrastructure.output`.
  `domain` imports nothing from the project outside itself; `application` does not import
  `infrastructure`.
- MUST: output ports live in `application`; their adapters (in-memory for the test) in
  `infrastructure.output`. Triggers (publish notice, initial load) live in `infrastructure.input`.
- MUST: no dependency-injection framework; constructor dependencies and explicit wiring.
- MUST: immutable values as `record`; closed results as `sealed` + exhaustive `switch`.
- SHOULD: the contract with the engagement system allows moving later to an asynchronous model
  ("start" → notice on completion) without touching `domain`.

Rationale: the real systems (queue, store, index, engagement system) do not exist in the test; the
design must allow replacing them without touching the logic.

### V. Processing integrity (NEVER YIELDS)

Scope: all code.

- MUST: no engagement disappears: every order ends completed, failed with a reason, or pending
  (deferred or with an expired lease).
- MUST: processing is idempotent per (engagement, reason): repeating an order neither repeats the
  call nor changes the result.
- MUST: every call to the engagement system has an explicit maximum wait; retries are bounded
  (count, growing delay with jitter) and happen only on transient failures.
- MUST: a call in flight is never cut to reassign capacity.
- MUST: confirmation order: first record the version in the index, then store the result, and
  finally confirm the order in the queue. A crash between steps leaves the order pending until its
  lease expires; repeating it is safe thanks to idempotency (at-least-once delivery).

Rationale: a lost or wrongly recorded engagement shows an auditor a false pending indicator; a
repeated call costs 1 minute of another team's capacity.

### VI. Security

Scope: all code and documents.

- MUST: no secret or credential in the repository, tests included.
- MUST: logs and alerts carry identifiers (engagement, firm, reason), never engagement content: it
  is confidential and regional.
- MUST: input from outside (notices, configuration) is validated at the boundary; inside, it is
  trusted.

Rationale: data residency and confidentiality are domain requirements (R10, HU-12).

### VII. Safe configuration

Scope: all new configuration.

- MUST: configuration is a `record` validated in its constructor, with safe `defaults()`.
- MUST: an invalid hot change is rejected, the previous configuration is kept and an alert is
  raised (AC10).
- MUST: parameters that cannot change at runtime are documented as such in the README.
- MUST: no environment variables or `.env` files in the test; where configuration lives in
  production is documented in the README.

Rationale: an inconsistent value (wait ≥ lease) makes two workers take the same engagement.

### VIII. Observability

Scope: all code.

- MUST: logs through the JDK's `System.Logger`; never `System.out`.
- MUST: every processing log line includes the job and engagement identifiers.
- MUST: alerts (saturation, retry budget, rejected configuration) go out through their own port,
  so they can be tested.
- MUST: each job's progress can be queried (pending, in flight, completed, failed).

Rationale: a load lasting days against another team's system is operated by watching progress and
alerts.

### IX. Safe concurrency (NEVER YIELDS)

Scope: all code that runs on more than one thread.

- MUST: slow calls run on virtual threads; the limit of simultaneous calls is enforced by our own
  resizable mechanism, not by a pool size.
- MUST: no `synchronized` around waits or I/O (in Java 21 it pins the virtual thread to its
  platform thread); use `ReentrantLock` or `java.util.concurrent` structures.
- MUST: no lock is held during the call to the engagement system; take the state, release the
  lock, call, and take it again to record the result.
- MUST: shared state is explicit: immutable, or guarded by a single mechanism documented in the
  class.
- MUST: every concurrent component has a test that exercises it with several threads and checks an
  invariant (e.g. maximum observed simultaneous calls ≤ limit).
- MUST: shutdown is orderly: stop taking orders, wait for calls in flight with a maximum time, and
  whatever is unconfirmed returns to pending when its lease expires.

Rationale: HU-10 is a concurrent component against a scarce system; concurrency bugs do not show
up in a single-threaded test.

## Technical constraints

- MUST: Java 21 (Gradle toolchain), Gradle 8.14.3 through the wrapper (`./gradlew`).
- MUST: tests with JUnit 5 + AssertJ; Mockito only as allowed by III. Coverage with JaCoCo,
  mutation with PIT.
- MUST: single module `fanout-worker/`, root package `com.caseware.templateupdates`.

## Development workflow

1. One branch per story from `main`: `hu/<HU>-<topic>`.
2. `hu-start` → `/speckit-specify` → `/speckit-clarify` → `/speckit-plan` (Constitution Check)
   → `/speckit-tasks` → `/speckit-analyze` (0 CRITICAL) → `/speckit-implement` per checkpoint
   with `hu-flow:tdd-cycle` → `hu-check` → `hu-close`.
3. Gates before closing a story, fastest to slowest: warning-free build, green tests, coverage
   ≥ 90 %, mutation ≥ 90 %. The first one that fails stops the rest.
4. Before each commit, the engineer reviews the full change to the AI-generated production code;
   approving the commit implies that review.
5. Commits: Conventional Commits in English, only with the user's explicit approval.
6. Every user correction is recorded in `progress.md` and in `deliverables/ai-usage-notes.md`.

## Governance

- The constitution overrides the Spec Kit templates. If it contradicts `CLAUDE.md`, one of the two
  is fixed in the same change.
- Every deviation in a story goes to the plan's *Complexity Tracking* with its reason.
- Semantic versioning: MAJOR removes or redefines a principle, MINOR adds a principle or section,
  PATCH clarifies wording without changing meaning.
- The user approves amendments; this file is edited, the version raised and the change summarised
  in the commit's impact report.

**Version**: 1.1.1 | **Ratified**: 2026-10-27 | **Last Amended**: 2026-10-27
