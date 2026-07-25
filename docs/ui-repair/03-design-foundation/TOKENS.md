# Semantic Tokens

The design-token architecture for GL-R ERP. This is a **proposal/specification**,
not an implementation — Phase 3 does not write token code (that is Phase 4). It
names the semantic layer the app should resolve to, maps it onto the values that
**already exist** in `frontend/src/index.css` (`@theme`), and records the gaps.

## How to read this

- **Values already exist** in `index.css` `@theme static` (the runtime source of
  truth) and are mirrored in `DESIGN.md`'s frontmatter. This doc adds the missing
  layer: **semantic intent** (what each token is *for*), **prohibited uses**, and
  **contrast**. It does not change any value.
- **Semantic name** is the role (`--surface-canvas`), which may alias an existing
  raw token (`--color-bg`). Where a semantic name has no backing token yet, it is
  marked **GAP → propose**.
- **Dark mode is out of scope.** Dark values are marked `future` — do not invent
  one. Only the navy sidebar is dark today, and it is a fixed surface, not a theme.

## Two-sources-of-truth problem (must fix first)

Tokens are currently declared **twice**: `index.css:7-124` (`@theme static`, the
**superset**) and `styles.css:5-95` (`:root`, a **subset** missing the warning/
danger/info extras, `--color-link`, `--color-override`, the phase palette, and
`--spacing`). Two sources drift. **Decision D-T1:** `index.css @theme` is the
single source of truth; the `styles.css :root` block is redundant and should be
collapsed into it during Phase 4 (remove after verifying no rule depends on a
value only present there). No value changes in the collapse.

---

## A. Colour — surfaces

| Semantic name | Backing token | Value (light) | Purpose | Prohibited | Contrast | Used by |
|---|---|---|---|---|---|---|
| `surface-canvas` | `--color-bg` | `#eef1f6` | The page everything sits on | Not a panel/content bg; not text | n/a (bg) | app shell, `.content-scroll` |
| `surface-panel` | `--color-surface` | `#ffffff` | Working surfaces: cards, tables, forms, content | Not the page bg | text on it must clear 4.5:1 | `.panel`, `DataTable`, `Modal`, inputs |
| `surface-raised` | `--color-surface-hover` | `#fbfcff` | Row/card hover, faint raise | Not a resting bg for content | n/a | `DataTable` row hover |
| `surface-subtle` | `--color-surface-subtle` | `#f1f5f9` | Tracks, dividers, inset zones | Not a text color | n/a | progress tracks, dividers |
| `surface-muted` | `--color-surface-muted` | `#f8fafc` | Table headers, inset panels | Not a card bg competing with panel | header text ≥4.5:1 | `.table-head`, insets |
| `surface-selected` | `--color-info-row-active` | `#eff6ff` | Current/selected row or item | Not a general highlight; not decoration | n/a | selected table row |
| `surface-disabled` | *(opacity, not a bg)* — `opacity:0.55` | — | Disabled controls | A grey bg substitute | — | `button:disabled`, disabled fields |
| `surface-overlay` | **GAP → propose `--color-overlay`** | `rgba(15,23,42,0.52)` (modal), `0.48` (drawer) | Scrim behind transient layers | Any opaque use | n/a | `.modal-backdrop`, drawer, `.loading-veil` |
| `surface-sidebar` | `--color-sidebar-bg` | `#0b1220` | The one dark surface: nav rail | Any content surface | text on it ≥4.5:1 (slate ~7.3:1) | `.sidebar` |

> **GAP:** overlay/scrim colours are **hardcoded rgba literals** in 5 places
> (`styles.css:394,481,1465,1551,2036`). Propose `--color-overlay` (`0.52`),
> `--color-overlay-drawer` (`0.48`), `--color-veil` (`rgba(255,255,255,0.55)`) so
> the scrim is tokenised. Cosmetic; no visual change.

## B. Colour — text (ink ramp)

