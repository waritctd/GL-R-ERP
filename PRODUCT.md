# Product

## Register

product

## Platform

web

## Product Purpose

GL-R-ERP is GL&R's internal operations portal — an HR + Sales/CRM system growing toward a broader ERP platform. It is not a complete ERP yet, and it is no longer an HR-only tool: the sales/CRM stack (deals, pricing, quotation, deposit, commission, catalog, procurement, fulfilment) is a live part of the current release line alongside the HR core (employees, attendance, leave, overtime, payroll, profile, auth, dashboards).

Its job is to **coordinate operational work as it moves between roles** — the handoffs, approvals, records, and documents that make up a real business day. A deal passes from a rep to a pricer to the CEO to account; a leave request passes from an employee to a manager; payroll passes from attendance reconciliation to a pay run to statutory exports. The portal's purpose is to make each of those handoffs legible: who holds it now, what state it is in, and what the next person must do.

## Users

Internal GL&R staff across the whole operation — not one admin persona, but a chain of roles that hand work to each other. Design must serve every link, not favor the desk-bound administrator. The primary language is Thai; English is co-present. Both must read correctly in the same layout, at the same size.

## Primary roles

- **Sales reps** — open deals, request pricing, chase quotations and deposits, track their own pipeline. Own their deals only.
- **Import / procurement** — cost landed goods, respond to pricing requests, place factory orders, reconcile quoted vs actual cost.
- **CEO / owner** — the final gate on pricing, overtime, and exceptions; the second signature on deal close. Sees the whole board.
- **Account** — confirms money in and out: deposits received, billing, final payment, close-out; records invoices and commission on closed deals.
- **HR / payroll administrators** — run payroll, reconcile attendance, manage employees, process leave and overtime; oversight (not approval) of OT/leave.
- **Managers (division / department heads)** — approve overtime, leave, and attendance exceptions for their team; some approve within the sales chain.
- **Warehouse / QC** — record inbound and inspection steps that gate delivery, on a factory-floor or shared device.
- **Employees** — check in/out, request leave, view payslips, update profile — mostly from a phone.

## Primary jobs

- **See the work waiting on me** and tell it apart from work waiting on someone else ("whose move is it?").
- **Move a record one confident step** to the next hand — submit, price, approve, confirm, record — without leaving the system.
- **Approve or reject** a submitted request (leave, OT, pricing, commission, close) with the evidence needed to decide, and a reason when rejecting.
- **Run a correct pay period** — reconcile attendance, process payroll, produce statutory exports.
- **Create and progress a deal** through pricing → quotation → deposit → procurement → delivery → close.
- **Self-serve routine HR tasks** (leave, payslip, profile) on a phone.
- **Find and read a record's state, history, and documents** to answer a question or resolve an exception.

## Operational risks

The design must reduce, never amplify, these:

- **A wrong number in a money field** — payroll, pricing, commission, deposit, landed cost. Figures must be unambiguous, aligned, and scannable; a mis-read is expensive.
- **Work stalling in a handoff** — a record whose next holder can't tell it is theirs, or can't see the one action to take, silently blocks the chain.
- **Acting on a stale or already-decided record** — there is no optimistic locking in the backend (no `@Version`); two approvers, or one acting from an old tab, are caught only by a state-guard 409/422. The UI must surface *already-decided* and refetch on focus, not invite a doomed action.
- **A user attempting an action the backend will 403** — the UI must classify "mine to act" using the *same* role gates the Java services enforce, never a looser rule.
- **A mis-tap on the factory floor or phone** committing the wrong record — real touch targets and deliberate confirmation on consequential actions.
- **A Thai-primary user forced to act on an English verb** on a money button, or a clipped/wrapped Thai label hiding information.

## Typical device contexts

