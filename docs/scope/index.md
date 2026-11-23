# Scope: GL&R ERP

The portal GL&R staff use for HR self service, payroll, and the sales flow from first contact to delivery and payment. It serves employees, HR, sales, import, warehouse, accounting and the CEO.

**Build approach:** Tracer Bullet (each change is built end to end through database, API and screen, thin but working, and shown running before the next one starts).
**Workflow:** Beta (after develop: check verify, then test). The project default level of rigor. `/architect` is the recommended first stop for a feature with a real decision, but skippable when you already know the build. Any feature can carry its own tag (e.g. `· GA`) to do more or less.

_These are recommendations to keep your build orderly, not requirements. Skip anything that does not fit: if you already know how to build a feature, use `/develop` and skip `/architect`. You decide when a feature is `done`._

Code pointers are short. `hr/<package>` means `backend/src/main/java/th/co/glr/hr/<package>/`, and `features/<name>` means `frontend/src/features/<name>/`.

## At a glance

| # | Feature | Phase | Status |
|---|---------|-------|--------|
| 1 | Sign in and sessions | Platform | existing |
| 2 | Roles, permissions and view as | Platform | existing |
| 3 | Notifications and mail | Platform | existing |
| 4 | Attachments and file storage | Platform | existing |
| 5 | Activity log and audit | Platform | existing |
| 6 | Feedback and What's New | Platform | existing |
| 7 | Document rendering | Platform | existing |
| 8 | Mock mode and demo data | Platform | existing |
| 9 | Deploy and operations | Platform | existing |
| 10 | Employee directory and profiles | HR | existing |
| 11 | Requests and approvals | HR | existing |
| 12 | Leave | HR | existing |
| 13 | Overtime | HR | existing |
| 14 | Attendance | HR | existing |
| 15 | People insights | HR | existing |
| 16 | Payroll runs and payslips | Payroll | existing |
| 17 | Tax allowance declarations | Payroll | existing |
| 18 | Special money | Payroll | existing |
| 19 | Commission | Payroll | existing |
| 20 | Customers and deal parties | Sales | existing |
| 21 | Deals and the stage pipeline | Sales | existing |
| 22 | Price requests and pricing decisions | Sales | existing |
| 23 | Quotations | Sales | existing |
| 24 | Order confirmation and deposit notice | Sales | existing |
| 25 | Sales board, my work and visits | Sales | existing |
| 26 | Dashboards and sales analytics | Sales | existing |
| 27 | Price catalogue and price import | Import and fulfilment | existing |
| 28 | Import requests and procurement | Import and fulfilment | existing |
| 29 | Delivery and tax invoice before delivery | Import and fulfilment | existing |
| 30 | Shipment rounds and lots | Import and fulfilment | in-progress |
| 31 | Deal payments and invoices | Finance | existing |
| 32 | Finance workspace and receipts import | Finance | existing |
| 33 | Add on orders | Slice 1 | planned |
| 34 | Document generators follow the quotation editor | Slice 2 | planned |
| 35 | Weekly report replacement | Slice 3 | planned |
| 36 | Import adds or moves a line on a contacted factory | Slice 4 | in-progress |

## Epics

- [Platform](platform.md): features 1 to 9, all existing.
- [HR](hr.md): features 10 to 15, all existing.
- [Payroll](payroll.md): features 16 to 19, all existing.
- [Sales](sales.md): features 20 to 26 existing, 33 to 35 planned, 36 in progress.
- [Import and fulfilment](import-fulfilment.md): features 27 to 29 existing, 30 in progress.
- [Finance](finance.md): features 31 and 32, both existing.

## Deferred

Out of scope for the current build pass, kept so the plan stays honest.
- **Visit plan and key accounts**: the last step of the weekly report work, after the report and the manager view.

## Legend

**The decision box.** Every feature carries exactly one, the sub-task whose label ends with `(spec)`. Its wording varies, so skills locate it by that `(spec)` suffix, never by an exact label. Every other box is an execution box and `/architect` never ticks one.

**Feature lifecycle**: the scope updates as a feature moves; each row is what it shows and who sets it:

| State | Set by | The feature shows |
|---|---|---|
| `planned` · needs a decision | `/scope` | one box: `Design it (spec): /architect <feature>` |
| `in-progress` (designed) | **`/architect` at spec capture** | `Design it` ticked; spec linked; `Build it: /develop <feature>` + **2 to 5 milestones**; the tier's closing boxes (`Verify it` Alpha+, `Test it` Beta+, `Review it` + `Document it` GA); any surfaced follow-up enrolled |
| `in-progress` (building) | `/develop` | milestone sub-boxes tick one by one; code pointer filled |
| `in-progress` (verified) | `/check verify` | `Build it` + milestones ticked; `Verify it` ticked |
| `done` | **you, when you decide it is** (any skill sets it when you say so); `/sync` reconciles | boxes you ran ticked, skipped ones marked skipped; the tier's last stage (`Prototype` → after `/develop`; `Alpha` → after `/check verify`; `Beta`/`GA` → after `/test`) is the suggested point to call it done; `/sync` captures conventions |

- **Next step** = the first unticked box (always a command or a tracked milestone).
- **needs a decision** = run `/architect` first; otherwise straight to `/develop`. The tag drops once the spec is captured.
- **Atomic build tasks live in the spec's `## Build plan`, not here**: the scope carries only the milestone rollup.
- **Status** `planned` → `in-progress` → `done`, plus `existing` (pre-workflow) and `dropped` (de-scoped, kept for history).
- **`existing` is not `done`**: it was built before this workflow, so `/develop` and `/sync` leave it alone. To change an existing feature, add a row for the change with `/scope <the change>`.
- **Approach tag** beside a heading (e.g. `· Facade`) overrides the project default for that feature; no tag = inherits it.
- **Workflow tier tag** beside a heading (e.g. `· GA`, `· Prototype`) sets that one feature's rigor above or below the project default; no tag inherits the default.
- **Workflow** (header line) is the project default, what runs after `/develop`: **Prototype** = nothing; **Alpha** = `/check verify`; **Beta** = `/check verify` then `/test`; **GA** = adds a fresh model `/check review` then `/document`. A feature built on an unratified decision (an `Assumed` spec) stays flagged, but that never blocks `done`.
- **Pointer line** (`spec <n> · code in <path>`): the spec link added by `/architect`, the code path by `/develop`.
