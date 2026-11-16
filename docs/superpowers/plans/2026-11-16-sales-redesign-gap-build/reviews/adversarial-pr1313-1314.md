# Adversarial review: PR #1314 (quotation flat list) and PR #1313 (release-note email reset)

**Where this report lives:** the agent's worktree isolation refused writes to `.claude/worktrees/whats-new/...`, so this file is in the session scratchpad instead.

**Setup:** worktree `agent-a8bcf76ae3c77f949`, detached at `origin/feat/quotation-flat-list` (909dceb1). PR B was read from `origin/fix/release-note-email-reset` (2189aa59).

**What I did not do:** no Maven run, no push, no GitHub comment. The scratch test and the mutations were reverted, and `git status --short` is empty.

---

## PR A: #1314. Verdict: FIX BEFORE MERGE

**Baseline:** `npx vitest run QuotationListPage.test.jsx quotationPrefs.test.js` passes 71/71 (58 + 13).

### A1. PROVEN: sorting the new "วันที่อนุมัติ" column does nothing

- **Where:** `QuotationListPage.jsx`, the `approvedAt` column: `sortable: true`, renders `<ApprovedDate row={row}/>`, no `sortAccessor`.
- **Why:** DataTable's `comparableFromRendered` reads `props.children`. `ApprovedDate` has no children, so every row compares as `''`.
- **Shown by:** a scratch test with approvals in Mar, Jan and Feb. The order `0003, 0002, 0001` was unchanged before the click, after one click and after two clicks. Sorting by ยอดรวม did reorder the rows.
- **Why it matters:** the PR claims "In the flat view columns sort again".
- **Fix:** `sortAccessor: (row) => (row.approvedAt ? Date.parse(row.approvedAt) : null)`, plus a test that clicks the header.

### A2. PROVEN: the เลขที่ header sort disagrees with the default order from revision -10 up

- **Why:** the header sort uses DataTable's `localeCompare(..., 'th')`, which is not numeric.
- **Shown by:** for revisions -9, -10 and -2, the default order is `-10, -9, -2`. Clicking the header gives `-10, -2, -9`, then `-9, -2, -10`.
- **Fix:** give the column a `sortAccessor` or a numeric comparator.

### A3. PROVEN: mutations the tests do not catch

| Mutation | What it means |
|---|---|
| M2 drop `numeric: true` | No fixture has a revision of 10 or more. |
| M17 flat view uses `GROUPED_COLUMNS` | Nothing pins sorting in the flat view. This is how A1 shipped. |
| M14 flat footer denominator +1 | The flat view's "(กรองจาก M)" count is never asserted. The count itself is correct: a scratch run showed flat 1/3 and grouped 2/5. |
| M12 id tie-break reversed | Low impact. |
| M6 drop the Bangkok conversion | Survives on an Asia/Bangkok machine; killed under `TZ=UTC`. CI sets no TZ. Pin TZ in the vitest config. |

### A4. PROVEN: pasting the total as printed finds nothing

- **Why:** `formatMoney` prints `฿5,444.80`. Pasting that string into search gives 0 rows.
- **Fix:** strip `฿`, spaces and NBSP from the query.

### A5. PROVEN, low: some one-character queries now match every row

- `.` and `,` match every row with a total.
- An exact total is not unique: `5,444.80` also matches `15,444.80`. The PR admits the substring behaviour; I find it acceptable for a free-text box, but this consequence should be documented.

### A6. JUDGEMENT: the ช่วงเวลา filter and the visible date disagree

The filter reads `quotationDate`, but the only visible date is `approvedAt`. Combined with A1, a user can neither filter nor sort by the date on screen. Label the filter with the date it uses, or filter on the visible date.

### A7. Minor: column sort survives a view switch

A column sort made in the flat view is still applied after switching to grouped and back. Meanwhile "เรียงตามเลขที่" stays pressed. The grouped view itself is not torn apart; verified.

### Attacked and held

- **Sorting by number:** mixed prefixes, null numbers, a base number without a revision suffix, and a year rollover all sort correctly.
- **Bangkok midnight:** `16:59:59Z` shows 15 พ.ย. and `17:00:00Z` shows 16 พ.ย.
- **Bad `approvedAt`:** an invalid or undefined value shows "—".
- **Odd totals:** `grandTotal` of null, 0, `'-12.5'` and `'abc'` are all safe.
- **Rounding:** `toFixed` and `toLocaleString` can only disagree at 3 or more decimal places, and totals are 2dp.
- **Storage:** corrupt, foreign or throwing storage all fall back to flat or stay in memory. A user without an id uses the `:anon` key.
- **Grouped view:** unchanged apart from the intended date and search changes. All 5 grouped describe blocks pass.
- **CSV export:** no side effect. `COLUMNS` is local to this page, and the page never enables CSV export.
- **Tests weakened:** only the "grouping always on" test was replaced, which is legitimate because that design was reversed.
- **Accessibility:** `role="group"` with an `aria-label`, `aria-pressed`, `focus-visible` styles, and a 44px minimum height on phone are all present. `flex-1` does nothing inside the non-full-width `inline-flex`; cosmetic only.

