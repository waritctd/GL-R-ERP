# Component Duplication

Duplicated UI implementations across the code + rendered product. **Do not
consolidate now** — this is an inventory for a later phase. Counts are importer/
usage file counts from `frontend/src` (JSX).

Headline: the shared design system in `frontend/src/components/common/` is
**largely healthy and well-adopted** — most primitives have a single mature home.
The one systemic duplication is the **button system**.

## 1. Buttons — TWO parallel systems ⚠️ (the main finding)

| Implementation | Where | Adoption |
|---|---|---|
| **`<Button>` cva component** (mature) | `components/common/Button.jsx` (class-variance-authority: variant × size, `max-[720px]:min-h-[44px]` touch floor) | **26 files** |
| **Legacy CSS button classes** | `.primary-button` / `.secondary-button` / `.icon-button` in `styles.css` | 20 / 22 / 16 files |

- **Behaviour/visual differences:** the cva `<Button>` carries the mobile 44px
  touch floor, focus/disabled handling, and token-driven colors; the raw
  `.*-button` classes are hand-styled in `styles.css` and do **not** all share the
  same touch-target/focus guarantees. A page mixing both (e.g. `TicketListPage`
  uses raw `.icon-button` + `.primary-button` while other pages use `<Button>`)
  gets subtly inconsistent buttons.
- **Most mature:** the cva `<Button>` component.
- **Consolidation (later):** migrate raw `.*-button` call sites to `<Button>`;
  retire the CSS classes from `styles.css`. One verified surface at a time.

## 2. Colour literals vs tokens — drift

- `features/tickets/TicketCreateModal.jsx` hardcodes `#ef4444` **×14** where
  `--color-danger` exists; `components/common/NotificationBell.jsx` has 3 color +
  1 font-size literals. (Impeccable detector: 18 advisory hits, all cosmetic.)
- **Most mature:** the `DESIGN.md` tokens / `@theme` in `index.css`.
- **Consolidation (later):** swap literals for tokens (P3, maintainability).

## 3. Modals — mostly consolidated, one outlier

- **Shared `Modal.jsx`** (focus-trap, Escape, restore, `role="dialog"`) — **16
  importers**; the mature primitive. Plus `ConfirmDialog.jsx` (11 importers) for
  confirmations.
- **Outlier:** `features/auth/ChangePasswordModal.jsx` hand-rolls its own
  `modal-backdrop` instead of using `Modal.jsx`.
- Feature "*Modal" components (`TicketCreateModal`, `CancelDealModal`,
  `UpdateStageModal`, `MarkLostModal`, `EmployeeFormModal`, `ProductFormModal`,
  `PricingRequestCreateModal`, `ChangeRequestModal`) are content wrappers — verify
  each sits on `Modal.jsx` during consolidation; treat `ChangePasswordModal` as the
  known divergence.

## 4. Well-consolidated primitives (low/no duplication — good)

| Concern | Shared primitive | Importers | Notes |
|---|---|---|---|
| Page headers | `PageHeader.jsx` | 28 | Broadly adopted |
| Status badges | `StatusBadge.jsx` | 39 | Text+colour (a11y-safe); widely used |
| Tables | `DataTable.jsx` | 7 | Single shared table (but has a11y debt — ACCESSIBILITY_AUDIT A-01/02) |
| Mobile record cards | `DataTable` `mobileCard` prop | (via DataTable) | One reflow mechanism, not per-page cards |
| Empty states | `EmptyState.jsx` | 16 | Some inline empties remain (spot-check later) |
| Loading states | `Skeleton.jsx` (8) + `RouteFallback.jsx` | 8 | Two patterns (skeleton vs route fallback) — mild |
| Form fields | `FormField.jsx` | 11 | Shared; error wiring needs a11y work (A-04) |
| File upload | `FileUploadField.jsx` | 4 | Single shared pattern |
| Confirm dialog | `ConfirmDialog.jsx` | 11 | Shared |
| Toast / alerts | `Toast.jsx` | (global) | One toast system |

## 5. Not separately duplicated, but worth naming for later

- **Action bars / worklist rows**: the account/import landings render per-row
  next-action buttons inline (not a shared "worklist row" primitive). As more roles
  get worklists (the target pattern), a shared `WorklistRow` / action-cell would
  prevent divergence. *Propose as a new shared primitive in Phase 3 — do not build
  now.*
- **Timelines / pipeline strips**: the deal pipeline strip + the request routing
  breadcrumb ("ส่งแล้ว › หัวหน้าฝ่าย › CEO") are separate stage/route visualisations;
  candidates to unify conceptually later.

## Summary
One real systemic duplication (**buttons**), one hand-rolled modal outlier, and
cosmetic colour drift. Everything else is a healthy single-source design system —
the repair should **extend** it (worklist row, tablet nav) rather than rebuild it.
