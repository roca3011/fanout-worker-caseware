# AI Usage Notes

Where AI helped, where it was corrected or overruled, where not to trust it in this domain, and how I would guide other engineers.

## Tool

Claude Code (Claude Sonnet and Opus) for analysis, story refinement, design writing, test-first coding and review. A personal workflow plugin gave every user story the same steps (specify, plan, break into tasks, implement test-first, quality check); it is not part of the deliverable.

## Time spent

Analysis, design and code took the ~4-hour budget. About 1 more hour went into delivery polish, done afterwards and folded into the original commits: the design document rewritten in plain language within 4 pages, diagrams exported as images, a build fix found by testing a clean clone, and all documents translated to English.

## Where it helped

- Reading the brief and spotting the traps: a 1-minute load per engagement, non-linear versions, data residency, a decision questioned 8 months later.
- Doing the arithmetic for capacity, first-load time and cost, which exposed infeasible designs early.
- Explaining the 80/20 split with a worked example and industry references when the requirement was unclear.
- Writing the code test-first: 222 tests, 99.8 % line coverage, 96 % of mutants killed.
- Applying the workflow's review rules to the design, which showed it depended on another team's system without saying so.
- Declining to quote model prices from memory; costs use current published prices.

## Where it was corrected or questioned

| Phase | What happened | Lesson |
|---|---|---|
| Analysis | Scope grew to 13 stories and 60+ acceptance criteria against a 4-hour budget, until the engineer asked whether the brief was being followed | AI widens scope by default; bound it against the brief |
| Design | The first design updated every engagement on each publish; the arithmetic gave ~80 loads running at all times, above capacity | Always ask for the arithmetic: a plausible design can be infeasible |
| Design | A 3-minute timeout ("three times normal") was proposed without justification | Ask for the reason behind every default value |
| Design | 5 retries were proposed, then cut to 3: each retry spends a scarce minute of the engagement system | Check AI defaults against the domain's constraints |
| Process | The AI first evaluated the wrong tool, a kit built for another project | Confirm which tool the user means before evaluating it |
| Process | Citing the time limit, the AI skipped agreed workflow steps and wrote the spec and tasks by hand | AI must not cut an agreed process on its own; it proposes and asks |
| Process | The AI dropped six engineering rules as "not applicable"; on review, four did apply | "Not applicable" must be justified rule by rule |
| Code | The first automated review ended in 7 s with no detail because the code was not committed; the AI reported it as unverified rather than passed. The second found 2 real issues, one citing a rule the reviewer had not read | Check every finding against the code and the rule it cites; a fast, empty review is not evidence |
| Delivery | The diagrams were valid but rendered badly in IntelliJ: crossed text, invisible arrows in the dark theme | Check output in the reader's viewer; diagrams now ship as images with their source beside them |
| Delivery | Making the design readable added a glossary, diagrams and collapsible blocks: 11 pages against a 4-page limit | Measure against the delivery criteria after every change |
| Delivery | To fit 4 pages, the AI cut diagrams and technical detail without re-reading the brief, which says diagrams do not count and asks for the arithmetic; the readers are Staff-level evaluators | Re-read the brief before trading content for length |
| Delivery | The build was declared working after local runs only. A clean clone did not compile: an ignore rule meant for IDE output had hidden a source package, the build script was not executable on macOS/Linux, and JDK 21 could not be downloaded | "It works" is verified on a clean clone, away from the development machine |

## Where not to trust AI in this domain

- Change summaries shown to auditors without checking them against the real change (invented or missing items).
- The regulatory or audit impact of a change: that is professional judgement.
- Costs, legal retention periods and standards: verify against sources.
- Assumptions about other teams' systems (capacity, events, contracts): confirm with those teams.

## How I would guide other engineers

- Ask the AI for the arithmetic and explicit assumptions, not just the design.
- Check every default number against the domain's constraints.
- Use the AI for alternatives and edge cases; the engineer makes the decision.
- In code, tests first for concurrency, idempotency and failures; reject concurrent code without tests.
- Verify the result where someone else will use it: their viewer, a clean clone, the delivery limits.