| Semantic name | Backing token | Value | Purpose | Prohibited | Contrast on white | Used by |
|---|---|---|---|---|---|---|
| `text-primary` | `--color-text` | `#0f172a` | Headings, primary text, key figures | — | ~16:1 ✓ | headings, emphasis |
| `text-secondary` | `--color-text-secondary` | `#334155` | Body & table text | — | ~10:1 ✓ | body, `.data-row` |
| `text-muted` | `--color-text-muted` / `--color-muted` | `#64748b` | Captions, secondary labels — **the floor** | Below this for any body/data text | ~4.6:1 ✓ (floor) | captions, hints |
| `text-disabled` | *(opacity 0.55 on text-secondary)* | — | Disabled control text | A standalone light-grey | must still ≥3:1 where possible | disabled labels |
| `text-faint` | `--color-text-faint` / `--color-faint` | `#94a3b8` | **Icons & placeholders only**; sidebar text | **Body or data text — banned** | ~2.6:1 ✗ (fails as text) | placeholders, decorative icons |
| `text-inverse` | `--color-surface` | `#ffffff` | Text on dark/coloured fills | On light surfaces | must clear 4.5:1 on its fill | primary button, sidebar active |
| `text-link` | `--color-link` | `#2563eb` | Inline navigable text links | Non-link emphasis | ~5.1:1 ✓ | "view file", inline links |

> **The Muted Floor Rule** is a hard token rule: `text-muted` `#64748b` is the
> lightest a **body/data** colour may go on white. `text-faint` is for
> icons/placeholders/sidebar only. (Verified good in ACCESSIBILITY_AUDIT.)

## C. Colour — borders

| Semantic name | Backing token | Value | Purpose | Prohibited | Used by |
|---|---|---|---|---|---|
| `border-default` | `--color-border` | `#e6eaf0` | Card/panel edges | A decorative accent stripe | panels, cards |
| `border-strong` | `--color-border-strong` | `#d9e0ea` | Heavier separation | Default border weight | emphasised separators |
| `border-subtle` | `--color-border-subtle` | `#e2e8f0` | Row/light dividers | A panel edge | `.data-row` divider |
| `border-input` | `--color-border-input` | `#dfe5ee` | Form-control stroke (1.5px) | — | inputs, secondary/icon buttons |
| `border-focus` | `--color-primary-hover` | `#6366f1` | Focused input border (with ring) | A resting border | input `:focus` |
| `border-error` | `--color-danger` | `#dc2626` | Invalid field border | A non-error emphasis | `.is-invalid` |
| `border-muted` | `--color-border-muted` | `#cbd5e1` | Slate — control border / sidebar text alias | — | some controls, sidebar |

> **Banned:** any of these as a `border-left/right` > 1px decorative accent stripe
> (absolute ban, DESIGN.md §9/§20). Full borders or bg tints only.

## D. Colour — actions

| Semantic name | Backing token | Value | Purpose | Prohibited | Contrast | Used by |
|---|---|---|---|---|---|---|
| `action-primary` | `--color-primary` | `#4f46e5` | The one high-emphasis action; fill | Decoration; more than one primary per context | white text on it ~7:1 ✓ | primary `Button`, active tab |
| `action-primary-hover` | `--color-primary-hover` | `#6366f1` | Primary hover; focus border hue | A resting fill | white text ~5.1:1 (large/bold) | primary hover, input focus |
| `action-secondary` | `--color-surface` + `--color-border-input` + `--color-icon-muted` | `#fff`/`#dfe5ee`/`#475569` | Default non-primary action | The primary action | icon-muted text ~7:1 ✓ | secondary `Button` |
| `action-quiet` (text/back) | `--color-primary` on transparent | `#4f46e5` | Tertiary/inline nav ("กลับ") | A primary-weight action | ~7:1 ✓ | `text` variant `Button` |
| `action-success` | `--color-success` | `#059669` | Approve/confirm fill specifically | A generic primary | white text ~3.9:1 (large/bold only) | success `Button` |
| `action-danger` | `--color-danger` on `--color-surface` | `#dc2626` | Destructive — **outlined, not filled** | A filled red primary; a routine action | text ~4.8:1 ✓ | danger `Button`, `ConfirmDialog` danger |
| `action-disabled` | *(opacity 0.55)* | — | Any disabled action | A separate grey token | — | `button:disabled` |
| `action-override` | `--color-override` | `#7c3aed` | **Only** CEO manual price override | Anywhere else — purple appears nowhere else | text ~5.3:1 ✓ | line-item override |

