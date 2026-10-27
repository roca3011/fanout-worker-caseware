# fanout-worker

Template-publish fan-out worker (Part 2). It loads engagements from the slow engagement system
(~1 min per load) to record their template version, without exceeding the concurrency cap
negotiated with the owning team, without repeating work and without losing any engagement on
failure. Plain Java 21 library, no framework; in-memory adapters stand in for the queue, the
result store, the engagement index and the engagement system.

## Quick start

Requirements: any JDK 17 or newer to start Gradle, and internet access the first time. The Gradle
wrapper downloads Gradle 8.14.3, and Gradle downloads JDK 21 for compiling and testing if it is not
installed. Nothing else is needed.

```bash
git clone https://github.com/roca3011/fanout-worker-caseware.git
cd fanout-worker-caseware/fanout-worker
./gradlew test        # macOS / Linux / Git Bash
gradlew.bat test      # Windows (cmd or PowerShell: .\gradlew.bat test)
```

Expected: `BUILD SUCCESSFUL` with every test green, in about 1-2 minutes. Test report:
`build/reports/tests/test/index.html`.

On Windows, if the clone fails with `Filename too long`, run `git config --global core.longpaths true`
and clone again.

## Open in an IDE

Open the **`fanout-worker` folder** (not the repository root) as a Gradle project. The IDE then
imports the build, and tests run from the editor.

| IDE | Requirement | How |
|---|---|---|
| IntelliJ IDEA | Bundled Gradle plugin | *File → Open* → `fanout-worker/build.gradle.kts` → *Open as Project* |
| VS Code | *Extension Pack for Java* and *Gradle for Java* | *File → Open Folder* → `fanout-worker` |
| Eclipse | *Buildship* (bundled in Eclipse IDE for Java Developers) | *File → Import → Gradle → Existing Gradle Project* → `fanout-worker` |

If the IDE reports a wrong Java version, set the project's Gradle JVM to 17 or newer (IntelliJ:
*Settings → Build Tools → Gradle → Gradle JVM*).

## Run

Shown with `./gradlew`; on Windows use `gradlew.bat`.

```bash
./gradlew test        # unit + component tests; also writes the coverage report
./gradlew pitest      # mutation score; skips the slower @Tag("component") tests
```

| Report | Threshold | Location |
|---|---|---|
| Tests | 100 % green | `build/reports/tests/test/index.html` |
| Coverage (JaCoCo) | >= 90 % | `build/reports/jacoco/test/html/index.html` |
| Mutation (PIT) | >= 90 %, fails the build below it | `build/reports/pitest/index.html` |

```java
var settings = new SettingsHolder(FanOutSettings.defaults());
var worker = FanOutWorker.create(queue, results, index, engagementSystem, alerts, settings,
        Clock.systemUTC(), new SecureRandom());   // one virtual thread per call
worker.start();
new TemplatePublishedListener(new PublishService(queue, index)).onTemplatePublished(templateId, "v8");
new EnqueueService(queue).enqueueInitialLoad(engagements);        // backfill
new ProgressQuery(queue, results, clock).progress(new TemplatePublished(templateId, "v8"));
new SettingsReloader(settings, alerts).reload(() -> readSettingsFromParameterStore());
worker.close();                                                  // waits for calls in flight, up to 30 s
```

Layout: `domain/` (values, pure policies) → `application/` (dispatcher, item processor, limiter,
ports in `port/out/`) → `infrastructure/input/` (publish listener, settings reloader) and
`infrastructure/output/` (in-memory adapters). The logic has no threads; only `FanOutWorker.run()`
and the executor handoff do.

## Key tradeoffs

