# CaseWare take-home — Scope, decisions and user stories

Working document. The final deliverables (design document, code and README) are written separately.

## 1. Scope

**What it is:** a service that detects and reports pending template updates per engagement, offers a readable summary of the changes and records the decision (apply / decline).

**What it is not:** a document versioning system. Template versioning already exists.

### In scope

| # | Requirement |
|---|---|
| R1 | Show per engagement whether an update is pending |
| R2 | Change summary readable by non-technical users |
| R3 | Information always up to date |
| R4 | Reflect a publication within seconds |
| R5 | Work for existing engagements, not only new ones |
| R6 | Non-linear versions: market branches, withdrawn versions, a decline followed by a version that contains it |
| R7 | Several updates accumulated before deciding |
| R8 | Define what a decline pins |
| R9 | Summaries defensible months later before a regulator |
| R10 | Data residency (EU, Canada) |
| R11 | Total cost, inference included, under $X a month |
| R12 | Live systems, no maintenance window |

### Out of scope

- Applying the template content to the engagement.
- Template versioning and comparison between versions (they already exist).
- Executing apply / decline (the engagement system does it; we consume the notice).
- User interface (we only expose the information).

### Fixed constraints

- Reading an engagement's version requires opening it: ~1 minute per engagement.
- The engagement system belongs to another team and has limited capacity.
- The template database is shared and does not know about engagements.
- Engagement content is confidential and regional; template content belongs to no firm.

### Volume

| Item | Value |
|---|---|
| Firms | ~4,000 |
| Active engagements | ~800,000 |
| Products | ~40 |
| Engagements per firm | median ~40, maximum ~40,000 |
| Publications | ~1 per week per product |
| Budget | $X a month (not defined) |

## 2. Glossary

| Term | Meaning |
|---|---|
| Engagement (Engagement File) | Digital folder with all of a firm's audit work for one client for one year |
| Template (Product Template) | Base structure an engagement is created from; Caseware updates it |
| Index | Our table: engagement → template, base version and decisions |
| Pending | There is a valid version later than the engagement's that it has neither applied nor declined |
| Initial load | Opening each existing engagement once to record it in the index |
| Reconciliation | Periodic comparison of the index with the engagement system to detect lost notices |

## 3. Decisions taken

| # | Decision | Reason |
|---|---|---|
| D-01 | We keep our own engagement → version index, fed by creation, apply and decline notices | Avoid opening engagements (1 min) to learn their version |
| D-02 | A publication only records the "latest valid version" in the index; pending is computed on query | The cost of a publication does not depend on the number of engagements |
| D-03 | The engagement system queries the index when an engagement is opened, instead of receiving one notice per engagement | 40 products × 20,000 engagements = 800,000 calls/week ≈ 80 simultaneous at all times; it does not fit the other team's capacity |
| D-04 | The Part 2 component is generic: it processes lists of engagements against the slow system. Main use: initial load and engagements not yet recorded | Meets Part 2 and is consistent with D-02 and D-03 |
| D-05 | Summaries are generated once per version pair (template, from, to) and stored | Low cost and reusable across firms |
| D-06 | Requirement to challenge: "decide for each engagement". Propose a bulk decision per group (same template, market and version), keeping the individual record | A firm with 40,000 engagements would have to decide 40,000 times on the same change |
| D-07 | Delivery approach: this document is internal reasoning; the deliverable is 2 to 4 pages with decision, why and figure | The brief asks not to over-optimize and for a short document with clear decisions |
| D-08 | The Part 2 code is triggered by a publication and processes that template's unverified engagements; the README explains why not every engagement is notified | Delivers what was asked ("template-publish fan-out worker") and shows the reasoning of D-03 |

## 4. Story map (vertical slices)

| # | Story | Status |
|---|---|---|
| HU-01 | Pending indicator (new engagements, linear versions) | **Closed** |
| HU-02 | Apply / decline clear the indicator | **Closed** |
| HU-03 | Readable summary per update | **Closed** |
| HU-04 | Auditable, immutable summary | **Closed** |
| HU-05 | Accumulated updates as a single pending one | **Closed** |
| HU-06 | Offer again after a decline when a later version contains it | **Closed** |
| HU-07 | Withdrawn version | **Closed** |
| HU-08 | Market branches | **Closed** |
| HU-09 | Initial load of existing engagements | **Closed** |
| HU-10 | Bulk processing against the slow system (Part 2) | **Closed** |
| HU-11 | Reconciliation of lost notices | **Closed** |
| HU-12 | Data residency and isolation between firms | **Closed** |
| HU-13 | Observability, service targets and cost | **Closed** |

## 5. HU-01 — Pending indicator

**As** an auditor at a firm
**I want** to see which engagements have a template update available
**so that** I know where I have pending decisions without opening each engagement.

### Slice scope

- Only engagements created after deployment (existing ones arrive in HU-09).
- Linear versions: no branches or withdrawals.
- No summary (HU-03).

### Flow

```
"Engagement created" notice   ──► index: E1 → Pymes v5
"Version published" notice    ──► index: latest valid Pymes = v6   (a single write)
Firm query                    ──► compares each engagement's version with the latest valid → pending
Opening an engagement         ──► the engagement system asks the index about that engagement
```

### Acceptance criteria

