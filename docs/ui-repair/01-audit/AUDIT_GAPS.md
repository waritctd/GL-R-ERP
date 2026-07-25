# Audit Gaps

What Phase 1 did **not** cover, and why — recorded honestly rather than fabricated.
Nothing here is a finding; it is the boundary of the evidence.

## Environment / authorization
- **Backend not exercised.** `backend/.env.local` is absent, so the Spring backend
  could not be started locally; the entire audit ran against the **in-browser mock**
  (`VITE_USE_MOCKS=true`, port 5321). Therefore **all permission/role observations
  are UI-level only** (the mock’s approximation) and are **not** verified enforcement
  — per `CLAUDE.md`, permission truth is the Java service. Any nav/route/authz note
  (esp. F-03, F-11) is unverified against production.
- **Mock artifacts excluded from findings** (not product defects): the recurring
  `502 / net::ERR_ABORTED http://…/api/auth/login` console line (the mock intercept),
  and the persistent “เข้าสู่ระบบสำเร็จ” login toast visible in many screenshots
  (harness login timing).

## Evidence artifacts not preserved
- **`report.json` (the capture-harness run log) was not committed; its citations have
  been removed.** The live-capture session produced a `report.json` with
  machine-readable outcomes (the `validateDOMNesting` console error behind F-02, the
  per-role `silentRedirect:true` outcomes behind F-03, and the nav-outcome confirmation
  in ROLE_ROUTE_MATRIX), but that file was a runtime artifact and is **not** present
  under `evidence/current/`. Rather than cite a file that isn’t here, those findings
  were **re-grounded on the source + committed screenshots that do exist**: F-02 on
  `DataTable.jsx:255` (`RowTag='button'`) + `role="row"` at `:336/:362` + inner
  `Button.jsx:61` (invalid nesting is structurally certain, not merely observed); F-03
  on `RequireAccess.jsx:10` (`<Navigate to="/" replace />`) + the per-role
  `*/denied-probe/` captures. No finding depends on the uncommitted log any more. If a
  machine-readable nav/console log is wanted for the record, re-run the harness in a
  later pass and commit it into `evidence/current/`.

## Roles not inspected
- **`warehouse` and `qc`** exist in `ROLE_PERMISSIONS` but are **not seeded as login
  users** (only 7 role personas + a second `employee` = `warehouse.manager`). They
  currently inherit the plain-employee experience in code, but their rendered UI was
  not captured. Gap.
- **`sales` / `import` / `account`** personas have `employeeId: null` in the seed, so
  their self-service routes (`/profile`, `/leave`, `/employee-requests`) were
  unreachable **in this seed** — a data artifact, documented in ROLE_ROUTE_MATRIX,
  not a permission rule.

## Screens / detail pages not captured
- **Pricing-request detail** (`/pricing-requests/:id`) — the queue was empty for the
  CEO seed, so no row to open; the full PCR chain (factory quotes → costing → CEO
  pricing decision → quotation) was **not** rendered.
- **Procurement/factory-PO detail** — the procurement list rows are not `DataTable`
  row-buttons, so the automated row-click harvest didn’t reach the detail page.
- **Not exercised at all:** `/tickets/:ticketId/deposit` (deposit-notice generation),
  `/ceo-settings` deep content, `/catalog` search-results interaction, `/price-import`
  upload→validate→stage→commit flow, `/attendance` day drill-down, `/finance` row
  actions, remaining-invoice/quotation document previews.

## States not exercised
- **Loading / skeleton** (the mock resolves instantly — no loading frames captured).
- **File-upload** progress/success/error (📎).
- **Validation-error messages** — create-deal uses a **disabled submit** rather than
  post-submit inline errors, so field-level error rendering wasn’t surfaced.
- **Destructive-confirmation** dialogs (e.g. cancel deal / mark lost) not opened.
- **Large-dataset** tables (pagination stress), **table overflow** for any `DataTable`
  caller **without** a `mobileCard`, **200% browser zoom** (WCAG 1.4.10), and
  **mobile keyboard/focus** behaviour.

## Accessibility coverage limits
- No **automated `axe` scan** against the running DOM, no **real screen-reader /
  keyboard walkthrough**, and no **measured contrast** per rendered surface (tokens
  were reasoned about, and the “Muted Floor” was confirmed from source, but individual
  tinted-surface combinations weren’t instrument-measured). A-01…A-06 are source- and
  screenshot-grounded, not AT-tested.

## Analysis confidence notes
- The Impeccable **detector** is markup-shallow: it produced only 18 cosmetic
  advisory hits and found **none** of the real a11y/DOM/responsive issues — those came
  from source + screenshot review (two isolated assessment passes). Treat detector
  output as a maintainability signal, not a coverage claim.
- A few landings (`sales_manager`, `division_manager`) were captured to the evidence
  tree (`<role>/landing/`) but not each opened as an inline image in the findings;
  their findings inherit the shared-shell/landing patterns (metric cards, worklist)
  and are marked accordingly.

## Carried-forward gaps → owning phase

Each Phase-1 gap is assigned to the phase that must close it, so none is silently
dropped. Phases 2–3 are still analysis-only (no production code); the enforcement and
live re-tests land in Phase 4+.

| Gap (from above) | Owning phase | What closes it |
|---|---|---|
| `warehouse` / `qc` UI never rendered (unseeded personas) | **Phase 2 (IA)** | Seed `warehouse`/`qc` mock logins; capture their nav + landing; fold into the role→route IA. Until then they inherit the plain-employee shell (documented, not verified). |
| Non-empty PCR / procurement / deposit detail never rendered (empty seed) | **Phase 2 (IA)** | Seed non-empty PCR + procurement fixtures; capture the detail chain (factory quote → costing → CEO pricing → quotation) and the deposit-notice screen. |
| Loading / skeleton, file-upload, validation-error, destructive-confirm, large-dataset, 200%-zoom, mobile keyboard/focus states unexercised | **Phase 4+ (execution)** | Add a loading/large-dataset fixture; exercise each state during the per-surface repair and ship before/after evidence. |
| No `axe` scan, no real screen-reader / keyboard walkthrough, no measured contrast (A-01…A-06 are source+screenshot only) | **Phase 4+ (execution)** | Run `axe` + a keyboard/SR pass + contrast measurement on the top ~10 screens as each a11y fix (A-01…A-06) is made; treat as the verification gate for those fixes. |
| **Permission-shaped items F-03, F-11 are mock-level only — UNVERIFIED against production** | **Phase 4+ (execution)** | Stand up the real backend (`SPRING_PROFILES_ACTIVE=demo`) and re-check F-03/F-11 against the Java service; any nav↔permission change ships a real-DB integration test per `CLAUDE.md`. **Do not treat the mock's authz as production truth.** |
| Table-overflow for any `DataTable` caller **without** a `mobileCard` not spot-checked | **Phase 2/3** | Enumerate `DataTable` call sites; confirm each has a `mobileCard` or record the overflow risk (RESPONSIVE_AUDIT). |

These are tracked here as the single source of carried-forward work; the top-level
[`../README.md`](../README.md) phase map points back to this table.
