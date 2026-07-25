# Role → Job Map

What each role actually does, owns, waits on, and needs to see — derived from the Java
services (authoritative for permissions), the frontend nav/permission model, and the
Phase-1 audit. Use this as the input to landing strategy (E) and navigation (D).

> **Authorization caveat.** Ownership/visibility/approval facts are read from the Java
> services and are *source-verified, not test-verified*. Per `CLAUDE.md`, any change
> that acts on them in Phase 4+ needs a real-DB integration test. The mock's authz is
> not authoritative.

## Roles that exist in code

`ceo`, `sales`, `sales_manager`, `import`, `account`, `hr`, `employee`, `warehouse`,
`qc` (`routes.js:254`, `DivisionAccessPolicy.roleFor`). **Division manager** is not a
role literal — it is derived: `role === 'employee' && !!user.manager`
(`permissions.js:23-25`). `warehouse`/`qc` derive from division code (`wh`/`qc`) but
currently render the **plain-employee experience** (`canUseEmployeeExperience:
['employee','warehouse','qc']`, `routes.js:254`) — no warehouse/QC-specific surface
exists yet (Phase-1 gap; no real QC step in code, only a free-text `qc_note` at goods
receipt).

---

## CEO (`ceo`)

- **Core responsibility** — Final authority on price, money-out-the-door, and deal
  closure. The escape hatch/fallback on almost every gated action.
- **Most frequent daily jobs** — Review pricing decisions (set margins → approve /
  return to import); approve manager-approved commissions (hop 2); approve
  manager-approved OT (hop 2) and special-money (hop 2); verify-close deals (2nd
  signature). Price/FX config in `/ceo-settings`.
- **High-risk jobs** — `approve` pricing (recomputes selling price, sets the number
  the customer sees; `PricingDecisionService.approve`); `verifyClose` (irreversible
  `status=closed` + `lifecycle=COMPLETED`, `TicketService:609-623`); CEO-approve
  commission (money into payroll).
- **Approvals held** — Pricing decision (sole approver, `CEO_ROLES`); commission hop 2
  (`ceoApprove`); OT hop 2 (`requireCeo`); special-money hop 2; deal close verification
  (sole, `verifyClose`); PCR cancel override.
- **Records owned** — Pricing decisions (`pricing_decision`); CEO price/FX config.
- **Records visible, not editable** — Everything: all tickets/deals, all PCRs, all
  factory quotes + costing (incl. cost/margin — CEO is the only sales-side role that
  sees margin), all commissions, all attendance & OT & special-money (`VIEW_ALL_ROLES`
  for attendance/OT), payroll preview (`PAYROLL_VIEW_ROLES={hr,ceo}`). **Not** leave
  review (CEO is leave view-only, not a reviewer — `LeaveService`).
- **OT & leave are oversight, not self-service (business rule).** CEO does **not**
  submit its own OT/leave and its own don't need approval — so the OT (`/employee-requests`)
  and leave (`/leave`) surfaces are, for CEO, a **read-only summary/history over all
  employees' activity** (`canViewAllOvertime`/`canViewAllLeave`), *plus* the CEO's OT
  hop-2 approvals surfaced as actionable rows within it. Not a "submit a request" form.
- **Records waiting on CEO** — PCRs at `READY_FOR_CEO_REVIEW`/`CEO_REVIEWING`;
  commissions at `MANAGER_APPROVED`; OT/special-money at `MANAGER_APPROVED`; deals at
  close-ready awaiting `verifyClose`.
- **Records CEO waits for** — Import to submit costing; account to confirm close-ready;
  managers to first-approve commission/OT.
- **Documents uploaded** — None specific.
- **Documents generated** — None directly (approval enables Sales to issue quotation).
- **Manual transitions** — pricing `startReview`/`approve`/`returnToImport`; commission
  `ceoApprove`/`reject`; OT/SM `ceoApprove`/`ceoReject`; `verifyClose`; can also drive
  any stage (`requireStageWriteAccess`: CEO any stage) and cancel PCRs.