```gherkin
AC1 – Recorded on creation
Given   the latest version of "Pymes" is v5
When    engagement E1 is created with "Pymes" v5
Then    E1 is in the index with version v5 and nothing pending

AC2 – Pending on publication
Given   E1 is on v5
When    "Pymes" v6 is published
Then    E1 shows pending v5 → v6 in under 30 s (95th percentile)
And     no engagement is opened

AC3 – The cost of publishing does not depend on the number of engagements
Given   20,000 engagements use "Pymes" v5
When    "Pymes" v6 is published
Then    the publication makes a fixed number of writes, however many engagements it affects

AC4 – No false pending
Given   E3 was created with "Pymes" v6
When    the notice "version published: Pymes v6" arrives late or repeated
Then    E3 shows nothing pending

AC5 – Independent templates
Given   E1 uses "Pymes"
When    "Limited Review" v9 is published
Then    E1 does not change

AC6 – Query per firm
Given   firm F has 40,000 engagements, 12,000 of them pending
When    it queries its pending updates
Then    it gets the total and a paginated list in under 300 ms per page (95th percentile)
And     no engagement is opened

AC7 – Query when opening an engagement
Given   E1 has pending v5 → v6
When    the engagement system asks about E1
Then    it gets "pending: v5 → v6"

AC8 – Repeated notices
Given   the notice "engagement created: E1" arrives twice
Then    the index is the same as if it had arrived once
```

### Decisions

| Decision | Value |
|---|---|
| Time from publication to visible pending | < 30 s, 95th percentile |
| Query time per firm | < 300 ms per page, 95th percentile |
| Consumers | UI (list per firm) and engagement system (one engagement when opened) |

## 6. HU-02 — Apply or decline clear the indicator

**As** an auditor at a firm
**I want** an engagement to stop showing as pending once I apply or decline an update
**so that** my list reflects only the decisions I still have to make.

### Slice scope

- Linear versions.
- Declining hides that version; a later version that contains it is HU-06.
- The engagement system carries out the change in the engagement (out of scope).

### Flow

```
User clicks "Apply v6" on E1
  → Notice "decision taken: E1 applies v6"   → index: E1 "applying v6" (🔄)
  → Notice "apply completed: E1 v6"          → index: E1 on v6, nothing pending (✅)
     or
  → Notice "apply failed: E1 v6"             → index: E1 back to pending v5 → v6 (🔔 + reason)

User clicks "Decline v6" on E1
  → Notice "decision taken: E1 declines v6"  → index: E1 on v5, declined v6, nothing pending (✅)
```

### Acceptance criteria

```gherkin
AC1 – Declining clears the indicator
Given   E1 is on v5 with pending v5 → v6
When    the notice "E1 declines v6" arrives
Then    E1 stays on v5, with v6 declined and nothing pending

AC2 – Applying shows an intermediate state
Given   E1 has pending v5 → v6
When    the notice "decision taken: E1 applies v6" arrives
Then    E1 shows as "applying v6" and does not count as pending

AC3 – Apply completed
Given   E1 is "applying v6"
When    the notice "apply completed: E1 v6" arrives
Then    E1 is on v6 with nothing pending

AC4 – Apply failed
Given   E1 is "applying v6"
When    the notice "apply failed: E1 v6" arrives with a reason
Then    E1 goes back to pending v5 → v6 and the reason is shown

AC5 – Apply that never finishes
Given   E1 has been "applying v6" for more than 30 minutes
When    reconciliation detects it
Then    the real state is requested from the engagement system, the index is corrected and an alert is raised

AC6 – Out-of-order notices
Given   E1 is already on v6 after a completed apply
When    an old notice "decision taken: E1 applies v6" arrives late
Then    it is ignored and E1 stays on v6

AC7 – Repeated notices
Given   the notice "E1 declines v6" arrives twice
Then    the index is the same as if it had arrived once

AC8 – Record of who decided
Given   any decision on E1
When    it is recorded
Then    who decided, what, on which versions and when is stored
And     that record is never modified or deleted
```

### Decisions

| Decision | Value | Reason |
|---|---|---|
| Apply notices | Three: decision taken, completed, failed | The user sees "applying" instead of a 🔔 for minutes |
| Notice order | Sequence number per engagement, set by the engagement system; any notice with a number lower than or equal to the last applied is ignored | Comparing times does not work: server clocks differ |
| "Applying" limit | 30 minutes, configurable | ~30 times normal, without leaving a false state for days |
| Undoing a decline | Outside this slice (A11) | The brief does not ask for it |
| Decision log | Ours, immutable, in the firm's region | Basis for defending the decision before the regulator (HU-04) |

## 7. HU-03 — Readable summary per update

**As** an auditor at a firm (non-technical user)
**I want** to read in plain language what changes if I apply an update
**so that** I can decide whether to apply or decline it without interpreting technical diffs.

### Slice scope

- One summary per version pair (template, from, to).
- Linear versions.
- Immutability and defence before the regulator: HU-04.

### Flow

```
Notice "version published: Pymes v6"
  → Pymes versions in use (index) → v4, v5
  → For each pair (v4→v6, v5→v6):
       1. Request the JSON diff from the template system
       2. Generate the basic rule-based summary (always)
       3. If a model is configured, generate the readable summary
       4. Verify the readable summary against the diff
       5. Store both
  → The UI shows the readable one if it passed verification; otherwise the basic one
```

### Format