> **Note (contrast):** `action-success` `#059669` white-on-green is ~3.9:1 — passes
> for **large/bold** button text (≥14px bold) but not for small text on it. Keep
> success as a bold-label button; do not set small text on a green fill.

## E. Colour — statuses (the semantic 6)

Backend carries **~85 distinct status values**; they map to **6 tones**, never one
colour each (the anti-goal is met at the primitive). Full value→tone table in
[`STATUS_PRESENTATION.md`](STATUS_PRESENTATION.md). The tones:

| Tone | bg / text tokens | Meaning | Prohibited |
|---|---|---|---|
| `status-neutral` | `--color-surface-subtle` / `--color-icon-muted` | Inert, terminal-neutral, draft, N/A | For a state that needs attention |
| `status-info` | `--color-info-bg` / `--color-info` | In-progress, informational, "someone is working" | For "done" or "needs me" |
| `status-success` | `--color-success-bg` / `--color-success-dark` | Approved, paid, delivered, complete | For pending/in-progress |
| `status-warning` | `--color-warning-bg` / `--color-warning` | Pending, awaiting, needs-attention | For errors or success |
| `status-danger` | `--color-danger-bg` / `--color-danger-dark` | Rejected, cancelled, void, error | For a healthy waiting state |
| `status-indigo` | `--color-info-bg-alt` / `--color-info-dark` | A field/meta accent (not a lifecycle status) | As a 6th "status" — reserve for meta |

> `teal` CSS-aliases to the **same green as success** — not a distinct status
> colour. **Attendance late/early must never map to `danger`** (reporting-only,
> §76): they are `warning` at most. Every tone always ships with a **text label**
> (WCAG 1.4.1) — colour is never the only channel.

## F. Typography

Values in `index.css:8-21`. Family: **Sarabun** (Thai+Latin, one family) with a
`system-ui` fallback; mono is a system stack.

| Semantic name | Backing token | Value | Purpose | Prohibited |
|---|---|---|---|---|
| `family-body` | `--font-sans` / `--font-family` | `'Sarabun', system-ui, …` | All UI text, both scripts | A second UI family |
| `family-display` | *(same as body)* | Sarabun | **Not needed** — hierarchy is weight, not a display face | Introducing a display/serif for UI |
| `family-mono` | `--font-mono` / `--font-family-mono` | `ui-monospace, SFMono-Regular, …` | Codes, IDs, aligned figures | Body prose; Thai (no Thai mono) |
| `size-2xs … 4xl` | `--text-2xs`…`--text-4xl` | 11/12/13/14/16/22/26/28/34px | The type ramp | Off-scale sizes |
| `weight-body` | *(400)* | 400 | Reading/data | — |
| `weight-label` | *(700)* | 700 | Labels, controls, buttons | — |
| `weight-heading` | *(800)* | 800 | Headings, key figures | **See gap below** |
| `line-height` | *(per role)* | 1.15–1.5 | Thai marks need **≥1.4** on body/label | Tightening Thai below 1.4 |
| `letter-spacing` | *(normal; 0.04em overline only)* | — | Overline is the only tracked style | Tracked "eyebrows" as decoration |
| `numeric-tabular` | **GAP → propose** `font-variant-numeric: tabular-nums` | — | Currency/quantity column alignment | Proportional figures in money columns |

