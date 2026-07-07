# GL&R ERP — UAT Test Cases

| | |
|---|---|
| **Document** | 11 — User Acceptance Test Cases |
| **Version** | 1.0 · 2 July 2026 |
| **Audience** | QA, business testers |
| **How to run** | Execute each case in order; record Pass/Fail and notes. Use the demo environment (`Demo@2026` accounts, one per role) or a seeded test DB. |

---

## Table of Contents

1. [Test Approach](#1-test-approach)
2. [Authentication & Access](#2-authentication--access)
3. [Employee Management](#3-employee-management)
4. [Attendance](#4-attendance)
5. [Overtime](#5-overtime)
6. [Leave](#6-leave)
7. [Sales Tickets & Documents](#7-sales-tickets--documents)
8. [Commission](#8-commission)
9. [Payroll](#9-payroll)
10. [Security & Authorization](#10-security--authorization)
11. [Sign-off](#11-sign-off)

---

## 1. Test Approach

- **Roles under test:** `employee`, division manager, `hr`, `sales`, `sales_manager`, `ceo`, `import`.
- **Pass criteria:** actual result matches expected; no error dialog; audit entries written where noted.
- **Priority:** 🔴 must-pass for go-live · 🟡 should-pass.

## 2. Authentication & Access

| ID | Priority | Steps | Expected result |
|---|---|---|---|
| AUTH-01 | 🔴 | Log in with valid credentials | Session established; role-appropriate menu shown |
| AUTH-02 | 🔴 | First login with a temporary password | Forced change-password screen; cannot proceed until changed |
| AUTH-03 | 🔴 | Enter wrong password several times | Account locks out temporarily (rate limiting) |
| AUTH-04 | 🔴 | Change password via menu | New password works; old one rejected next login |
| AUTH-05 | 🟡 | Log in, then redeploy/restart backend | Still logged in (sessions persisted in DB) |
| AUTH-06 | 🔴 | Log out | Session invalidated; protected pages redirect to login |

## 3. Employee Management

| ID | Priority | Steps | Expected result |
|---|---|---|---|
| EMP-01 | 🔴 | HR creates a new employee (all sections) | Record saved; employee code auto-generated |
| EMP-02 | 🔴 | HR edits assignment (division/position) | Change saved; prior assignment kept in history |
| EMP-03 | 🔴 | HR searches/filters by division & status | Correct subset returned; pagination works on large lists |
| EMP-04 | 🔴 | Employee views own profile | Sees own data; cannot directly edit |
| EMP-05 | 🔴 | Employee submits a profile-change request | Appears under My Requests as `pending` |
| EMP-06 | 🔴 | HR approves the request | Master record updated; status `approved` |
| EMP-07 | 🟡 | HR rejects a request with a note | Status `rejected`; note visible to employee |
| EMP-08 | 🔴 | HR opens a sensitive (PII/salary) field | Access recorded in `hr.audit_log` |

## 4. Attendance

| ID | Priority | Steps | Expected result |
|---|---|---|---|
| ATT-01 | 🔴 | Tap a card on the SC700 with the agent running | Punch appears in the portal shortly after |
| ATT-02 | 🔴 | Employee opens Attendance | Sees only own punches |
| ATT-03 | 🔴 | Division manager opens Attendance | Sees own division's punches |
| ATT-04 | 🔴 | HR imports a `.dat` file | Punches backfilled; import file + any row errors recorded |
| ATT-05 | 🟡 | HR rotates the device token | New token works; old token rejected (401/403) |
| ATT-06 | 🟡 | Upload an oversized `.dat` | Rejected by size cap |

## 5. Overtime

| ID | Priority | Steps | Expected result |
|---|---|---|---|
| OT-01 | 🔴 | Employee submits a workday OT request | Created `SUBMITTED`, multiplier 1.50 |
| OT-02 | 🔴 | Employee submits a holiday OT request | Multiplier 3.00 |
| OT-03 | 🔴 | Manager approves an OT request | Status `APPROVED`; payable minutes recorded |
| OT-04 | 🔴 | Approved OT appears in that month's payroll preview | OT pay included on the employee's line |
| OT-05 | 🟡 | Employee cancels a pending request | Status `CANCELLED` |
| OT-06 | 🟡 | Submit OT with end before start | Rejected (validation/DB constraint) |
| OT-07 | 🔴 | Manager tries to approve OT outside their division | Not permitted |

## 6. Leave

| ID | Priority | Steps | Expected result |
|---|---|---|---|
| LV-01 | 🔴 | Submit vacation leave within quota | Accepted; balance reduced |
| LV-02 | 🔴 | Submit leave exceeding remaining quota | Immediately rejected with reason |
| LV-03 | 🔴 | Submit sick leave without attachment | Blocked — attachment required |
| LV-04 | 🔴 | Manager/HR approves a leave request | Status approved |
| LV-05 | 🟡 | Employee cancels a pending leave | Status cancelled; balance restored |
| LV-06 | 🟡 | View leave types & balances | Correct quotas (Sick 30 / Vacation 6 / Personal 3) |

## 7. Sales Tickets & Documents

| ID | Priority | Steps | Expected result |
|---|---|---|---|
| TKT-01 | 🔴 | Sales creates & submits a ticket | Status `draft` → `submitted`; event logged |
| TKT-02 | 🔴 | Import/manager picks up & proposes a price | `in_review` → `price_proposed` |
| TKT-03 | 🔴 | Sales manager approves the price | `approved`; event logged |
| TKT-04 | 🟡 | Sales manager rejects the price | Returns for re-pricing |
| TKT-05 | 🔴 | Edit items after submission | `has_edits` flagged for approver |
| TKT-06 | 🔴 | Issue a quotation/document | Draft → issued; running number assigned; file downloadable |
| TKT-07 | 🔴 | Open a revision on an issued document | `revision_no` increments; original retained |
| TKT-08 | 🟡 | Add a comment to a ticket | Comment recorded as an event |
| TKT-09 | 🔴 | Close the ticket | Status `closed` |

## 8. Commission

| ID | Priority | Steps | Expected result |
|---|---|---|---|
| COM-01 | 🔴 | Sales submits a commission from invoice details | Amount computed from tier config; status pending |
| COM-02 | 🟡 | Use the simulator before submitting | Shows expected commission; nothing saved |
| COM-03 | 🔴 | Sales manager approves | Status approved |
| COM-04 | 🔴 | Approved commission appears in payroll-ready feed | Aggregated per employee/month |
| COM-05 | 🟡 | Record a clawback | Negative record offsets future payroll |

## 9. Payroll

| ID | Priority | Steps | Expected result |
|---|---|---|---|
| PAY-01 | 🔴 | HR previews payroll for a month | Every active employee listed with full breakdown |
| PAY-02 | 🔴 | Verify OT + commission on lines | Approved OT and commissions included |
| PAY-03 | 🔴 | Verify SSO & withholding tax | SSO on capped base; progressive tax via annualized calc |
| PAY-04 | 🟡 | Enter special pays / allowances / unpaid-leave days | Net pay recalculates correctly |
| PAY-05 | 🔴 | Process the period | Saved with processor identity; audit entry `PROCESS_PAYROLL` |
| PAY-06 | 🔴 | Download the bank export | `glr-payroll-<id>.txt`; audit entry `EXPORT_PAYROLL_BANK_FILE` |
| PAY-07 | 🔴 | **Parallel-run reconciliation** vs. manual payroll for one cycle | Figures match within tolerance (go-live gate) |

## 10. Security & Authorization

| ID | Priority | Steps | Expected result |
|---|---|---|---|
| SEC-01 | 🔴 | Employee calls an HR-only endpoint | 403 Forbidden |
| SEC-02 | 🔴 | Employee requests another person's data | Denied / scoped out |
| SEC-03 | 🔴 | Sales (non-manager) approves a price | Denied |
| SEC-04 | 🔴 | Post a punch without a valid device token | Rejected |
| SEC-05 | 🟡 | Request an unknown API route | 404 (not 500) |
| SEC-06 | 🟡 | Inspect response headers | CSP, HSTS, X-Frame-Options, nosniff present |

## 11. Sign-off

| Role | Name | Date | Result |
|---|---|---|---|
| Business owner | | | ☐ Pass ☐ Fail |
| HR lead | | | ☐ Pass ☐ Fail |
| Sales lead | | | ☐ Pass ☐ Fail |
| IT / technical | | | ☐ Pass ☐ Fail |

> **Go-live gate:** all 🔴 cases pass **and** PAY-07 parallel-run reconciles for one full pay cycle before production go-live (proposal week 8).

*End of document.*