Structured list: section, type (added / removed / modified), description and source (path in the JSON diff). The source is not shown to the auditor; it is used to verify and defend.

### Generators

```
ChangeSummarizer (contract)
 ├─ RuleBasedSummarizer    → fixed rules using the template's titles; always present
 ├─ BedrockSummarizer      → Claude on Amazon Bedrock (production)
 └─ LocalModelSummarizer   → our own open-weights model (development, or if a market requires it)
```

The basic summary plays three roles: working without a model, fallback when the model fails, and reference for verification.

### Acceptance criteria

```gherkin
AC1 – Summary available after publishing
Given   there are engagements on Pymes v5
When    Pymes v6 is published
Then    the v5 → v6 summary is generated in under 5 minutes (95th percentile)
And     it is generated only once, even with 20,000 engagements on v5

AC2 – Plain language
Given   the v5 → v6 summary
When    the auditor views it
Then    they see a list per section with the change type and a description, without technical paths or JSON

AC3 – Every item rests on a real difference
Given   a generated readable summary
When    it is verified
Then    every item points to at least one difference that exists in the JSON
And     if any item has no valid source, the summary is rejected

AC4 – Nothing is omitted
Given   a generated readable summary
When    it is verified
Then    every difference in the JSON is covered by some item
And     if any is missing, the summary is rejected

AC5 – There is always something to show
Given   the model fails, takes too long or its summary is rejected
When    the auditor views the summary
Then    they see the basic summary with a note that the detailed summary is not available
And     an alert is raised

AC6 – Indicator without waiting for the summary
Given   Pymes v6 is published
When    the summary is still being generated
Then    the pending update already shows (HU-01) with "summary in preparation"

AC7 – No client data
Given   any summary generation
Then    only template content is sent to the model, never engagement data

AC8 – Works without a language model
Given   no model is configured
When    Pymes v6 is published
Then    the basic v5 → v6 summary is generated and shown
And     no per-publication alerts or "summary not available" note are raised

AC9 – Model swappable by configuration
Given   the configured generator changes from Bedrock to a local model
When    the next version is published
Then    the summary is generated with the new model and passes the same verification
And     which generator and which model produced it is recorded
```

### Decisions

| Decision | Value | Reason |
|---|---|---|
| Production model | Claude on Bedrock: Haiku, and Sonnet only if Haiku fails verification | AWS; template content is not confidential |
| When to generate | At publication, for the versions in use | Ready when the auditor arrives; few pairs |
| Generation time | < 5 min, 95th percentile | The indicator already shows within 30 s |
| Language | The template market's language (A12) | Canada: English and French |
| Huge diffs | Summarise per section and merge; above a limit, only the basic summary per section | Model input limit and verification reliability |
| Content-team person | Not in this slice; proposed improvement: their own notes at publication as model input | They know the why; the model only sees the what |
| Basic summary | Mandatory; the model is optional | The system does not depend on AI |
| Local model | Allowed by configuration; recommended only for development or if a market requires it | ~500 summaries/month: pay-per-use is cheaper than a GPU left running. Compute with current prices |

## 8. HU-04 — Auditable, immutable summary

**As** an audit firm
**I want** to be able to prove months later which summary an auditor saw when deciding to apply or decline an update
**so that** I can defend that decision in a regulatory review.

### Data stored per summary

| Item | Purpose |
|---|---|
| Template, from version and to version | What was compared |
| Copy of the JSON diff used | Not depending on the template system months later |
| Hash of the diff and the summary | Proving nothing was altered |
| Exact text shown, per language | What the auditor saw |
| Generator (rules or model), exact model and version | How it was generated |
| Version of the model instructions | How it was asked |
| Original model response | Separating what the model said from what was shown |
| Verification result (HU-03 AC3, AC4) | That it was checked against the real changes |
| Generation date and time | When |

### Link to the decision

```
Decision: Laura · Ferretería Gómez · declines v6 · 14-Mar-2026 10:32 · summary R-8841
Summary R-8841: Pymes v5 → v6 · generated 07-Mar-2026 · Claude Haiku [version] · verified ✓
```

### Acceptance criteria

```gherkin
AC1 – What is stored cannot be modified or deleted
Given   a stored summary
When    any person or process tries to modify or delete it before its retention period ends
Then    the operation is rejected, even for administrators

AC2 – Improving a summary does not replace the previous one
Given   summary R1 exists for Pymes v5 → v6
When    an improved version is generated
Then    R2 is created as a new version and R1 stays unchanged
And     decisions taken with R1 keep pointing to R1

AC3 – The decision records the summary seen
Given   Laura sees summary R1 and declines v6
When    the decision is recorded
Then    the decision stores identifier R1

AC4 – Evidence pack
Given   a regulatory review of Ferretería Gómez
When    the firm requests the evidence for its update decisions
Then    it receives, for each decision: who, what, when, the exact summary shown,
        the diff used, the generator and model, and the verification result
And     every piece includes its hash

AC5 – Verifiable integrity
Given   a stored summary and its hash
When    the hash is recomputed over the content
Then    it matches the stored one; otherwise a critical alert is raised

AC6 – Withdrawn version
Given   v6 is withdrawn after Laura declined it with R1
Then    R1 is kept intact, marked "withdrawn version"
```

### Decisions