- **Automatic transitions triggered** — pricing `approve` moves PCR →
  `APPROVED_FOR_QUOTATION`; `verifyClose` sets COMPLETED.
- **Notifications required** — `*_PENDING_CEO` (commission/OT/SM); PCR `READY_FOR_CEO_REVIEW`
  (via `notifyByRole("ceo")`, division `MD%`/`MN%`); factory-quote + costing progress;
  quotation issued/outcome. CEO is the most notification-heavy role.
- **Sensitive-data restrictions** — Sees cost & margin (unique on the sales side); does
  **not** get employee PII/salary unless also HR (PII gate is `hr`-only).
- **Mobile likelihood** — **High.** The mixed-device approver persona; approvals must be
  completable one-handed. Pricing *decision entry* (margins) is desktop-leaning.
- **Current nav** — Dashboard, รายการดีล, ตั้งค่าราคา, แคตตาล็อกสินค้า, นำเข้าราคา,
  คิวใบขอราคา, จัดซื้อ & นำเข้า, ค่าคอมมิชชัน, งานการเงิน, เวลาทำงาน, คำขอ/อนุมัติ OT,
  วันลา (12 items across all groups except HR-admin).
- **Proposed nav** — Lead with **งานของฉัน (My Work)** = a unified approval inbox
  (pricing + commission + OT/SM + close-verify). Keep pipeline, pricing/import, finance,
  people read-access. Demote `ตั้งค่าราคา` into an Admin/settings area.

---

## Sales rep (`sales`)

- **Core responsibility** — Own the deal from lead to close. Single accountable owner of
  each ticket (`createdById`).
- **⚠ Ownership is immutable — leaver gap (G-1, red-team).** `createdById` cannot be
  reassigned (no `reassign`/`transferOwner` in the services) and the "งานของฉัน" worklist is
  owner-keyed. When a rep resigns (employee record flips to `RSG`), their in-flight deals/PCRs
  orphan from every queue except CEO/manager oversight — no one is prompted to adopt them.
  The IA must **not assume the owner is always present**; interim mitigation is a manager/CEO
  "orphaned deals" view, real fix is a backend reassignment action ([OUT OF SCOPE — backend]).
  **Resolution (D-14):** a deal whose owner is inactive (`active=false`/`RSG`) classifies as
  **Ownerless** and surfaces in a **"ดีลไร้เจ้าของ"** cluster on the CEO/sales_manager
  `งานของฉัน` landing — no faked reassignment; oversight acts via powers they already hold. Note
  also: a **viewer has exactly one derived role** (`roleForDivision` returns one), so a genuine
  dual-hat user (scenario 17) is unrepresentable except employee+manager.