### Thai-first typography (spec E)

Evaluated the existing font before recommending change — **keep Sarabun**; it is
the correct Thai+Latin humanist family and is already the whole system.

- **Loading:** Google Fonts **CDN** (`index.html:8`), `Sarabun:wght@300;400;500;600;700`, `display=swap`. Thai + Latin from one family.
- **Weights available: 300, 400, 500, 600, 700.** The ramp uses 400/700 and calls for **800** — **800 is NOT loaded**, so "800" heads fall back to 700 today. **GAP → decide:** add `800` to the CDN request, or retune the ramp's heading weight to 700. Do not treat 800 as rendering until resolved. (No external download is added in this planning task — this is a one-line CDN param or a token retune, recorded for Phase 4.)
- **CDN dependency is a risk** for an internal ERP that may run on-prem/offline. **Recorded (not actioned):** consider self-hosting Sarabun (woff2, subset Thai+Latin) in Phase 4+ for reliability and privacy. The backend already self-hosts Sarabun Regular/Bold for PDFs — the woff2 could be sourced the same way.
- **Numeral clarity:** Sarabun's Arabic numerals are legible; use **tabular-nums** in tables and money (propose token above). Thai numerals are not used for figures.
- **Line-height for Thai marks:** body/label **≥1.4**; never tighten Thai below it (upper sara / tone marks clip). Display/headline at 1.15–1.3 is acceptable for short heavy strings but must be tested with tall Thai stacks.
- **Small-text minimum:** overline 11px is the floor; nothing smaller. Body/data ≥13px.
- **Table typography:** 13px body, tabular figures, one weight per column role; truncate + tooltip.
- **Currency/quantity:** `฿` prefix, thousands separators, fixed decimals, right-aligned, tabular.
- **Long-name behaviour:** Thai has no inter-word spaces — truncate with ellipsis + tooltip; never clip mid-syllable or force a broken wrap. Test every label with a long real Thai name.

## G. Spacing & density

Base scale `--space-1…8` = 4/8/12/16/20/24/28/32 (`index.css:24-32`), 4px base.

| Semantic name | Backing token | Value | Purpose | Prohibited |
|---|---|---|---|---|
| `space-base` | `--spacing` | `4px` | The unit everything derives from | Off-unit values |
| `space-1…8` | `--space-1…8` | 4–32px | Gaps, pads, gutters | Raw px that a step covers |
| `page-gutter` | **GAP → propose `--page-gutter-{mobile,tablet,desktop}`** | 16 / 20 / 24–32px | Page edge padding per breakpoint | A single fixed gutter across devices |
| `section-space` | `--space-6/8` | 24/32px | Between major sections | Same as a row gap |
| `control-space-compact` | `--space-1/2/3` | 4/8/12px | Dense tables, toolbars | Roomy forms |
| `control-space-comfortable` | `--space-4/5/6` | 16/20/24px | Forms, approvals | Dense grids |
| `row-density` | **GAP → propose `--row-pad-dense`** | ~13px vertical | Table row height | Comfortable padding in a dense grid |

### Density modes (spec F)

**One controlled default, tuned per surface. No user-facing density preference**
(no demonstrated need — do not add one speculatively). Three tuned levels chosen by
surface, not by toggle:

| Surface | Level | Recommendation |
|---|---|---|
| Table rows | **Dense** | ~13px vertical pad, 13px text, sticky header, tabular figures, truncate+tooltip |
| Form controls | **Default** | 40px input height, 16px input font (iOS no-zoom), comfortable field gap |
| Page headings | **Default** | one Title (16/800) per panel; ≤1 Display (34) per screen |
| Filters | **Default** | single filter bar, URL-persisted, collapses on mobile |
| Mobile cards | **Roomy** | identity · status · 2–3 facts · one full-width ≥44px action |
| Approval panels | **Roomy** | decision + evidence in focus, one-tap approve |
| Timelines | **Default** | vertical rhythm; actor + time per event |
| Document rows | **Dense** | name · type · date · download |

