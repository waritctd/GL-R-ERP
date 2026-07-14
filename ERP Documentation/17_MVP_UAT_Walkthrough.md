# GL&R ERP — MVP Golden-Path Walkthrough

| | |
|---|---|
| **Document** | 17 — MVP Golden-Path Walkthrough |
| **Version** | 1.0 · 14 July 2026 |
| **Predecessor** | [14 — UAT Master Plan](14_UAT_Plan.md), Section "MVP First-Phase Flow" |
| **Audience** | Real GL&R business users being walked through UAT: HR, Sales, Import, Division managers, CEO, employees |
| **Environment** | `uat` branch, `docker-compose.uat.yml` (fresh seed), password `Uat@2026` for every account |

## Why this document exists

`14_UAT_Plan.md`'s "MVP First-Phase Flow" already defines the correct golden path — one continuous
thread from a new hire joining through to a correct paycheck, plus the sales-to-cash thread that
feeds it. This document is that same flow, **corrected against what's actually built today**, and
reshaped into a walkable checklist for running with real, non-technical users.

**What changed since the 5 July plan:**
- **Email notifications are no longer a gap.** The 5 July plan listed email as *"should move to
  future backlog"* / *"no email channel."* A full notification backbone shipped since (`V32`–`V36`),
  and as of this session all 10 email scenarios (commission, leave, OT, ticket, profile-request) pass
  against a real SMTP capture inbox. This walkthrough puts email checks back into the golden path —
  see the "✉ Email checkpoint" callouts below, plus a dedicated stage for the piece that was missing
  entirely (profile-request emails, the in-app notification bell).
- **Document format:** the plan states *"issues XLSX, not PDF."* The current code serves both
  `quotation-<id>.pdf` and `quotation-<id>.xlsx` — note this in stage 5 rather than assuming XLSX-only.

An interactive, checkbox-driven version of this same walkthrough (for actually clicking through
live with testers) is available as a Claude Artifact from this session.

---

## Sign-in accounts

| Email | Role in this walkthrough | Used in stage |
|---|---|---|
| `hr@uat.glr` | HR | 2, 3, 6, 7, 8 |
| `employee@uat.glr` | Employee | 1, 3, 8 |
| `divmgr@uat.glr` | Division manager | 3 |
| `sales@uat.glr` | Sales | 4, 5, 6 |
| `import@uat.glr` | Import | 4 |
| `salesmgr@uat.glr` | Sales manager | 4, 6 |
| `ceo@uat.glr` | CEO | 4, 6 |
| `nulldiv@uat.glr` | Null-division fallback | safety net only |
| `admin@uat.glr` | Superuser | not used in golden path |

Use a private/incognito window per role so two sessions (e.g. Sales + CEO) can stay open side by
side.

**One-line flow:** `login → HR builds workforce → time & requests (+ email) → sales deal (+ email) →
document → commission (+ email) → payroll → notifications double-check → safety net`

Run stages 1→8 as one continuous demo, stopping and fixing before continuing if any stage fails —
every stage after it assumes the ones before it worked.

---

## Stage 1 — Get in safely

**Who:** Any role. **Why it matters:** nobody can test anything else if login or role derivation is
broken.

- [ ] Log in as `employee@uat.glr` — expect: lands on the employee's own menu, not HR/admin.
- [ ] Log out, log back in as `hr@uat.glr` — expect: HR sees Employees, Payroll, and Approvals.

## Stage 2 — HR builds the workforce

**Who:** HR. **Why it matters:** every downstream module — attendance, OT, leave, payroll, role
access — keys off the employee record HR creates here.

- [ ] Add a new employee: full name, division, position — expect: employee code is generated
      automatically, not typed in.
- [ ] Edit that employee's division or position, then open their history — expect: the previous
      assignment is kept, not overwritten.

## Stage 3 — Time & requests

**Who:** Employee, then Manager/HR. **Why it matters:** approved OT and leave are direct inputs to
payroll — the approval step and its scoping (who can approve whom) both have to hold.

- [ ] As `employee@uat.glr`, submit a workday overtime request — expect: created SUBMITTED at 1.50×.
- [ ] Submit a vacation leave request within quota — expect: accepted immediately, balance reduces.
- [ ] As `divmgr@uat.glr`, approve the OT request — expect: status flips to APPROVED with payable
      minutes shown.