| Decision | Value | Reason |
|---|---|---|
| Retention period | 7 years, configurable per market (A13) | Usual for audit documentation; to be confirmed |
| Immutable storage | Amazon S3 with Object Lock in compliance mode + index in DynamoDB | Not even the root account can delete before the period ends |
| Summary seen | The UI sends its identifier with the decision; if missing, the current one is recorded marked "inferred" | Before a regulator, what was inferred must be stated |
| Region | Generated once and copied to each region that uses them | The evidence pack is assembled entirely within the region |
| Access to the evidence pack | Only firm users with a specific permission; every request is logged | It contains the firm's decisions about its clients |
| Logging views without a decision | No | No requirement asks for it |

## 9. HU-05 — Accumulated updates as a single pending one

**As** an auditor at a firm
**I want** several versions released before I decide to be presented as one update with the total change
**so that** I do not have to review and decide version by version.

### Why a direct diff and not a sum of summaries

| Change | v6 | v7 | Sum of summaries | Direct diff v5 → v7 |
|---|---|---|---|---|
| Inventory procedure | Added | Text corrected | "Added" + "modified" | "Added" (final text) |
| Petty cash checklist | Removed | Added back | "Removed" + "added" | Does not appear: no net change |
| Regulatory reference | No change | Updated | "Updated" | "Updated" |

### Presentation

```
Ferretería Gómez   🔔 Update available: v5 → v7
                      Includes 2 versions: v6 (Mon 3 Mar), v7 (Thu 6 Mar)
                      [View changes]  → summary v5 → v7
```

### Acceptance criteria

```gherkin
AC1 – A single pending update
Given   E1 is on v5
When    v6 and v7 are published with no decision taken
Then    E1 shows a single pending update: v5 → v7

AC2 – Summary of the net change
Given   v6 removes the petty cash checklist and v7 adds it back unchanged
When    the v5 → v7 summary is generated
Then    the petty cash checklist does not appear
And     the summary comes from the direct diff between v5 and v7

AC3 – Included versions
Given   the pending update v5 → v7
When    the auditor views it
Then    they see the list of included versions (v6 and v7) with their publication dates

AC4 – Summaries for every version in use
Given   there are engagements on v4 and on v5
When    v7 is published
Then    the summaries v4 → v7 and v5 → v7 are generated
And     earlier summaries are kept (HU-04)

AC5 – Decision while a new version is released
Given   Laura is viewing the v5 → v6 summary
When    v7 is published and Laura then clicks "Apply"
Then    the decision is recorded on v6, the version she saw, with its summary
And     once applied, E1 is on v6 with pending v6 → v7

AC6 – Declining the accumulated update
Given   E1 has pending v5 → v7
When    Laura declines it
Then    E1 stays on v5 with v7 declined and nothing pending
```

### Decisions

| Decision | Value | Reason |
|---|---|---|
| Version offered | Only the latest valid one | It is what the brief asks; choosing intermediate ones adds complexity without a requirement |
| Summary | Direct diff (net change) | Shows what will really change |
| Intermediate versions | Shown as information (number and date) | Context without separate decisions |
| Decision during a publication | Applies to the version the user saw | Applying something unseen breaks the defence before the regulator |
| Applying a version that is no longer the latest | Assumption: the engagement system can (A14); otherwise it is rejected, asking to review the new summary | The brief does not say |

## 10. HU-06 — Offer again after a decline

**As** an auditor at a firm
**I want** a version I declined to be offered again, with a warning, when a later version contains it
**so that** I can decide on what is new knowing it also brings what I declined.

### What a decline pins

Templates apply whole: a decline cannot exclude specific changes, only versions.

| Option | Meaning | Problem |
|---|---|---|
| A. The engagement | No more updates | Future regulatory changes are lost |
| B. The specific changes | Offer v8 without what came in v7 | Impossible: the template applies whole |
| C. Only that version | I do not want v7 | If v7 is withdrawn, v6 would be offered again, though it is contained in what was declined |
| **D. That version and its ancestors** (chosen) | I want neither v7 nor anything contained in v7 | None |

### Example

| When | Event | Ferretería Gómez (on v5) |
|---|---|---|
| March | Declines v5 → v7 | ✅ Nothing pending (boundary v7) |
| April | v7 is withdrawn | ✅ Nothing pending: v6 is inside what was declined |
| May | v8 is released | 🔔 v5 → v8 ⚠️ includes changes declined on 14 March |
| May | Declines v8 | ✅ Nothing pending (boundary v8) |

```
Update available: v5 → v8

⚠️ Includes changes you declined on 14-Mar-2026 (v7):
   • Adds the physical inventory count procedure (Assets)
   • Removes the petty cash review checklist (Cash)

New since your decline:
   • Adds the ESG risks section
   • Updates the reference to ISA 501 (2026)
```

Split: the sources (JSON paths) of the v5 → v8 summary are compared with those of the declined v5 → v7 summary (stored in HU-04).

### Acceptance criteria

