---
name: GL-R-ERP
description: A calm, reliable HR operations workspace for GL&R — Thai-first, mobile-aware, built for getting real work done.
colors:
  primary: "#4f46e5"
  primary-hover: "#6366f1"
  accent: "#14b8a6"
  accent-deep: "#0f766e"
  success: "#059669"
  warning: "#b45309"
  danger: "#dc2626"
  info: "#1d4ed8"
  workspace: "#eef1f6"
  sidebar: "#0b1220"
  surface: "#ffffff"
  surface-muted: "#f8fafc"
  surface-subtle: "#f1f5f9"
  ink: "#0f172a"
  ink-secondary: "#334155"
  ink-muted: "#64748b"
  ink-faint: "#94a3b8"
  border: "#e6eaf0"
  border-input: "#dfe5ee"
typography:
  display:
    fontFamily: "Sarabun, system-ui, -apple-system, 'Segoe UI', sans-serif"
    fontSize: "34px"
    fontWeight: 800
    lineHeight: 1.15
    letterSpacing: "normal"
  headline:
    fontFamily: "Sarabun, system-ui, -apple-system, 'Segoe UI', sans-serif"
    fontSize: "26px"
    fontWeight: 800
    lineHeight: 1.2
    letterSpacing: "normal"
  title:
    fontFamily: "Sarabun, system-ui, -apple-system, 'Segoe UI', sans-serif"
    fontSize: "16px"
    fontWeight: 800
    lineHeight: 1.3
    letterSpacing: "normal"
  body:
    fontFamily: "Sarabun, system-ui, -apple-system, 'Segoe UI', sans-serif"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: 1.5
    letterSpacing: "normal"
  label:
    fontFamily: "Sarabun, system-ui, -apple-system, 'Segoe UI', sans-serif"
    fontSize: "13px"
    fontWeight: 700
    lineHeight: 1.4
    letterSpacing: "normal"
  overline:
    fontFamily: "Sarabun, system-ui, -apple-system, 'Segoe UI', sans-serif"
    fontSize: "11px"
    fontWeight: 800
    lineHeight: 1.3
    letterSpacing: "0.04em"
  mono:
    fontFamily: "ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace"
    fontSize: "13px"
    fontWeight: 400
    lineHeight: 1.5
    letterSpacing: "normal"
rounded:
  sm: "3px"
  md: "8px"
  lg: "20px"
  pill: "999px"
spacing:
  1: "4px"
  2: "8px"
  3: "12px"
  4: "16px"
  5: "20px"
  6: "24px"
  7: "28px"
  8: "32px"
components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.surface}"
    rounded: "{rounded.md}"
    padding: "0 16px"
    height: "38px"
  button-primary-hover:
    backgroundColor: "{colors.primary-hover}"
    textColor: "{colors.surface}"
  button-secondary:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink-muted}"
    rounded: "{rounded.md}"
    padding: "0 13px"
    height: "38px"
  button-success:
    backgroundColor: "{colors.success}"
    textColor: "{colors.surface}"
    rounded: "{rounded.md}"
    padding: "0 13px"
    height: "38px"
  button-danger:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.danger}"
    rounded: "{rounded.md}"
    padding: "0 13px"
    height: "38px"
  input:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.md}"
    padding: "0 12px"
    height: "40px"
  panel:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink-secondary}"
    rounded: "{rounded.md}"
    padding: "20px"
  status-badge:
    rounded: "{rounded.pill}"
    padding: "3px 10px"
    height: "26px"
  nav-item-active:
    textColor: "{colors.surface}"
    rounded: "{rounded.md}"
    padding: "8px 10px"
---

# Design System: GL-R-ERP

## 1. Overview

**Creative North Star: "The Steady Operations Desk"**

GL-R's interface should feel like a steady operations desk: calm, organized, reliable, and built for getting real work done without confusion or decoration. Staff arrive to run payroll, reconcile attendance, approve leave and overtime, and update records — the interface's job is to make what matters legible, let them act with confidence, and get out of the way. Warmth here comes from clarity and predictability, never from ornament.