| Concern | Chosen | Rejected, and why |
|---|---|---|
| Downstream capacity | Own `ConcurrencyLimiter` over an AIMD limit: an "overloaded" answer halves the cap once per generation of calls (50 → 25), then it climbs back one step at a time | `Semaphore` (cannot shrink below held permits); halving on every answer (50 → 1 instantly) |
| Urgent vs backfill | Urgent gets `max(1, round(limit × share))` (0 if share is 0); either type takes 100 % when alone; calls in flight are never cut | Hard partition (wastes capacity) |
| Fairness | One FIFO per firm, round-robin | Single FIFO (a 40,000-engagement firm blocks a 40-engagement one) |
| Idempotency | One entry per engagement, reasons merged; confirmation removes only the reasons seen at claim time | Removing the whole entry (loses a reason that arrived mid-read) |
| Stale reads | `annotate(id, version, seq)` ignored unless `seq` is higher | Last write wins; wall-clock ordering |
| Crash recovery | Lease with token and expiry; a stale token makes `ack` return `false` | Deleting on claim (a crash loses the engagement) |
| Retries | 3 attempts, backoff ~30 s / ~2 min with ±20 % jitter; a retry budget parks failures for 5 min and alerts, without spending an attempt | Immediate retries; a budget without a floor |
| Hot reload | `reload(Supplier<FanOutSettings>)`: an invalid candidate is rejected, the previous settings stay, `SETTINGS_REJECTED` fires | `reload(FanOutSettings)` (an invalid one cannot be built) |
| Threads | Executor injected; virtual threads in production | Threads inside the logic (slow, non-deterministic tests) |

Patterns used: adaptive concurrency limit (AIMD), weighted share that uses idle capacity,
round-robin across firms, lease-based work queue, idempotent processing with `seq`-guarded writes,
backoff with jitter plus retry budget, confirm against a snapshot, leader election per region,
functional core with an imperative shell.

## Deviations from the acceptance criteria

| AC | Deviation | Why |
|---|---|---|
| AC10 | "No more than 20 in flight within 1 minute" holds as: from the change on, no call starts above the new limit; calls in flight are not cut and drain as they finish (normally < 1 min, worst case the 3 min response timeout) | Cutting a call does not relieve the engagement system, which keeps loading on its side; a response timeout under 1 min would expire normal calls and add load |
| AC11 | "Retries exceed 10 % of calls" reads "exceed max(5, 10 % of calls)" | With few calls one failure is 100 %: every start-up would raise a false alarm |

## Configuration (`FanOutSettings`, hot-reloadable)

| Parameter | Default | Rule |
|---|---|---|
| `maxConcurrency` | 50 | >= 1; per region |
| `urgentShare` | 0.80 | [0, 1] |
| `responseTimeout` / `leaseDuration` | 3 min / 6 min | > 0 and `responseTimeout` <= `leaseDuration` / 2 |
| `maxAttempts` / `retryDelays` | 3 / 30 s, 2 min | >= 1 / positive, non-decreasing, >= `maxAttempts - 1` entries |
| `retryBudgetRatio` / `Window` / `MinRetries` | 0.10 / 5 min / 5 | (0, 1] / > 0 / >= 0 |

The 5 s connection timeout belongs to the real HTTP adapter's constructor and is fixed at startup.

## Production notes

- **Stores**: one Aurora PostgreSQL per region holds the work queue (`SELECT … FOR UPDATE SKIP LOCKED`,
  merge with `ON CONFLICT`), the results (written in the same transaction as the ack) and the
  conditional index update (`UPDATE … WHERE seq < :seq`). SQS only carries the publish notice.
- **One dispatcher per region**: the cap of 50 is per region. Instances elect one active dispatcher
  with `pg_try_advisory_lock`. Not implemented here. Rejected: a shared counter in the database
  (a write per call, expiring slots) and splitting the cap between instances (idle share is wasted).
- **Real engagement-system adapter**: maps network errors, HTTP status and timeouts to
  `Transient` / `Permanent` / `Overloaded`; owns the connection timeout.
- **Future work**: a circuit breaker on top of the retry budget, to stop dispatching while the
  engagement system is down.