- **Most frequent daily jobs** — Advance pipeline stages (with the "log an activity +
  set next follow-up" gate); create/submit pricing requests (PCR); build & issue
  customer quotations; issue deposit notices; record quotation outcomes; confirm
  customer order; chase their own follow-ups.
- **High-risk jobs** — Issue quotation (customer-facing price; may discount only down to
  the CEO minimum, 422 otherwise); confirm customer order (`confirmOrder` bridges the
  deal into the fulfilment/money track); cancel deal (owner-only, cascades PCR cancel).
- **Approvals held** — None. Sales is a *requester/producer*, not an approver.
- **Records owned** — Their own tickets/deals (`createdById == actor.id`); PCRs they
  create; customer quotations; deposit-notice documents (as ticket owner).
- **Records visible, not editable** — Approved selling price (never cost/margin —
  `salesView` strips them); their own commission rows (read-only, own rows only).
- **Records waiting on sales** — PCRs at `MORE_INFO_REQUIRED` (import asked a question);
  quotations to issue after `APPROVED_FOR_QUOTATION`; deposit notices to issue;
  customer-quotation outcomes to record; deals needing the next stage move.
- **Records sales waits for** — Import to pick up + cost a PCR; CEO to approve pricing;
  account to confirm deposit/final payment; customer decision.
- **Documents uploaded** — PCR attachments (spec/reference, DRAFT/`MORE_INFO_REQUIRED`
  only); the tax invoice is **not** sales' to upload (account records it).
- **Documents generated** — Customer quotation (PDF/XLSX), deposit notice (PDF/XLSX),
  remaining invoice (XLSX) — all system-rendered but sales-initiated.
- **Manual transitions** — `updateStage` (own SALES_TARGET_STAGES); PCR `createDraft`/
  `submit`/`respondInformation`/`createCustomerChangeRevision`; quotation `create`/
  `update`/`issue`/`recordOutcome`/`createRevision`; deposit `createDraft`/`update`/
  `issue`; `confirmCustomer`/`confirmOrder`; `markLost` (owner/mgr/ceo); `cancel`
  (owner-only).
- **Automatic transitions triggered** — Quotation `issue` (first) auto-advances stage to
  `QUOTE_DESIGN_SIDE`/`QUOTE_BUYER`; `confirmCustomer` auto→`ORDER_RECEIVED`.
- **Notifications required** — PCR picked up (`OVERTIME`-style: rep notified), pricing
  approved/returned, quotation outcome, `MORE_INFO_REQUIRED`. Delivered to the specific
  rep (`notifyEmployee`) or via `notifyByRole("sales")` (division `SA%`).
- **Sensitive-data restrictions** — Own deals only (403 on others; DRAFT PCRs 404 to
  non-owners to prevent id-enumeration). Never sees cost/margin. No employee PII.
- **Mobile likelihood** — **High.** Field-facing; deal-checking, follow-up logging,
  quotation status on the phone. Quotation *building* is desktop-leaning.
- **Current nav** — Dashboard, รายการดีล, แคตตาล็อกสินค้า, ค่าคอมมิชชัน, เวลาทำงาน (+
  self-service when linked to an employee).
- **Proposed nav** — **งานของฉัน** (my deals needing action + follow-ups) → **ดีล
  (Pipeline)** → catalog → commissions (read-only) → self-service. See landing strategy.

---

## Sales manager (`sales_manager`)

- **Core responsibility** — Oversight of the sales team + first-line commission approval.
  Read-and-comment across the pipeline, with a few real powers.
- **Most frequent daily jobs** — Review the team pipeline; first-approve commissions
  (hop 1, `managerApprove`); create manual commissions; monitor PCR queue.
- **High-risk jobs** — Manager-approve commission (advances to CEO); create manual
  commission (hand-typed amount).
- **Approvals held** — Commission hop 1 (`managerApprove`); manual-commission creation
  (`MANUAL_CREATE_ROLES`). **Not** a deal approver; **not** a money confirmer.
- **Records owned** — Manual commissions they create; comments/oversight notes.
- **Records visible, not editable** — All tickets/deals (read + comment; not owner —
  `VIEWER_ROLES`), PCR queue, all commission rows. Deliberate exceptions where they
  *can* act: pipeline stage moves for team deals, `markLost`, `reopenDeal`
  (`requireDealOwnership` includes sales_manager).
- **Records waiting on sales_manager** — Commissions at `SUBMITTED`.
- **Records they wait for** — Reps to submit; CEO to finalize.
- **Documents** — None uploaded/generated specific to the role.
- **Manual transitions** — commission `managerApprove`/`reject`/`createManual`/
  `createClawback`; team-deal stage moves / `markLost` / `reopenDeal`.
- **Automatic transitions** — Manual commission they create lands `MANAGER_APPROVED`
  (pre-advanced past their own hop).
- **Notifications required** — `COMMISSION_PENDING_MANAGER`.
- **Sensitive-data restrictions** — Sales-side only; no cost/margin (not in
  `RAW_DECISION_ROLES`); no PII. Own division scoping applies to team lists.
- **Mobile likelihood** — **Medium-high.** Approvals on the phone; oversight browsing on
  desktop.
- **Current nav** — Like sales + คิวใบขอราคา (pricing-request queue); has self-service
  (employeeId 2).
- **Proposed nav** — **งานของฉัน** (commissions awaiting my approval + team exceptions) →
  Pipeline (team view) → pricing queue (read) → commissions → self-service.

---

## Import / procurement (`import`)

- **Core responsibility** — Turn an approved sale into landed goods: cost the pricing
  request, run the factory, procure, and fulfil.
- **Most frequent daily jobs** — Pick up submitted PCRs; generate/send factory-quote
  drafts and record responses; build landed-cost costing and submit it for CEO review;
  issue the import request; create factory POs; record shipping/goods-received; drive
  delivery.
- **High-risk jobs** — Submit costing (drives the CEO's price basis); create factory POs
  (commits to suppliers, frozen from approved costing); goods-received / delivery records.
- **Approvals held** — None (import is a *doer*, not an approver). Import decides factory
  readiness, not price.
- **Records owned** — PCR pickup assignment (on the PCR, never `ticket.assigned_to`);
  factory quotes; pricing costing; factory purchase orders; fulfilment records.
- **Records visible, not editable** — Tickets in scope (a **projected** DTO with the
  quotation chain stripped; import is denied deposit notices and the payment ledger and
  quotation file downloads); approved selling price via `salesView` (no margin beyond
  what costing shows — import authored the cost, so it sees cost, not the CEO's final
  margin decision detail).
- **Records waiting on import** — PCRs at `SUBMITTED` (to pick up), `IMPORT_REVIEWING`/
  `AWAITING_FACTORY_RESPONSE`/`COSTING_IN_PROGRESS`, and `COSTING_REVISION_REQUIRED`
  (CEO returned it); deals at stage `PROCUREMENT` needing PO / goods movement.
- **Records import waits for** — Sales to submit a PCR; factories to respond; CEO to
  approve/return costing; account to confirm deposit (gate for `issueImportRequest`).
- **Documents uploaded** — Factory-quote attachments; supplier proforma details.
- **Documents generated** — None customer-facing (no IR document exists — an IR has no
  row of its own).
- **Manual transitions** — PCR `pickup`/`requestInformation`; factory-quote `generateDrafts`/
  `send`/`receive`/`startNegotiation`/`markReadyForCosting`/`markNotAvailable`; costing
  `createDraft`/`recalculate`/`submit`; ticket `issueImportRequest`/`markIrSent`/
  `markShipping`/`markGoodsReceived`/`reserveStock`/delivery; factory-PO create/record/cancel.
- **Automatic transitions triggered** — costing `submit` → PCR `READY_FOR_CEO_REVIEW`;
  `issueImportRequest` auto→`PROCUREMENT`; `markGoodsReceived`/`reserveStock` auto→
  `DELIVERY_SCHEDULING`; `completeDelivery` auto→`DELIVERED`.
- **Notifications required** — PCR submitted (`notifyByRole("import")`, division `PCIM%`);
  CEO `returnToImport` (assigned import or import-role fallback); info round-trips.
- **Sensitive-data restrictions** — No customer quotation/payment/deposit visibility; no
  PII. List-scoped to deals with a live PCR or stage ≥ PROCUREMENT (excludes closed/
  lost/cancelled).
- **Mobile likelihood** — **Medium.** Goods-received / shipping updates plausibly from a
  warehouse floor; costing entry is desktop.
- **Current nav** — Dashboard, แคตตาล็อกสินค้า, นำเข้าราคา, คิวใบขอราคา, จัดซื้อ & นำเข้า,
  เวลาทำงาน.
- **Proposed nav** — **งานของฉัน** (PCRs to pick up / cost + POs to move) → **Pricing &
  Import** (queue, costing, factory) → **Orders & Fulfilment** (procurement) → catalog/
  price-import → self-service.

---

## Account / finance (`account`)

- **Core responsibility** — Guard the money: confirm receipts, manage billing, record the
  invoice, trigger commission, and hold the first close signature.
- **Most frequent daily jobs** — Confirm deposit paid; chase overdue; confirm/record
  final payment; set billing / waive deposit; confirm close-ready; record tax invoice +
  create commission from a closed-paid deal.
- **High-risk jobs** — Confirm payments (money-received truth); `confirmCloseReady`
  (sole holder of the first close signature — CEO deliberately excluded so one person
  can't sign both halves); `createFromDeal` commission (seeds the commission that pays
  the rep).
- **Approvals held** — Money-receipt confirmations (`ACCOUNT_ROLES={account,ceo}`);
  close-ready (`CLOSE_CONFIRM_ROLES={account}`, account-exclusive); commission
  create-from-deal (`{account}`-exclusive). Note: **account does not *approve*
  commissions** — it *creates* them; approval is manager→CEO.
- **Records owned** — Payment records; billing; commission rows created from deals.
- **Records visible, not editable** — Tickets scoped to money-pending state (deposit-notice-
  issued / awaiting-final / overdue); **no commission list** (deep-link-only to
  `/commissions?ticketId=` for the record-invoice step). Excluded from customer-quotation
  and raw pricing.
- **Records waiting on account** — Deals at `DEPOSIT_NOTICE_ISSUED` (confirm deposit),
  `AWAITING_FINAL_PAYMENT`/`DEPOSIT_PAID` (final payment), close-ready (confirm),
  `CLOSED_PAID` (record invoice + commission), overdue (chase).
- **Records account waits for** — Sales to issue deposit notice; customer to pay; import
  to deliver (close prerequisite); CEO to verify-close.
- **Documents uploaded** — Tax invoice (`INVOICE` attachment, via commission
  `createFromDeal` — doubles as the close-gate invoice-on-file).
- **Documents generated** — None (deposit notice is sales-generated).
- **Manual transitions** — `confirmDepositPaid`, `confirmFinalPayment`/`recordPayment`,
  `setBilling`, `waiveDeposit`, `confirmCloseReady`/`revokeCloseConfirmation`, commission
  `createFromDeal`.
- **Automatic transitions triggered** — deposit paid auto→`DEPOSIT_RECEIVED`; final
  payment can auto→`CLOSED_PAID` (if also fully delivered).
- **Notifications required** — **None push-based today.** Account is a *pull* role — its
  work comes from the finance worklist, not notifications (`notifyByRole` has no account
  mapping; commission notifications go to rep/mgr/CEO, not account). This is an IA fact,
  not necessarily a defect — but "overdue" has no push signal.
- **Sensitive-data restrictions** — Money data only; no cost/margin, no PII, no customer
  quotation content.
- **Mobile likelihood** — **Medium.** Confirmations plausibly mobile; the finance grid is
  desktop-leaning.
- **Current nav** — Dashboard, แคตตาล็อกสินค้า, งานการเงิน, เวลาทำงาน. (`/commissions`
  reachable but deliberately **no nav item**.)
- **Proposed nav** — **งานการเงิน (Finance)** as the primary work surface (it already *is*
  the reference-quality worklist) → catalog → self-service. Keep commission access
  deep-link-only.

---

## HR (`hr`)

- **Core responsibility** — People operations: employee records, profile-change review,
  attendance, and payroll.
- **Most frequent daily jobs** — Review profile-change requests; manage employees; run/
  monitor attendance (incl. WFH mark-present); prepare & process payroll; handle bank/
  statutory exports and payslip distribution.
- **High-risk jobs** — `process` payroll (HR-only, irreversible period commit);
  bank/statutory export; mark WFH present; approve profile changes (writes to the
  registry immediately).
- **Approvals held** — Profile-change requests (`hr`-only); leave review (HR **or** the
  direct manager). **HR cannot approve OT** (returns 403 — `OvertimeService` has no HR
  branch; this is the issue-#199 shape) and cannot approve special-money.
- **Records owned** — Employee records; payroll periods; profile-request decisions;
  attendance overrides.
- **Records visible, not editable** — Commission payroll-ready summary (`/payroll-ready`,
  HR-only) — but **not** the commission list itself. All employees' attendance/OT/leave
  history (`VIEW_ALL_ROLES`/`canViewAll*`).
- **OT & leave are oversight, not self-service (business rule).** HR does **not** submit
  its own OT/leave and its own don't need approval. For HR the OT (`/employee-requests`)
  and leave (`/leave`) surfaces are a **read-only summary/history over all employees'
  activity** — HR **cannot approve OT** (403; `OvertimeService`), so its OT view is pure
  oversight; its leave view is oversight plus the occasional leftover `SUBMITTED` review
  it shares with the direct manager. Not a "submit a request" form.
- **Records waiting on HR** — Profile-change requests at `pending`; leave at `SUBMITTED`
  (shared with direct manager); payroll period to process each month.
- **Records HR waits for** — Employees to submit; commission approvals to finalize before
  payroll.
- **Documents uploaded** — Attendance `.dat` import.
- **Documents generated** — Payslips (PDF, emailed); bank/statutory export files (KBank
  PCT, ภ.ง.ด.1, สปส.1-10).
- **Manual transitions** — profile-request approve/reject; leave approve/reject/cancel;
  attendance mark-present/override/recalculate; payroll preview/process/export/distribute.
- **Automatic transitions triggered** — Leave auto-decides on submit (approved / auto-
  rejected) — HR's manual path only touches leftover `SUBMITTED` rows.
- **Notifications required** — Profile-change requests currently emit **no** notification
  (gap). HR is otherwise pull-based off the `/requests` badge and the payroll calendar.
- **Sensitive-data restrictions** — HR is the **only** role that sees employee PII/salary
  (`PRIVILEGED_EMPLOYEE_ROLES={hr}`); the "ข้อมูลอ่อนไหว" (PDPA) tab is HR-only. This is
  the app's most sensitive surface — must never leak into a shared component.
- **Mobile likelihood** — **Low–medium.** Profile-request review and attendance checks are
  plausibly mobile; payroll is explicitly desktop-only (`DesktopOnlyNotice`).
- **Current nav** — Dashboard, **ค่าคอมมิชชัน** (surprising — nav-visible but no list
  access; F-11), ภาพรวม HR, พนักงานทั้งหมด, คำขอแก้ไขข้อมูล (badge), เงินเดือน, +
  self-service.
- **Proposed nav** — **People & Attendance** group leading with the profile-request inbox
  (badge) → employees → attendance → payroll. **Remove the ค่าคอมมิชชัน nav item** (dead
  end for HR; F-11) — treat as an authz-adjacent change per the handoff rules.

---

## Employee (plain) (`employee`, and `warehouse`/`qc` today)

- **Core responsibility** — Self-service: attendance, leave, OT/welfare requests, profile.
- **Most frequent daily jobs** — Check attendance; submit leave; submit OT/special-money;
  request a profile change; track own request statuses.
- **High-risk jobs** — None (all self-scoped; leave over-quota silently becomes unpaid —
  worth surfacing clearly).
- **Approvals held** — None.
- **Records owned** — Own attendance, leave, OT, special-money, profile requests.
- **Records visible, not editable** — Own payslip; own request routing state.
- **Records waiting on employee** — Nothing blocks *them*; they wait on approvers.
- **Records they wait for** — Manager/CEO (OT, special-money), HR/manager (leave), HR
  (profile change).
- **Documents uploaded** — Leave medical certificate (SICK requires it, else auto-reject);
  (special-money evidence upload is **not implemented** — disabled placeholder).
- **Documents generated** — Own payslip (self-download).
- **Manual transitions** — submit leave/OT/special-money/profile-request; cancel own
  requests.
- **Automatic transitions triggered** — Leave auto-approves/-rejects on submit.
- **Notifications required** — `LEAVE_*`, `OVERTIME_*`, `SPECIAL_MONEY_*` on their own
  requests; profile-change decisions emit **none** (gap).
- **Sensitive-data restrictions** — Own data only; self-view nulls salary/PII on the
  employee DTO. Cannot see others.
- **Mobile likelihood** — **Highest.** The phone-first persona — every self-service task
  must complete one-handed.
- **Current nav** — Dashboard + self-service (เวลาทำงาน, คำขอ, วันลา).
- **Proposed nav** — **หน้าหลัก (self-service home)** with the day's attendance + balances
  + own requests, then the three self-service actions. `warehouse`/`qc` inherit this until
  their own surfaces are designed (tracked gap).

---

## Division manager (derived: `employee` + `manager` flag)

- **Core responsibility** — Everything a plain employee does, **plus** approve their
  division's team requests.
- **Most frequent daily jobs** — Approve team OT (hop 1); approve team special-money
  (hop 1); review team attendance; (leave review is direct-manager-only — see caveat).
- **High-risk jobs** — Team OT/special-money hop-1 approval.
- **Approvals held** — OT hop 1 and special-money hop 1 for anyone **in their division**
  (division-scoped, `managesEmployee`); leave only if they are the employee's *direct*
  manager (no division-manager leave branch — the `/leave` "การอนุมัติวันลา" nav appears
  for them but only direct reports are actionable — a real inconsistency to flag).
- **Records owned** — Own self-service records.
- **Records visible, not editable** — Team OT/special-money/attendance in their division.
- **Records waiting on them** — Team OT/SM at `SUBMITTED`.
- **Records they wait for** — CEO (hop 2 of team OT/SM).
- **Documents** — As plain employee.
- **Manual transitions** — team OT/SM `managerApprove`/`managerReject`; own self-service.
- **Automatic transitions** — As plain employee.
- **Notifications required** — `*_PENDING_MANAGER` for their division; own self-service.
- **Sensitive-data restrictions** — Division-scoped; out-of-division employeeId matches
  zero rows (no leak). No PII/salary.
- **Mobile likelihood** — **High.** Approve-in-seconds on the floor.
- **Current nav** — Dashboard + **ทีมของฉัน** (การอนุมัติ OT, การอนุมัติวันลา, ทีมในฝ่าย) +
  self-service.
- **Proposed nav** — **งานของฉัน** merges their approval queue with their own tasks; keep a
  distinct **ทีมของฉัน** for team roster/attendance. Resolve the leave-review inconsistency
  (either enable division-manager leave review — an authz change — or hide the nav; decide
  in Phase 4 with evidence, do not silently flip).

---

## Warehouse (`warehouse`) & QC (`qc`) — latent

- **Status** — Role literals exist and derive from division code (`wh`/`qc`), but they map
  to `canUseEmployeeExperience` and render the **plain-employee** surface. There is **no**
  warehouse or QC workflow UI, and **no real QC step** in the backend (only a per-line
  free-text `qc_note` captured at goods-receipt inside `ProcurementService`).
- **Implication for IA** — Do not design a warehouse/QC surface now (out of Phase-2
  evidence — unseeded, uncaptured). Record as a **Phase-2 gap carried forward**: seed
  logins, capture, and — if the business wants a real QC gate before delivery — that is a
  *new backend workflow*, not a UI repair (see [`../01-audit/AUDIT_GAPS.md`] and the
  long-standing "real QC deferred" note).