- **Desktop / office laptop (1366–1440+)** — the primary design target for dense admin work: payroll, reconciliation, deal lists, pricing queues. Payroll's densest surfaces (the 30 × 15 ประกันสังคม and ภาษีหัก ณ ที่จ่าย matrices) are designed here first.
- **Payroll on phone (≤720) — added 2026-07-29, owner decision.** Payroll is no longer desktop-only. It reflows rather than shrinking: the matrices become one employee per screen with a vertical toggle list, because fifteen columns of 44px checkboxes on a phone is how a tax setting gets mis-tapped. Phone is fully editable, so the mis-tap risk below applies to payroll too — real touch targets and deliberate confirmation on anything consequential.
- **Tablet / half-width laptop (721–1040)** — managers on an iPad or a split window. Currently the weakest surface (the shell breaks here); a first-class band to design for.
- **Phone (≤720)** — the surface the most people touch most often: employee self-service, sales on the move, approvals for mobile-heavy CEO/managers. Designed as reflowed flows, not shrunk desktop.
- **Factory-floor / shared device** — warehouse and QC recording inbound/inspection: big targets, few fields, unambiguous status.

## Thai-first requirement

Thai is the primary language and real content, not a translation afterthought. Every layout must read correctly in Thai *and* English at the same size, with line-height that fits Thai ascenders and descenders, correct diacritic spacing, and label widths that survive both scripts. Load-bearing verbs (the buttons that run payroll, approve a price, confirm money) are Thai-first; English may appear only as a helper subtitle, never as the sole label on a consequential control.

## Sensitive-data concerns

The portal holds salary, payroll, tax, personal (PII), commission, pricing, and cost/margin data. Design and evidence must respect that confidentiality is enforced by the backend and is **role-scoped**:

- **Salary / payroll / PII** is HR-scoped (not finance or account).
- **Cost and margin** on a deal are visible only to import and CEO; the sales rep sees the customer-facing price, never the raw cost/margin. A surface must not leak a figure a role cannot see.
- **A draft pricing request** is visible only to its owning rep plus CEO/sales_manager.
- Screenshots, demos, and tests use **mock/synthetic data only** — never real salary figures, PII, or customer data. Permission behaviour is verified against the real Java service, never inferred from the mock (which is known to be more permissive).

## Positioning

The one place where every role can see the work that is waiting **on them** — and move it one confident step to the next hand — without leaving the system to email, phone, or spreadsheet around it.

## Brand Personality

**Calm. Reliable. Efficient.**

Quiet confidence around money, records, and handoffs. The voice is plain, direct, and Thai-first — no marketing gloss, no cleverness near payroll, pricing, or personal data. Where the interface has personality, it shows as precision and predictability, not decoration. Delight lives in a well-placed empty state or a fast, obvious flow, never in ornament.

The creative north star is an **Operations Control Desk**: a single, orderly surface where a coordinator sees the state of the whole operation and dispatches the next move with confidence. "Control desk," not "control room" — this is a calm, well-lit daytime workspace where operators stay in command of the work, not a dark mission-control cockpit dramatizing it. The desk gives control through clarity and legibility, never through density-for-its-own-sake or alarm.

## Anti-references

This should NOT look or feel like:

- **Marketing website / landing page** — hero sections, scroll choreography, persuasion copy. This is a tool people work in, not a page they are sold to.
- **Flashy SaaS demo / gradient-heavy startup dashboard** — random gradients, neon "tech startup" colors, decorative animation, marketing choreography. Wrong tone next to payroll and pricing.
- **Banking or crypto app** — dramatized figures, dark-glass trading-desk styling, ticker theatrics, portfolio-app gloss. Money here is recorded and reconciled, not performed.
- **Purple-gradient admin template / generic AI dashboard** — identical card grids, hero-metric templates, uppercase tracked eyebrows above every section, purple gradients, widgets that exist only for decoration.
- **Card gallery** — pages built as walls of same-sized decorative cards where a table, list, or worklist is the honest affordance.
- **Over-rounded AI UI with glassmorphism** — excessive corner radii, heavy drop shadows, frosted-glass panels, cards nested inside cards.
- **Dark, intimidating control room** — the app is a calm daytime control *desk*, not a mission-control cockpit.
- **Cluttered Excel replacement / old government form system** — cramped tables, everything the same weight, tiny gray low-contrast text, no hierarchy or guidance.
- **Squeezed-desktop mobile** — mobile pages that are just a shrunk desktop layout instead of flows designed for the phone and the factory floor.