### Mutation results

| ID | Mutation | Result |
|---|---|---|
| M1 | Reverse the sort | Killed |
| M2 | Drop `numeric: true` | **Survived** |
| M3 | Grouped by default | Killed |
| M4 | Flat search ignores the total | Killed |
| M5 | Group search ignores the total | Killed |
| M6 | Drop the Bangkok conversion | **Survived** in the Bangkok TZ; killed under UTC |
| M7 | Show the document date when unapproved | Killed |
| M8 | Read the preference without the user id | Killed |
| M8b | Shared preference key (no user id) | Killed, but only via the helper's hard-coded key. The isolation test does not catch it. |
| M9 | Preference write is a no-op | Killed |
| M10 | No keyed (unformatted) total form | Killed |
| M11 | Keyed total form only | Killed |
| M12 | Reverse the tie-break | **Survived** |
| M13 | `aria-pressed` always false | Killed |
| M14 | Footer denominator +1 | **Survived** |
| M15 | Flat view unsorted | Killed |
| M16 | Old placeholder | Killed |
| M17 | Flat view uses `GROUPED_COLUMNS` | **Survived** |

---

## PR B: #1313. Verdict: FIX BEFORE MERGE (small)

All of PR B was reviewed by reading the code only; no Maven run, by instruction.

### B1. Reasoned: id ordering is sound only if the operator takes the advisory lock

- **Background:** `hr.audit_log.id` is IDENTITY (V18:5), so its value is assigned at insert time, not at commit. `email()` holds `pg_advisory_xact_lock(0x52454C4E, hashtext('email:'||id))`. The operator's INSERT does not.
- **Double send:** run T1 inserts rows 100–127. The operator inserts marker 128. Then T1 commits and sends. Every row of that run now falls below the marker, so the next click re-mails all 28.
- **Missed send:** a marker inserted in the middle of a failing run. The run's later rows get ids above the marker and survive, so those employees are never retried.
- **Fix:** ship this operator SQL in a javadoc or runbook:
  ```sql
  BEGIN;
  SELECT pg_advisory_xact_lock(1380273230, hashtext('email:<id>'));
  INSERT ...;
  COMMIT;
  ```
  Under READ COMMITTED, the email path's read runs after it acquires the lock, so it sees the marker. The test helper refers to a "runbook SQL" that is not in the diff.

### B2. Reasoned: MAX vs MIN survives the tests

- **Why:** every test inserts one marker per release.
- **Scenario the tests miss:** reset, re-send fails again, reset again. With `MIN`, a second retry is impossible.
- **Fix:** add a test for that sequence.

### B3. Reasoned: other SQL mutations

| Mutation | Result | Note |
|---|---|---|
| Drop the release predicate in the subquery | Killed | By `aResetForAnotherReleaseDoesNotReopenThisOne`, which is a real guard. |
| `>=` instead of `>` | Equivalent mutation | Ids are distinct, so no test can tell them apart. |
| Drop the `reset.entity` predicate | Survives | No test inserts a wrong-entity marker. Low impact. |
| Drop `COALESCE` | Killed | The existing at-most-once tests catch it. |

Tests to run once the database is free: `-Dtest=ReleaseNoticeIntegrationTest`, with each mutation applied in turn.

### B4. Design: the marker is all-or-nothing

- **What happens:** `NotificationEmailService.send` is `@Async` and only logs failures. After a partial failure, a reset re-mails everyone whose send succeeded. Whether partial failures are realistic (rate limits on parallel sends) is my suspicion, not something I observed.
- **Stronger alternative:** the async failure path appends a per-employee `RELEASE_NOTE_EMAIL_FAILED` audit row. Those employees then count as pending again. That stays append-only, needs no operator SQL, works per recipient, and keeps at-most-once for successful sends.
- **Minimum:** add a "use only when NO send succeeded" warning next to the constants.

### B5. Read code: no application path can forge a marker

All `AuditService.record` callers pass literal or internally derived actions (`AuthService:86`, `ProfileRequestService:101`), and no endpoint lets a caller choose the action, entity or JSON. Moderate confidence.

### B6. Read code: bad markers fail closed, silently

A null or non-object `after_json` evaluates `->>` to NULL, so the marker is ignored. A wrong entity, a wrong action, or a release id that is not normalised (`^[a-z0-9][a-z0-9-]{0,39}$`) is ignored the same way. That is safe but gives the operator no feedback. The runbook should also set `actor_email`.

### B7. Performance: not a concern

The subquery is uncorrelated, so it runs once (InitPlan). It can use `idx_audit_log_entity` on the `release_note` prefix. The outer scan over `entity='employee'`, which includes login rows, predates this PR.

### Proven vs suspected

- **Proven:** nothing; PR B was reviewed by reading only.
- **Reasoned:** B1, B2, B3, B5.
- **Suspected:** B4's claim that partial failures are realistic.