**✉ Email checkpoint:** the employee's inbox should now have two messages — one for the OT
submission, one for the leave auto-approval. Either missing is a real defect, not an accepted gap.

## Stage 4 — Sales deal → approval

**Who:** Sales, Import, Manager/CEO. **Why it matters:** the revenue thread — a pricing action being
wrongly blocked here was a real defect in the last test round; confirm it stays fixed.

- [ ] As `sales@uat.glr`, create and submit a ticket — expect: draft → submitted.
- [ ] As `import@uat.glr`, pick it up and propose a price — expect: succeeds, no 403.
- [ ] As `ceo@uat.glr` or `salesmgr@uat.glr`, approve the price — expect: ticket becomes approved.

**✉ Email checkpoint:** import and CEO inboxes get a "new price request" message on submit; the
sales rep's inbox gets an "approved" message after CEO sign-off. Try rejecting a second ticket too —
the import inbox should get a rejection message.

## Stage 5 — Issue the document

**Who:** Sales. **Why it matters:** the client-facing output — what GL&R actually hands a customer.

- [ ] From the approved ticket, preview and issue the quotation — expect: a running document number
      is assigned.
- [ ] Download it, then open a revision on the same document — expect: file downloads (PDF and Excel
      are both available); the original is retained after the revision.

## Stage 6 — Commission

**Who:** Sales, Manager/CEO. **Why it matters:** commission has to reach payroll, and only the right
approvers can sign off — sales cannot approve their own commission.

- [ ] As `sales@uat.glr`, submit a commission from the invoice — expect: amount computed from the
      tier table, status pending.
- [ ] As `salesmgr@uat.glr`, then `ceo@uat.glr`, approve it (both sign-offs) — expect: fully approved
      only after both approvals.
- [ ] As `hr@uat.glr`, confirm it appears in the payroll-ready feed — expect: visible against the
      correct employee and month.

**✉ Email checkpoint:** the sales rep's inbox gets a "commission submitted" confirmation as soon as
the first step lands.

## Stage 7 — Payroll — the payday

**Who:** HR. **Why it matters:** the convergence point — this single stage proves stages 2 through 6
actually flow into a correct paycheck. The single most important thing to get right.

- [ ] As `hr@uat.glr`, open the payroll preview for this month — expect: the employee's line shows
      base pay, the approved OT, and the approved commission together.
- [ ] Process the period, then download the bank export — expect: processed successfully; export
      downloads with the right figures.
- [ ] Try opening payroll as an employee or sales account — expect: blocked, HR-only.

## Stage 8 — Notifications & email — the full sweep

**Who:** HR, Employee. **Why it matters:** stages above already prove email fires on the core
thread. This stage closes the loop on what used to be a documented gap: profile-correction emails
and the in-app notification bell.

- [ ] As `employee@uat.glr`, submit a profile-change request (e.g. update a phone number) — expect:
      appears as pending; HR's inbox receives a "profile change requested" email.
- [ ] As `hr@uat.glr`, approve it — then submit and reject a second one — expect: the employee gets
      an approval email for the first, and a rejection email with the stated reason for the second.
- [ ] Open the notification bell as the employee — expect: an in-app entry for the profile decision,
      alongside the OT/leave activity from stage 3.
- [ ] Skim one of today's emails end to end — expect: a greeting with the recipient's name, a clear
      call-to-action link, and the GL&R HR signature — not a bare system dump.

---

## Safety net — check throughout, not just at the end

A payroll and PII system is unacceptable if its boundaries leak. These gate go-live as hard as the
payroll figure itself does.

- [ ] As `employee@uat.glr`, open your own profile — expect: no salary or PII of yourself or anyone
      else is visible.
- [ ] Try to open another employee's notification by guessing its link/ID — expect: denied, not
      silently allowed.
- [ ] With IT, check the audit log after the payroll process step — expect: `PROCESS_PAYROLL` and
      `EXPORT_PAYROLL_BANK_FILE` entries exist, with no salary figures written in clear text.

---

## Known, accepted gaps

Not blockers for this walkthrough:
- The physical attendance-device (SC700) tap — tested via record import instead; verified on-site
  during hypercare.
- The PDF visual render — checked by eye, not automated.
- The payroll parallel-run reconciliation against a manual payroll — its own dedicated session, see
  `14_UAT_Plan.md` Section 7.9 (UAT-PAY-09).

*End of document.*