The system is **light and even, with a single dark rail to orient by**: a cool off-blue workspace (`#eef1f6`), white content surfaces, and a deep-navy sidebar (`#0b1220`) that anchors navigation. Density is deliberate — tables and forms run tight where the work demands it, and everything breathes where it doesn't. Type is Thai-first (Sarabun) and carries real weight; labels and headings sit at 700–900 so structure reads at a glance in both Thai and English. Color is rationed: indigo marks actions and focus, teal marks what is *live* (the current nav item, a progress fill), and semantic colors mean exactly one thing each.

This system explicitly rejects the flashy SaaS dashboard (gradient drench, decorative motion, hero-metric templates), the cluttered spreadsheet replacement (everything one weight, tiny gray text, no hierarchy), and the old bureaucratic HR/government form system (dated chrome, cramped grids). It is not a mission-control cockpit either — this is a calm daytime office tool, not a dark control room.

**Key Characteristics:**
- Light workspace, one dark navigation rail — calm, oriented, never busy.
- Thai-first typography with heavy structural weights; both scripts must read correctly in the same layout.
- Rationed color: indigo for action/focus, teal for "live", semantics for meaning only.
- Flat-by-default surfaces; elevation is a response to intent, not decoration.
- Sturdy, legible controls with real touch targets — mobile is a first-class surface.

## 2. Colors

A cool, rationed palette: neutral workspace and white surfaces carry the work, indigo and teal do the pointing, and semantic colors are reserved for state.

### Primary
- **Indigo** (`#4f46e5`): The single action color. Primary buttons, active tabs, links, and the focus ring's hue. Hovers to a lighter **Indigo Bright** (`#6366f1`). This is the color of "you can act here" — nothing decorative wears it.

### Secondary
- **Teal** (`#14b8a6`): The "live" accent, used deliberately sparingly (a handful of places across the whole app): the active sidebar item's tint, progress-bar fills, and small count badges. Its rarity is what makes it read as *current*. **Teal Deep** (`#0f766e`) backs the occasional highlight panel.

### Tertiary — Semantic
- **Success Green** (`#059669`, bg `#dcfce7`): approvals, paid status, positive confirmations.
- **Warning Amber** (`#b45309`, bg `#fef3c7`): pending, needs-attention, caution.
- **Danger Red** (`#dc2626`, bg `#fee2e2`): rejections, destructive actions, errors. Danger buttons are *outlined*, not filled — the weight of the action shouldn't shout until pressed.
- **Info Blue** (`#1d4ed8`, bg `#dbeafe`): informational status, neutral notices, selected rows.

### Neutral
- **Workspace** (`#eef1f6`): the cool off-blue page background everything sits on.
- **Sidebar Navy** (`#0b1220`): the one dark surface — the navigation rail. Text on it is **Slate** (`#cbd5e1`).
- **Surface White** (`#ffffff`): panels, cards, tables, inputs. **Surface Muted** (`#f8fafc`) for table headers and inset zones; **Surface Subtle** (`#f1f5f9`) for tracks and dividers.
- **Ink** (`#0f172a`): primary text. **Ink Secondary** (`#334155`): body/table text. **Ink Muted** (`#64748b`): captions and secondary labels — the floor for text on white. **Ink Faint** (`#94a3b8`): icons and placeholders only, never body copy.
- **Border** (`#e6eaf0`) for card/panel edges; **Border Input** (`#dfe5ee`) for form controls.

### Named Rules
**The Rationed Teal Rule.** Teal marks what is *live* and nothing else. If teal appears more than a few times on one screen, it has stopped meaning "current" and become decoration — remove it. Indigo is for action; teal is for state; they are not interchangeable.

**The Muted Floor Rule.** `#64748b` (Ink Muted) is the lightest a text color may go on a white surface — it clears 4.5:1. `#94a3b8` (Ink Faint) is for icons and placeholders only. Never set body or data text in Faint; "light gray for elegance" is a banned anti-reference here.

## 3. Typography

**Display / Body / Label Font:** Sarabun (with `system-ui, -apple-system, 'Segoe UI', sans-serif` fallback)
**Mono Font:** `ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace` — for codes, IDs, and figures where digit alignment matters.

**Character:** One humanist family in many weights, chosen because Sarabun renders Thai and Latin with equal care in a single layout. The system leans on *weight*, not typeface pairing, for hierarchy — heavy 800/900 for structure, 400 for reading. There is no display/serif pairing; a product this dense would only get noisier from a second family.

