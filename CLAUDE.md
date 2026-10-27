# CaseWare — Staff Java Developer take-home

## Context
- Statement: PDF in this folder (not in git). Budget ~4 h in total; "do not over-optimize"; a short document with clear decisions.
- Part 1 (design): `deliverables/design-document.md`. Readers are Staff-level evaluators: a plain-language summary first, then the technical decisions and the arithmetic the brief asks for. At most 4 pages of text; diagrams do not count (measure it exported to PDF without images, A4 and Letter).
- Diagrams: PNG files in `deliverables/diagrams/`, each with its Mermaid source (`.mmd`) beside it. The document links the PNG so any Markdown viewer shows it. After editing a `.mmd`, regenerate its PNG:
  `npx -p @mermaid-js/mermaid-cli mmdc -i <file>.mmd -o <file>.png -b white -s 2`
- Part 2 (code): `fanout-worker/`, the fan-out worker triggered by a template publication (HU-10).
- AI usage notes: `deliverables/ai-usage-notes.md`.

## Development harness
- `hu-start`, `hu-check`, `hu-close` and `tdd-cycle` are skills of the `hu-flow` plugin, built separately (not part of the test) as a harness for application development: every story follows the same flow, with quality gates and memory across sessions.
- Flow: `hu-start` → Spec Kit (`/speckit-specify` → `clarify` → `plan` → `tasks` → `analyze` → `implement`, with `tdd-cycle` per task) → `hu-check` (quality gate) → `hu-close` (retrospective).
- Only the constitution (`.specify/memory/constitution.md`) is versioned. Spec Kit templates, `specs/` and `.claude/` stay local (ignored): process scaffolding, not deliverables.

## Where stories come from
- Backlog: `docs/backlog-and-decisions.md`, section `## N. HU-XX`.
- `hu-start` takes the story, acceptance criteria, out-of-scope items, parameters and decisions from there. It asks only what is still open.
- Global decisions (D-01…D-08) and ambiguities (A1…A19) are in the same file.
- Only HU-10 is implemented; the rest is design.

## Git
- Remote: `origin` = `github.com/roca3011/fanout-worker-caseware`. The final result lives on `main`; `hu/<HU>-<topic>` branches were used only during development.
- Commit and push only with the user's explicit approval. Conventional Commits in English, no co-author lines.
- Commit author: the GitHub noreply email (`git config user.email`); GitHub rejects pushes made with the personal email (GH007).
- `.gitignore` rules must be anchored to a path (`/fanout-worker/out/`, not `out/`): an unanchored `out/` once hid the `application/port/out/` package from git.

## Code conventions (Part 2)
- Java 21 (Gradle downloads it through the toolchain), Gradle wrapper, no Spring. Virtual threads for the slow calls.
- How to clone, test and open in an IDE: `fanout-worker/README.md`. Before saying "it runs", check a fresh clone outside this folder.
- In a synced folder (OneDrive, Dropbox), move `build/` out with `-PexternalBuildDir=<dir>`.
- Hexagonal: `domain/` → `application/` → `infrastructure/input/` + `infrastructure/output/`.
- Ports for the queue, the result store and the engagement system; in-memory adapters for the test.
- Tests: JUnit 5 + AssertJ; Mockito only on output ports (constitution III). Thresholds: 100 % green, coverage >= 90 %, mutation (PIT) >= 90 %.
- Technical rules: `.specify/memory/constitution.md` (versioned; overrides the Spec Kit templates).
- YAGNI: an interface only for external boundaries or once a second variant exists.

## Language
- Chat with the user: plain Spanish.
- Everything committed to the repository is English: code, identifiers, `deliverables/`, `docs/`, README, CLAUDE.md, the constitution and commits. Local, ignored files (`specs/`, `.claude/`) may stay in Spanish.

## AI usage
- Every user correction is recorded in the story's state and in `deliverables/ai-usage-notes.md` (a deliverable: English, one short sentence per column).