```gherkin
AC1 – A later version is offered again
Given   E1 is on v5 and declined v7
When    v8 is published
Then    E1 shows pending v5 → v8

AC2 – Warning about what was already declined
Given   E1 declined v7 with summary R1
When    it views the pending update v5 → v8
Then    it sees the already-declined changes and the new ones separately, with the date of the earlier decline

AC3 – What was declined does not reappear after a withdrawal
Given   E1 is on v5 and declined v7
When    v7 is withdrawn and the latest valid version becomes v6
Then    E1 shows nothing pending

AC4 – The boundary advances with each decline
Given   E1 declined v7
When    it also declines v8
Then    the boundary moves to v8 and nothing is offered until a version later than v8

AC5 – Applying removes the boundary
Given   E1 declined v7
When    it applies v8
Then    E1 is on v8 and the earlier decline no longer affects pending updates
And     the decline record is kept (HU-04)

AC6 – A decline does not affect other engagements
Given   E1 and E2 are on v5 and only E1 declined v7
When    E2 is queried
Then    E2 still has pending v5 → v7
```

### Decisions

| Decision | Value | Reason |
|---|---|---|
| What a decline pins | The declined version and all its ancestors (option D) | The only workable one: loses no future changes and does not re-offer after a withdrawal |
| Warning about what was declined | Yes, split into "already declined" and "new" | The auditor must know that declined content reappears |
| Decline reason | Optional free text, stored with the decision (A15) | Before a regulator, the why is worth more than the what |
| Decline scope | Per engagement | The brief speaks of a decision per engagement |
| "Earlier" with branches | Resolved in HU-08: "is contained in" (ancestor in the tree) | This slice is linear |

## 11. HU-07 — Withdrawn version

**As** an auditor at a firm
**I want** not to be offered a version Caseware withdrew
**so that** I do not apply a template known to be faulty.

### Rule

The latest valid version is the most recent one not withdrawn. The "version withdrawn" notice recomputes it with a single write, without going through engagements (D-02).

### Cases (v7 is withdrawn)

| Engagement situation | Before | After | Reason |
|---|---|---|---|
| On v5, no decision | 🔔 v5 → v7 | 🔔 v5 → v6 | v6 becomes the latest valid |
| On v5, declined v7 | ✅ | ✅ | v6 is inside what was declined (HU-06) |
| On v6 | 🔔 v6 → v7 | ✅ | Nothing newer and valid |
| Already applied v7 | ✅ | ⚠️ "uses a withdrawn version" | Reverting content is out of scope; we only inform |
| Applying v7 | 🔄 | 🔄 and ⚠️ when done | We cannot cancel the apply |
| On v5, v8 released later | — | 🔔 v5 → v8 | Normal; v7 shows as "withdrawn" among the included versions |

### Acceptance criteria

```gherkin
AC1 – A withdrawn version is not offered
Given   E1 is on v5 with pending v5 → v7
When    v7 is withdrawn
Then    E1 shows pending v5 → v6 in under 30 s (95th percentile)
And     no engagement is opened

AC2 – No newer valid version, nothing pending
Given   E2 is on v6 with pending v6 → v7
When    v7 is withdrawn
Then    E2 has nothing pending

AC3 – Engagement on a withdrawn version
Given   E3 applied v7
When    v7 is withdrawn
Then    E3 shows the warning "uses a withdrawn version"
And     it is not offered a return to v6
And     when v8 is released, it is offered v7 → v8 as usual

AC4 – Apply in progress
Given   E4 is "applying v7"
When    v7 is withdrawn and then "apply completed: E4 v7" arrives
Then    E4 is on v7 with the warning "uses a withdrawn version"

AC5 – A summary that did not exist
Given   the v5 → v6 summary was never generated
When    v7 is withdrawn and there are engagements on v5
Then    the v5 → v6 summary is generated (HU-03)

AC6 – What was stored is kept
Given   the v5 → v7 summary exists and decisions were taken with it
When    v7 is withdrawn
Then    the summary and the decisions are kept, marked "withdrawn version" (HU-04)

AC7 – Withdrawal of an intermediate version
Given   E1 is on v5 and the latest valid is v7
When    v6 is withdrawn
Then    E1 still has pending v5 → v7
And     the list of included versions shows v6 as "withdrawn"

AC8 – Repeated or out-of-order notices
Given   the notice "version withdrawn: v7" arrives twice, or before its publication notice
Then    the final result is the same as if it had arrived once and in order
```

### Decisions

| Decision | Value | Reason |
|---|---|---|
| Engagement on a withdrawn version | Informational warning only | Reverting is out of scope; hiding it would be worse |
| Going back to the previous version | Not offered | That is applying content; the fix arrives as a new version |
| Undoing a withdrawal | No: a withdrawal is final (A16) | Avoids versions that appear and disappear |
| Decision on an already-withdrawn version | The engagement system must check before applying; if the notice reaches us, it is recorded and an alert is raised | The other system is the one applying |
| Creating with a withdrawn version | Out of scope; a recommendation to the engagement system | The other system does it |

## 12. HU-08 — Market branches

**As** an auditor at a firm
**I want** to be offered only versions from my market's branch
**so that** I do not receive changes meant for another country or region.

### Model

```
v1 ─ v2 ─ v3 ─────── v5 ─ v6 ─ v7        (Canada branch)
             \
              v4 ─ v8                    (EU branch)
```

| Before (line) | Now (tree or graph) |
|---|---|
| "v3 is earlier than v7" | "v3 is contained in v7": v3 is reached from v7 by following its parents |
| "The latest version" | "The latest valid version of its market's branch" |

### Offer rule

The latest valid version of the engagement's market branch is offered if:
1. it contains the engagement's version;
2. it is not withdrawn (HU-07);
3. it is not contained in any version the engagement declined (HU-06).