### Hierarchy
- **Display** (800, 34px, 1.15): the largest page/section title. Rare — one per screen at most.
- **Headline** (800, 26px, 1.2): major section headers, key figures on stat cards.
- **Title** (800, 16px, 1.3): panel titles (`.panel h2`), card headings — the workhorse heading size.
- **Body** (400, 14px, 1.5): default reading and data text. Cap prose at 65–75ch; tables may run denser.
- **Label** (700, 13px, 1.4): form labels, controls, buttons. Bold on purpose — labels anchor the dense forms.
- **Overline** (800, 11px, 0.04em, UPPERCASE): table column headers and small section eyebrows. This is the *only* place uppercase tracking is allowed.
- **Mono** (400, 13px): employee codes, reference numbers, currency figures needing alignment.

### Named Rules
**The Weight-Not-Family Rule.** Hierarchy is built from weight and size within Sarabun, never from a second typeface. If a screen needs more contrast, go heavier or larger — do not introduce a display font into UI labels, buttons, or data.

**The Both-Scripts Rule.** Every type choice must read correctly in Thai *and* English at the same size. Watch line-height (Thai ascenders/descenders need the 1.4–1.5 floors), truncation, and label width. No layout may depend on Latin-only metrics.

## 4. Elevation

**Flat-by-default, lift on intent.** Surfaces sit flat on the workspace, separated by hairline borders and tonal steps (workspace → surface → muted), not shadow. Depth is reserved for things that genuinely float *above* the page — modals, menus, and the occasional hover lift. A card at rest casts almost nothing; if it looks like it's floating, the shadow is wrong.

### Shadow Vocabulary
- **Resting** (`box-shadow: 0 1px 2px rgba(15,23,42,0.03)`): the near-invisible seat under panels, stat cards, and tables. Barely there by design — it grounds the surface without lifting it.
- **Floating** (`box-shadow: 0 18px 44px rgba(2,6,23,0.28)`): dropdowns, popovers, anything that overlays content.
- **Overlay** (`box-shadow: 0 24px 70px rgba(2,6,23,0.24–0.28)`): modals and dialogs — the only place a heavy shadow is correct.
- **Focus Ring** (`box-shadow: 0 0 0 3px rgba(99,102,241,0.13)`): the indigo focus halo on inputs and interactive elements. Always present on `:focus` / `:focus-visible`.

### Named Rules
**The Flat-Desk Rule.** Content on the desk is flat. Shadow means "this element left the desk" (modal, menu, drag). A resting card with a big soft shadow is the SaaS-dashboard tell — forbidden. Excessive shadows and glassmorphism are named anti-references; do not reach for them.

## 5. Components

Controls should feel **sturdy and legible**: solid borders, confident bold labels, real touch targets. Nothing delicate near payroll; every control looks dependable and obvious.

### Buttons
- **Shape:** gently rounded (8px, `--radius-md`), 1.5px border on outlined variants, minimum 38–44px tall for touch.
- **Primary:** filled Indigo (`#4f46e5`) on white text, `0 16px` padding. Hover → Indigo Bright (`#6366f1`). The one high-emphasis action per context.
- **Secondary:** white surface, Ink-Muted text, Border-Input outline. The default for non-primary actions.
- **Success:** filled Success Green — for approve/confirm actions specifically.
- **Danger:** *outlined*, not filled — white surface, Danger-Red text and border. Destructive weight stays quiet until pressed.
- **Text / Back:** borderless, Indigo text, no padding — inline navigation and tertiary actions.
- **Icon button:** 44×44 square (36px for `.icon-only`), white surface, muted icon; `.dark` variant is transparent on the sidebar.
- **Disabled:** `opacity: 0.55`, `cursor: not-allowed`. Weight ≥700 across all buttons.

### Cards / Panels
- **Corner Style:** 8px (`--radius-md`).
- **Background:** Surface White, 1px Border edge, Resting shadow only.
- **Internal Padding:** 20px for panels, 16px for stat cards.
- **Header:** `.panel-header` is a space-between row — Title left, actions right, 16px below.
- **Never nest a card inside a card.** Use tonal surfaces (muted/subtle) or dividers for internal grouping.