## H. Shape / radius

`index.css:35-38`.

| Semantic name | Backing token | Value | Purpose | Prohibited |
|---|---|---|---|---|
| `radius-sm` | `--radius-sm` | `3px` | Small inline chips, tight insets | Panels/buttons |
| `radius-control` | `--radius-md` | `8px` | **Default**: buttons, inputs, panels, cards, modals | — |
| `radius-panel` | `--radius-md` | `8px` | Panels/cards (same as control) | Over-rounding |
| `radius-dialog` | `--radius-md` | `8px` | Modals/drawers | 20px+ soft dialogs |
| `radius-lg` | `--radius-lg` | `20px` | Reserved, rare | A default corner |
| `radius-pill` | `--radius-pill` | `999px` | **Only** status/count badges & true pills | A pill button as decoration |

## I. Elevation

`index.css:41-46`. Four steps; flat by default (DESIGN.md §10).

| Semantic name | Backing token | Value | Purpose | Prohibited |
|---|---|---|---|---|
| `elevation-none` | — | none | The desk and content on it | — |
| `elevation-resting` | `--shadow-sm` | `0 1px 2px rgba(15,23,42,0.03)` | Near-invisible seat under panels/cards/tables | A "floating" resting card (SaaS tell) |
| `elevation-popover` | `--shadow-md` | `0 18px 44px rgba(2,6,23,0.28)` | Dropdowns, menus, popovers | A resting surface |
| `elevation-dialog` | `--shadow-lg` / `--shadow-lg-heavy` | `0 24px 70px …` | Modals, drawers | Anywhere on the desk |
| `focus-ring` | `--shadow-focus-ring` | `0 0 0 3px rgba(99,102,241,0.13)` | Always-visible focus halo | Being stripped without a replacement |

> `--shadow-inset-active` (`inset 3px 0 0 #2563eb`) exists for an active-item inset
> marker — the one sanctioned "left edge", used as an *active-state indicator*, not
> a decorative accent stripe.

## J. Motion

Values are inline today (no motion tokens yet). **GAP → propose** a small set.

| Semantic name | Proposed value | Purpose | Prohibited |
|---|---|---|---|
| `motion-fast` | ~120–160ms | Hover/press, chevrons, small flips | Long choreography |
| `motion-standard` | ~180–200ms | Drawers, collapsibles, transient layers | Decorative reveals |
| `motion-slow` | ≤250ms | Larger surfaces only | Anything ≥300ms |
| `easing-standard` | `ease-out` (exponential) | Enter/most transitions | Bounce/elastic |
| `easing-exit` | `ease-in` | Leaving transient layers | — |
| `reduced-motion` | `prefers-reduced-motion: reduce` → crossfade/instant | Mandatory alternative for every animation | Motion that hides info when removed |

> Today: one keyframe (`skeleton-shimmer`), transitions 100–180ms, reduced-motion
> handled in 4 places — the restraint is the target. Tokenising just formalises it.

## K. Layout

Values are literals today (no layout tokens). **GAP → propose** the set below —
this is where the **single breakpoint token** lives (the highest-value gap).

| Semantic name | Proposed / backing | Value | Purpose | Prohibited |
|---|---|---|---|---|
| `sidebar-width` | **GAP → propose** | `260px` | Desktop nav rail | A per-page literal |
| `sidebar-width-icon` | **GAP → propose** | ~72px | Collapsed icon rail (tablet) | — |
| `content-max` | **GAP → propose** | ~1440px | Max content width where applicable | Unbounded dense grids |
| `content-reading-max` | **GAP → propose** | ~72ch | Prose reading width | Full-bleed prose |
| `content-dense-max` | **GAP → propose** | none/scroll | Dense tables may exceed reading width | Forcing dense tables to reading width |
| `gutter-mobile / tablet / desktop` | **GAP → propose** | 16 / 20 / 24–32px | Page edge padding per band | One fixed gutter |
| `breakpoint-mobile` | **GAP → propose `--breakpoint-mobile`** | `720px` | **The one mobile breakpoint** | The bare literal `720` (used 121×) |
| `breakpoint-tablet` | **GAP → propose `--breakpoint-tablet`** | `1040px` | Tablet↔desktop boundary | New arbitrary breakpoints |