### Examples

| Engagement | Market | Version | Declined | Offered |
|---|---|---|---|---|
| E1 | Canada | v3 | — | v3 → v7 |
| E2 | EU | v3 | — | v3 → v8 |
| E3 | EU | v4 | — | v4 → v8 |
| E4 | Canada | v5 | v7 | Nothing |
| E5 | EU | v4 | — | Nothing if v9 is published in Canada |

Performance: ~40 products × ~52 versions a year; the graph fits in memory.

### Acceptance criteria

```gherkin
AC1 – Only the market's branch
Given   E5 is in the EU and on v4
When    v9 is published on the Canada branch
Then    E5 does not change

AC2 – Same version, different markets
Given   E1 (Canada) and E2 (EU) are on v3
Then    E1 shows pending v3 → v7 and E2 shows v3 → v8

AC3 – Summary across branches
Given   E2 is on v3 and is offered v8
When    the summary is generated
Then    the direct diff v3 → v8 is used
And     the list of included versions shows only those on the path from v3 to v8 (v4, v8)

AC4 – Decline with branches
Given   E4 (Canada) is on v5 and declined v7
When    v9 is published in Canada, containing v7
Then    v5 → v9 is offered with the warning about what was already declined (HU-06)

AC5 – Merge between branches
Given   v10 (EU) has two parents: v8 and v7 (Canada)
When    an EU engagement had declined v7
Then    v10 is offered with the warning that it includes declined changes

AC6 – Engagement on a version from another branch
Given   E6 is in Canada but its version is v4 (EU branch)
Then    E6 shows nothing pending, an alert is raised and it is flagged for review
```

### Decisions

| Decision | Value | Reason |
|---|---|---|
| Engagement's market | From the "engagement created" notice; if missing, the firm's; during the initial load, read when opening it | The version alone is not enough |
| Tree structure | From the "version published" notice, with parents and market (A17) | The brief does not say how branches are recorded |
| Merges | Supported: list of parents per version | Same cost; no rework if they appear |
| Market change | Not in this scope (A5) | The brief does not mention it |
| Version from another branch | Nothing pending, alert and manual review | Better not to offer than to offer the wrong thing |

## 13. HU-09 — Initial load of existing engagements

**As** an auditor at a firm with engagements created before this feature
**I want** to see the pending-update indicator on them too
**so that** I do not depend on the engagement having been created after deployment.

The engagement stores its creation version, but it may have applied or declined updates since: it has to be opened to learn its current version and its declines.

### Steps

1. **Listen first:** switch on the creation and decision notices before starting the load.
2. **Background load** with the HU-10 component: open → read current version, market and declines → record.
3. **Opportunistic load:** a new "engagement opened" notice when a user opens it to work; it is recorded at no extra capacity.
4. **Per-engagement switch, no big final cutover:** "unverified" → "verified".

| State | What the user sees |
|---|---|
| Unverified | "Checking for updates…" (never "up to date") |
| Verified | 🔔 or ✅ |

### Load ↔ notice race

The load's read includes the engagement's sequence number; the index only accepts writes with a number higher than the one it holds (same mechanism as HU-02).

### Order

| Priority | Engagements |
|---|---|
| 1 (urgent, 80%) | From templates with a recent publication |
| 2 | Recently used (if this can be known without opening them) |
| 3 | The rest |

Within each priority, firms take turns.

### Arithmetic

| Simultaneous calls | 800,000 engagements |
|---|---|
| 50 | 11 days |
| 50 by day + 200 by night | ~5 days |
| 100 | 5.6 days |

Minus whatever the opportunistic load contributes. Cost: 800,000 × the cost of opening an engagement (figure to request from the other team).

### Acceptance criteria

```gherkin
AC1 – Listen first
Given   the initial load has not started yet
When    the creation and decision notices are switched on
Then    every engagement created or decided from that moment is in the index without waiting for the load

AC2 – Full read of the current state
Given   E1 was created on v3, applied v5 and declined v6 before deployment
When    the initial load processes it
Then    the index records E1 on v5, with v6 declined, and its market

AC3 – The load does not overwrite newer data
Given   the initial load is reading E1
When    a more recent notice about E1 arrives before the load finishes
Then    the index keeps the notice's data and discards the load's

AC4 – Opportunistic load
Given   E2 is not verified
When    a user opens it to work
Then    E2 becomes verified with the data from that opening and the initial load no longer processes it

AC5 – Unverified engagement
Given   E3 is not verified yet
When    the user views their list
Then    E3 shows as "checking for updates", neither pending nor "up to date"
And     the firm sees how many engagements are verified out of the total

AC6 – Resuming and failures
Given   the initial load is interrupted
When    it resumes
Then    it continues where it was without repeating work (HU-10 AC6)
And     engagements that could not be read stay in failed with their reason

AC7 – No impact on normal use
Given   the initial load is running
Then    it never exceeds the agreed limit of simultaneous calls
And     it slows down automatically if the engagement system answers "I am overloaded"

AC8 – End of the load
Given   every engagement is verified or failed
Then    the load is considered finished and the failed ones go to review and to reconciliation (HU-11)
```

### Decisions