### Inputs / Fields
- **Style:** full-width, 40px min-height, **16px font-size** (deliberate — prevents iOS auto-zoom on focus), 1.5px Border-Input stroke, 8px radius, white surface.
- **Focus:** border shifts to Indigo Bright + the indigo Focus Ring halo. Outline is removed only because the ring replaces it — focus is always visible.
- **Error:** `.is-invalid` swaps to Danger border; `.form-error` message in Danger below the field.
- **Label:** grid-stacked above the field, 13px, weight 700, Ink-Secondary.
- **Icon/search fields:** icon absolutely positioned left, input padded to 40px.

### Navigation (Sidebar)
- **Style:** 260px fixed rail on Sidebar Navy; collapses on mobile. Items are 3-column grids (icon · label · count) at 48px min-height.
- **Default:** Ink-Faint label on transparent. **Active:** teal-tinted background (`rgba(20,184,166,0.13)`) with white label — the one place teal marks "you are here". Count badges are teal pills.

### Tables
- **Header:** `.table-head` — Surface Muted background, Overline type (11px, 800, uppercase), Ink-Muted, bottom border; `.is-sticky` pins it.
- **Rows:** white, 13px vertical padding, Ink-Secondary text, `#f1f5f9` bottom divider, hover to Surface Hover. CSS-grid columns per table type keep alignment; cells truncate with ellipsis rather than wrap.
- **Mobile:** dense tables reflow to stacked cards — never a horizontally-squeezed desktop grid.

### Status Badge
- **Style:** pill (999px), 26px tall, 3–10px padding, weight 800, 12px. Semantic bg+text pairs (success/warning/danger/info) — one color, one meaning. When interactive (button/link), grows to a 44px touch target.

### Modal
- **Backdrop:** `rgba(15,23,42,0.52)`, `z-index: 50`, centered. **Panel:** `min(720px, 100%)`, capped at viewport height, 8px radius, Overlay shadow, header/body/footer flex layout. Modals are a last resort — exhaust inline and progressive alternatives first.

## 6. Do's and Don'ts

### Do:
- **Do** ration color: Indigo (`#4f46e5`) for actions and focus, Teal (`#14b8a6`) for "live"/current only, semantics for state. Each color means one thing.
- **Do** keep surfaces flat at rest with a 1px border and the Resting shadow; reserve real shadow for modals, menus, and hover lift ("The Flat-Desk Rule").
- **Do** build hierarchy from Sarabun weight (400 body → 700 label → 800 heading), not from a second typeface.
- **Do** keep body/data text at Ink-Muted (`#64748b`) or darker — it clears 4.5:1. Verify contrast on tinted surfaces.
- **Do** keep inputs at 16px font-size and controls at ≥38–44px touch height; design mobile flows as reflowed cards, not shrunk desktop grids.
- **Do** test every layout in **both Thai and English** — line-height, truncation, and label widths must survive both scripts.
- **Do** honor `prefers-reduced-motion`; motion (150–250ms) conveys state only.

### Don't:
- **Don't** use random gradients, gradient text, or gradient-drenched surfaces — this is not a startup dashboard.
- **Don't** add excessive shadows, glassmorphism, or soft ambient shadows on resting cards. A floating resting card is the SaaS-dashboard tell.
- **Don't** nest cards inside cards, or over-round corners — restraint is the default (`--radius-md` is 8px, not 20px+ everywhere).
- **Don't** set body or data text in Ink-Faint (`#94a3b8`) or any tiny gray low-contrast type — a named anti-reference here.
- **Don't** cram tables to spreadsheet density with everything one weight and no hierarchy; and don't ship a mobile page that's a squeezed desktop screen.
- **Don't** scatter playful icons, decorative widgets, or over-cute empty states; and don't add unnecessary animation. Delight lives in a fast, obvious flow, not ornament.
- **Don't** spend Teal as decoration or use it interchangeably with Indigo ("The Rationed Teal Rule").
- **Don't** introduce a display or serif font into UI labels, buttons, or data ("The Weight-Not-Family Rule").
- **Don't** reach for a modal as the first thought — exhaust inline and progressive alternatives first.
