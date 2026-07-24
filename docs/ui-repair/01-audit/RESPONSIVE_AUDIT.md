# Responsive Audit

Grounded in live capture at the two baseline viewports (390×844, 1366×768) for the
full role×route matrix, plus shell/table spot-checks at 375×812, 768×1024,
1024×768, 1440×900 (`../evidence/current/`). See [UI_AUDIT](UI_AUDIT.md) for the
issue records; this doc is the responsive-specific view.

## Breakpoint model (as-built)
- **Single hardcoded 720px breakpoint**, defined in **three unaligned places** with
  no shared token: `frontend/src/hooks/useIsMobile.js:3` (`max-width:720px`),
  `frontend/src/styles.css` `@media` (`:1871`, `:2021`, plus `900`/`1040` blocks),
  and `frontend/src/components/common/Button.jsx:24` (`max-[720px]:min-h-[44px]`).
- ≤720px → mobile (drawer nav, card reflow). >720px → the full 260px desktop rail.
- **No intermediate tablet treatment.** This is the root of the worst responsive
  finding.

## Findings by viewport

| Viewport | Verdict | Notes |
|---|---|---|
| 1440×900, 1366×768 (desktop) | ✅ good | Primary design target; dense tables, sidebar, worklists all read well. |
| 1024×768 (landscape/small laptop) | ⚠️ | >720 → desktop rail; sidebar labels begin to crowd; content usable but tight. |
| 768×1024 (tablet portrait) | ❌ **F-01 (P1)** | Desktop rail stays; group-header Thai+English labels **wrap and collide** (“งาน/ขาย”); `/commissions` subtitle overlaps the toolbar; content crushed to one column. The whole iPad-portrait class is degraded. Evidence: `_responsive-spot/ceo-*/tablet-768x1024.png`. |
| 390×844 (baseline mobile) | ✅ mostly | Drawer nav (focus-trapped), tables → cards, create-deal modal → full-screen sheet. See mobile issues below. |
| 375×812 (small mobile) | ✅ | No new breakage vs 390 in the spot set. |

## Mobile reflow — mostly good
- ✅ **`DataTable` → stacked record cards** below 720px via the `mobileCard` prop
  (`DataTable.jsx:133-134,331`): tickets → `DealCard`, commissions/attendance/
  procurement/employees pass a card. Not a squeezed desktop grid. Evidence:
  [tickets mobile](../evidence/current/sales/tickets/mobile-390x844.png).
- ✅ **Create-deal modal** reflows to a clean full-screen stacked sheet with a fixed
  footer. Evidence: [create-deal mobile](../evidence/current/sales/create-deal-modal/mobile-390x844.png).
- ✅ **Mobile drawer** is full-height, backdrop-dimmed, Escape/backdrop close,
  focus-trapped. Evidence: [drawer](../evidence/current/hr/shell-drawer/mobile-390x844.png).

## Mobile problems
- ⚠️ **F-12 (P2)** — landing **metric cards horizontal-scroll and clip Thai labels**
  on mobile (account: “รอชำระส่...” clipped). Not a deliberate scroll region.
  Evidence: [account mobile](../evidence/current/account/landing/mobile-390x844.png).
- ⚠️ **F-06 (P2)** — create-deal wizard, while it reflows, is still a 6-step task in
  an overlay on a small viewport (a toast fires over it). Should be a full-page route.
- ℹ️ **F-21 (P3)** — **payroll is desktop-only** (`DesktopOnlyNotice`, `PayrollPage.jsx:447`);
  the grid doesn’t reflow. Intentional for a month-end admin grid, but recorded.

## Horizontal overflow
No body-level horizontal scroll observed in the mobile shots (cards + sheet stay in
viewport). The tablet damage is layout *squeeze*, not overflow. Any `DataTable`
caller **without** a `mobileCard` would fall back to the dense grid and could
overflow — none confirmed in the audited set (spot-check remaining callers in a
later pass — see AUDIT_GAPS).

## Recommended direction (Phase 3, no build now)
1. Introduce a **single shared breakpoint token** and reconcile the three 720px
   definitions to it.
2. Add a **deliberate tablet (721–1040px) behaviour** — labelled rail kept longer,
   or a true icon-rail with tooltips and suppressed group-header text (never
   fragmented labels). This is the highest-value responsive repair.
3. Reflow landing stat rows to a wrapping 2-up grid / summary line on mobile.