| Decision | Value | Reason |
|---|---|---|
| Listing engagements without opening them | Assumption: possible (A18) | Needed to start |
| Opening shows current version and declines | Assumption: yes (A19) | Otherwise already-declined updates would be offered again |
| "Engagement opened" notice | Yes | Free capacity; the brief allows adding notices |
| More capacity at night | Propose to the other team, configurable by time of day | Can halve the time |
| Unverified engagement | "Checking", never "up to date" | A false "up to date" is the worst error for an auditor |
| Archived engagements | Not loaded | The brief speaks of active ones |

## 14. HU-11 — Reconciliation of lost notices

The riskiest part of the design: a false "up to date" leads the auditor to skip a regulatory change.

| Where it is lost | Coverage |
|---|---|
| The source saves the change and crashes before notifying | Transactional outbox in the source systems |
| Transport loses or duplicates | At-least-once queue (SQS/EventBridge); idempotent processing |
| Our processor fails | Retries and a dead-letter queue with redrive |
| An engagement notice is missing | Sequence gap → "to verify" → re-read with HU-10 |
| A publication notice is missing | Comparison of the latest version per template every 5 min |

Extra safety nets: refresh on every opening (HU-09), daily sample of 800 engagements (0.1%), "applying" stuck > 30 min (HU-02).

```gherkin
AC1 – Sequence gap
Given   E1's last notice had sequence 5
When    one with sequence 7 arrives
Then    E1 moves to "to verify" and is re-read; meanwhile it shows "checking", never "up to date"

AC2 – Lost publication
Given   the publication notice for Pymes v8 did not arrive
When    the periodic comparison runs (every 5 minutes)
Then    v8 is recorded and the pending updates appear

AC3 – Sampling
Given   the daily sample of 800 engagements
When    the mismatch rate exceeds 0.1%
Then    an alert is raised
```

## 15. HU-12 — Data residency and isolation between firms

| Data | Where it lives |
|---|---|
| Index, decisions, declines and reasons | Only in the firm's region |
| Notice processors and the HU-10 component | One per region |
| Version tree and summaries | Generated once, copied to each region |
| Model calls | Any region (template content only) |
| Technical logs and metrics | In the region, without client data |

Rule: template data travels from global to the regions; firm data never leaves its region.

| Isolation between firms | How |
|---|---|
| Firm identity | From the authenticated token, never from a parameter |
| Partition key | Firm as the first component |
| Permissions | IAM condition `dynamodb:LeadingKeys` |
| Tests | Cross-firm reads must fail |

```gherkin
AC1 – Nothing leaves the region
Given   an EU firm
Then    its index, decisions and evidence pack are stored and processed only in the EU

AC2 – Isolation
Given   an authenticated user of firm A
When    they query pending updates naming firm B
Then    the request is rejected and logged
```

## 16. HU-13 — Observability, service targets and cost

| Target | Value |
|---|---|
| Publication → visible pending | < 30 s (p95) |
| Query per firm | < 300 ms (p95), 99.9% monthly availability |
| Summary available | < 5 min (p95) |
| Index accuracy | Mismatches in the sample < 0.1% |

| Metric | Alert if |
|---|---|
| Delay notice → record | > 30 s |
| Sequence gaps | Grow beyond normal |
| Unverified engagements | Do not drop during the initial load |
| Calls in flight / "I am overloaded" | Sustained saturation |
| Retry budget | > 10% |
| Summaries falling back to basic | > 5% |
| Summary integrity | Any mismatch (critical) |

Monthly cost ≈ our own infrastructure + summaries (~500/month) + recurring openings (~25,000/month); one-off: initial load (800,000 openings). The recurring cost is low and not dominated by AI; the big one is the initial load, and it depends on the other team's cost per opening.

## 17. HU-10 — Bulk processing of engagements against a slow system

**As** the pending-updates system
**I want** to process large lists of engagements by calling the engagement system (~1 minute per engagement, limited capacity)
**so that** each engagement's version is recorded in the index without overloading that system, without repeating work and without losing any if something fails.

### Uses

| Use | When | Action per engagement |
|---|---|---|
| Initial load | Once, for the ~800,000 existing ones | Open it, read its version and record it |
| Unrecorded engagements | A publication affects an engagement the initial load has not processed yet | The same, with priority |

### Out of scope

- Computing what is pending (HU-01).
- Generating summaries (HU-03).
- Real queue and storage: contracts with an in-memory implementation.

### Acceptance criteria