### Responsive breakpoints, semantically (spec G)

**As-built (agent-verified):** distinct values used are **520, 560, 720, 721, 900,
1040**. The canonical mobile boundary is **720px** but it is a **bare literal
repeated 121×** as `max-[720px]` across ~30 files, plus `matchMedia('(max-width:720px)')`
in `useIsMobile.js:3` and two `@media` blocks in `styles.css`. There is **no
breakpoint token** — this is the single biggest token gap (it is also root-cause of
F-01). *(This corrects the Phase-1 audit's "720px in exactly 3 places" — true only
of the canonical media/matchMedia definitions; the value is actually copy-pasted
121× as a Tailwind arbitrary utility.)* A `min-[721px] and max-[1040px]` icon-rail
block already exists (`styles.css:1816`) but is buggy — the tablet band is the
weakest surface (F-01), not an unhandled one.

**Do not invent many breakpoints.** Collapse to **two tokens** (`--breakpoint-mobile`
720, `--breakpoint-tablet` 1040) and reconcile the 520/560/900 one-offs case by
case. Five behaviour bands:

| Band | Range | Nav | Gutter | Header | Table/list | Filters | Dialog | Sticky actions | Context panel | Hidden/moved |
|---|---|---|---|---|---|---|---|---|---|---|
| **Compact mobile** | ≤520 | Drawer | 16px | Condensed, identity+state+action | Record cards | Collapsed into a filter sheet | Full-screen sheet | Bottom sticky bar | Folded into sections | Secondary meta moved below |
| **Large mobile** | 521–720 | Drawer | 16px | Condensed | Record cards | Collapsed | Full-screen sheet | Bottom sticky bar | Folded | Some meta restored |
| **Tablet** | 721–1040 | **Icon rail + tooltips** (or labelled rail kept) — never fragmented labels (F-01) | 20px | Full | Dense table returns | Inline filter bar | Centred modal | Inline action bar | Optional side panel | Group-header text suppressed when collapsed |
| **Office laptop** | 1041–1440 | Full 260px rail | 24px | Full | Dense table | Inline | Centred modal | Inline | Side panel | — (primary target) |
| **Wide desktop** | >1440 | Full rail | 24–32px | Full | Dense table, capped width | Inline | Centred modal | Inline | Side panel persistent | — |

---

## Token gap summary (for Phase 4)

1. **`--breakpoint-mobile` / `--breakpoint-tablet`** — replace the 720-literal ×121 and the 1040 one-offs. *(highest value; fixes F-01 root cause)*
2. **`--color-overlay*` / `--color-veil`** — tokenise the 5 hardcoded scrim rgba literals.
3. **Layout tokens** — `--sidebar-width`, `--page-gutter-*`, `--content-max`.
4. **Motion tokens** — formalise the 3 durations + easings + reduced-motion.
5. **`tabular-nums`** — a numeric token/utility for money & quantity columns.
6. **Heading weight 800** — load it or retune to 700 (Thai typography gap).
7. **Collapse the two token sources** (`styles.css :root` → `index.css @theme`).
8. **NotificationBell hardcoded hex ×5** (`#f59e0b/#3b82f6/#22c55e/#ef4444/#94a3b8`) and `TicketCreateModal #ef4444 ×14` → swap to tokens.

None of these change a rendered value except where explicitly a fix (F-01 tablet,
heading weight); all are recorded for Phase 4, implemented one verified slice at a
time per [`LEGACY_STYLE_MIGRATION.md`](LEGACY_STYLE_MIGRATION.md).