Concrete bans: random gradients, gradient text, excessive shadows, glassmorphism, nested cards, side-stripe accent borders, tiny gray text, low contrast, cramped tables, decorative card grids, playful icons as default, unnecessary animation, overly cute empty states.

## Design Principles

1. **The tool disappears into the task.** Earned familiarity over novelty. One consistent component vocabulary screen to screen; nothing that makes a user pause at an unfamiliar control. If it is clever, it is probably wrong here.
2. **Every record answers "whose move is it?"** The portal coordinates handoffs, so each screen makes the current holder, the current state, and the next action unmistakable — "mine to act" is visually distinct from "waiting on someone else." Routing the work is the product.
3. **Next action before summary metrics.** Open on the work to do, not a wall of counts. Metrics are a compact strip that supports the worklist, never the hero.
4. **Trust is the product near money and records.** Payroll, pricing, commission, and personal data demand precision and predictability — no flashy motion, no decorative color, no ambiguity in a number. Correctness reads visually.
5. **Status is text, not color alone.** Every state carries a Thai word; color reinforces meaning, it never is the meaning.
6. **Mobile and the floor get prioritisation, not compression.** Employee, sales, and warehouse flows are designed for the device in hand — reachable actions, real touch targets, card reflow — never a shrunk desktop grid. Desktop-only admin flows are labeled as such.
7. **Progressive disclosure, never hidden critical information.** Depth folds into tabs and sections; the one thing a user must act on is never behind a click.
8. **Destructive and consequential actions are deliberate.** Reject, cancel, void, and money-committing actions require a reason or a confirm; they never fire on a single stray tap.
9. **Bilingual by construction.** Thai and English both read right in the same layout with Sarabun. No decision depends on Latin-only metrics.
10. **Restraint is the default.** One accent for actions, one for "live/current," semantic color for meaning only. Density where the work needs it, calm everywhere else.

## Success criteria

- Work reaches the right person and clears without stalling; each role opens the app and immediately sees what is waiting on them.
- Approvals happen in seconds, from the phone as readily as the desktop.
- Money and records are correct and provable; no figure is ambiguous or mis-scoped.
- Staff self-serve routine tasks without asking HR or Sales admin.
- The interface is dependable and unremarkable in daily use — the win is that nobody has to think about the tool.

## What the product is not

- Not a complete ERP (yet) — it is an HR + Sales/CRM portal growing toward one; do not present unbuilt modules as if they exist.
- Not a marketing surface — no landing pages, no persuasion, no hero storytelling.
- Not a consumer app — no borrowed social/banking/portfolio patterns, no decorative delight.
- Not a spreadsheet — density serves scanning and hierarchy, not raw cell-cramming.
- Not a public product — it is internal, role-scoped, and holds sensitive HR and financial data.

## Accessibility & Inclusion

Target **WCAG 2.1 AA** (2.2 AA where already met).

- Body text ≥ 4.5:1, large text ≥ 3:1; no tiny gray low-contrast labels (an explicit anti-reference here).
- Full keyboard operability and a visible focus state on every interactive element.
- Status is never conveyed by color alone — text plus color on every badge and state.
- Honor `prefers-reduced-motion` — motion conveys state, so its removal must never hide information.
- **Thai + English typography (Sarabun)** treated as a first-class requirement: correct diacritic spacing, line-height that fits Thai ascenders and descenders, and layouts that survive both scripts' widths.
- Real touch targets (≥ 44px) on the mobile and factory-floor surfaces, where a mis-tap costs a record.