```gherkin
AC1 – Do not exceed the engagement system's capacity
Given   a configured global limit of 50 simultaneous calls
When    a job of 20,000 engagements is processed
Then    there are never more than 50 calls in flight at the same time

AC2 – Slow down if the engagement system is overloaded
Given   the engagement system answers "I am overloaded"
When    the component receives that answer
Then    it reduces simultaneous calls
And     raises them again gradually when answers are normal

AC3 – Do not repeat work
Given   engagement E1 was already processed for reason M1
When    the order to process E1 for M1 arrives again
Then    the engagement system is not called again
And     the result is the same as if it had arrived only once

AC4 – Retry only transient failures
Given   the call for E1 fails with a transient error
When    the component receives the failure
Then    it retries up to 3 attempts in total, with growing waits and random jitter
And     if the error is permanent, it does not retry and moves it to failed

AC5 – Set failures aside
Given   E1 runs out of attempts or fails with a permanent error
When    its last attempt ends
Then    E1 moves to the failed list with the reason
And     the rest of the job continues

AC6 – Resume after a crash
Given   a job of 20,000 engagements with 8,000 already processed
When    the component crashes and restarts
Then    it continues with the remaining 12,000 without calling again for the processed ones
And     those in flight at the time of the crash are retried

AC7 – Fair share between firms
Given   firm F1 has 40,000 engagements to process and firm F2 has 40
When    both jobs are running
Then    F2's engagements do not wait for F1 to finish

AC8 – Share between urgent engagements and the initial load
Given   there are urgent engagements (affected by a publication and not recorded) and initial load at the same time
When    50 simultaneous calls are shared
Then    about 40 are urgent and about 10 initial load
And     if there is only one type of work, that type uses all the capacity
And     calls in flight are never cut to reassign slots

AC9 – Visible progress
Given   a running job
When    its status is queried
Then    the counts of pending, in flight, completed and failed are returned

AC10 – Adjustable, validated configuration
Given   the component running with a limit of 50 simultaneous calls
When    the limit is changed to 20
Then    within 1 minute there are no more than 20 calls in flight, without restarting
Given   a configuration with a response wait of 8 min and a lease of 6 min
When    the component tries to apply it
Then    it rejects it, keeps the previous one and raises an alert

AC11 – Retry budget
Given   in the last 5 minutes retries exceed 10% of calls
When    another call fails
Then    it is not retried immediately; it is set aside for later and an alert is raised
```

### Parameters

| Parameter | Initial value | Change without restart | Reason |
|---|---|---|---|
| Connection timeout | 5 s | No | Detect outages fast |
| Response timeout | 3 min | Yes | No real data; tune to the measured 99.9th percentile. It must exceed the other system's internal time |
| Queue lease | ≥ 2 × response timeout (6 min) | Yes, validated | Prevent two workers from taking the same engagement |
| Simultaneous calls (global) | 50 as an example | Yes | To negotiate with the other team |
| Attempts | 3 in total | Yes | Each retry costs 1 minute of scarce capacity |
| Waits between attempts | ~30 s and ~2 min, with random jitter | Yes | Avoid synchronised retries |
| Retry budget | ≤ 10% of calls | Yes | Avoid retry storms |
| Share | 80% urgent / 20% initial load | Yes | Weighted fair share without wasting capacity |
| Share between firms | Round-robin | No | Very uneven firms (40 versus 40,000) |

**Implementation adjustments** (details in `fanout-worker/README.md`): the limit of 50 is per region (one active dispatcher per region); the retry budget is max(5, 10 % of calls); for AC10, from the change on no call starts above the new limit, and calls in flight drain as they finish (at most 3 min).

### Reference calculations

| Simultaneous calls | 20,000 engagements | 40,000 engagements | Initial load (800,000) |
|---|---|---|---|
| 20 | 16.7 h | 33 h | 28 days |
| 50 | 6.7 h | 13 h | 11 days |
| 100 | 3.3 h | 6.7 h | 5.6 days |
| 200 | 1.7 h | 3.3 h | 2.8 days |

### Future evolution

For ~1-minute operations, propose an asynchronous model to the other team ("start processing" → notice on completion). The component's contract is ready for that change.

### Industry references

- Amazon Builders' Library, "Timeouts, retries and backoff with jitter": timeout from a high percentile, waits with random jitter, retry budget.
- Kubernetes "API Priority and Fairness": capacity shares with borrowing between levels and round-robin within each level.
- Linux cgroups (CPU weights), Hadoop YARN (guaranteed and elastic capacity), Sidekiq (weighted queues).

## 18. Open ambiguities

| # | Ambiguity | Proposal |
|---|---|---|
| A1 | Value of $X | Treat it as a parameter and show cost as a formula |
| A2 | What a decline pins | Pending (HU-06) |
| A3 | What is offered with several accumulated versions | Pending (HU-05) |
| A4 | A pending update whose version is withdrawn | Pending (HU-07) |
| A5 | Can an engagement change market branch? | **Closed:** assumed not; it would need an explicit notice (HU-08) |
| A6 | What "seconds" means | **Closed:** < 30 s, 95th percentile (HU-01) |
| A7 | Who consumes the indicator | **Closed:** UI and engagement system (HU-01) |
| A8 | Summary per version pair or per engagement | **Closed:** per version pair (D-05, HU-03) |
| A9 | Users and queries at peak hour | Not in the brief; state the assumption |
| A10 | Can the engagement system query the index when opening an engagement? | Assumption behind D-03; state it in the document |
| A11 | Can a decline be undone? | The brief does not ask for it; out of scope unless confirmed |
| A12 | Summary language | Assumption: the template market's (Canada: English and French) |
| A13 | Retention period for summaries and decisions | Assumption: 7 years, configurable per market |
| A14 | Can the engagement system apply a version that is no longer the latest? | Assumption: yes; otherwise the decision is rejected and the new summary must be reviewed |
| A15 | Is a reason asked for when declining? | Proposal: optional; the brief does not ask for it |
| A16 | Can a withdrawal be undone? | Assumption: no; a fix always ships as a new version |
| A17 | How are each version's branches and parents recorded? | Assumption: the "version published" notice includes parents and market |
| A18 | Can existing engagements be listed without opening them? | Assumption: yes |
| A19 | Does opening an engagement show its current version and earlier declines? | Assumption: yes; otherwise there is a risk of re-offering what was declined |
