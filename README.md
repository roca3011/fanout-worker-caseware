# Pending Template Updates — Caseware take-home

Design and partial implementation of a service that shows auditors which engagement files have a
pending template update, with a plain-language summary of what changes.

## Deliverables

| # | Deliverable | Where |
|---|---|---|
| 1 | Design document (Part 1) | [`deliverables/design-document.md`](deliverables/design-document.md) |
| 2 | Template-publish fan-out worker (Part 2), with its README | [`fanout-worker/`](fanout-worker/) · [`README`](fanout-worker/README.md) |
| 3 | AI usage notes | [`deliverables/ai-usage-notes.md`](deliverables/ai-usage-notes.md) |
| 4 | Diagrams (PNG, with editable Mermaid sources) | [`deliverables/diagrams/`](deliverables/diagrams/) |

## Run the tests

Requires any JDK 17 or newer; Gradle and JDK 21 are downloaded on first run.

```bash
git clone https://github.com/roca3011/fanout-worker-caseware.git
cd fanout-worker-caseware/fanout-worker
./gradlew test        # macOS / Linux / Git Bash
gradlew.bat test      # Windows
```

Expected: `BUILD SUCCESSFUL`, 222 tests green, in 1–2 minutes. To open it in an IDE, open the
`fanout-worker` folder (not this root) as a Gradle project; details in the
[worker README](fanout-worker/README.md#open-in-an-ide).

## Repository layout

| Path | Content |
|---|---|
| `deliverables/` | Design document, AI usage notes, diagrams |
| `fanout-worker/` | Java 21 library with in-memory adapters and tests (no framework) |
| `docs/backlog-and-decisions.md` | Working notes: scope, decisions (D-01…D-08), user stories with acceptance criteria, open ambiguities |
| `.specify/memory/constitution.md` | Engineering rules applied to the code (readability, tests first, hexagonal design, safe concurrency) |
| `CLAUDE.md` | Project instructions for the AI coding assistant (Claude Code) |
