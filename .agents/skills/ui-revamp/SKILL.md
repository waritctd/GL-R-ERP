---
name: ui-revamp
description: Audit and fix web pages that look off — visual polish (type, layout, color, hierarchy, AI-slop removal) plus mobile-first responsive standards from 320px phones to wide desktops. Use when revising, critiquing, polishing or making any UI responsive.
---

# UI Revamp

One skill for fixing pages that "don't look right" and making them work on every screen, from a 320px phone to a 1440px+ desktop. It condenses three open-source approaches into one workflow: a design-system-first reasoning pass, a command vocabulary for targeted visual fixes, and mobile-first responsive guardrails.

## How to use

Say what you want in plain words, or use a mode keyword followed by a target:

```
audit the pricing page
critique landing
fix layout on the dashboard
typeset the blog post
adapt checkout for mobile
polish settings
```

With no mode named, run **audit → fix → polish** on the target.

---

## Step 1 — Gather context (always, before changing anything)

1. **Read the existing code first.** Find the stack (React/Next, Vue, plain HTML, Tailwind or plain CSS, component library) and work inside it. Never introduce a new framework to fix a page.
2. **Find the existing design system.** Look for tokens, a theme file, `tailwind.config`, CSS variables, `DESIGN.md`, or shared components. If one exists, it wins: extend it and don't override it. If none exists, extract one from the most-used values in the code (Step 2).
3. **Know the product.** Who is the audience, what is the page for, and what is the one action a visitor should take? Check for `PRODUCT.md` or a README. Ask the user only when something material is missing, and ask once.
4. **See the page.** Look at a screenshot or rendered view at 375px, 768px, 1024px and 1440px if you can. Judge the rendered result, not just the code.

## Step 2 — Lock a design system (small, explicit)

Write down, or confirm, a compact system before editing. If the project has none, save it as `DESIGN.md` at the project root so later pages stay consistent.

- **Type:** 1–2 families at most (a display face plus a text face, or a single family). Use a modular scale, e.g. 12 / 14 / 16 / 20 / 24 / 32 / 40 / 48. Body text is never below 16px on mobile.
- **Spacing:** a 4px or 8px base scale (4, 8, 12, 16, 24, 32, 48, 64, 96). No arbitrary values such as 13px or 27px.
- **Color:** a primary, an accent or CTA, a neutral ramp of 5–9 steps tinted toward the brand hue (not pure gray), plus success/warning/danger. Define these as tokens or CSS variables, and a dark set if the site has dark mode.
- **Radius and shadow:** 2–3 radius values and 2–3 elevation levels, used consistently.
- **Style direction:** one named direction that fits the product (e.g. calm and minimal for finance or health, bold and editorial for creative work, dense and functional for dashboards). Industry fit matters: trust-heavy products such as banking, health and legal avoid playful or neon styles.

**Page overrides:** if one page truly needs to deviate, record only the deviation in `design/pages/<page>.md`. Page rules override the master and everything else inherits.

## Step 3 — Diagnose (audit + critique)

Score each area **OK / Minor / Major** and list concrete findings with file and line where possible.

**Visual hierarchy and clarity (critique)**
- Is there one clear focal point and one primary CTA per view?
- Does size, weight and color tell the reader what matters first, second and third?
- Is the copy clear? Are labels and buttons action-oriented and free of jargon?

**Technical quality (audit)**
- Contrast is at least 4.5:1 for body text and 3:1 for large text and UI elements.
- Focus states are visible, keyboard order is logical, and headings run in order with none skipped.
- Images have alt text, and icon-only buttons have accessible labels.
- Layout breaks, horizontal scroll, overflow and clipped text are checked at every breakpoint.
- Performance: no huge images, no layout shift (reserve space for images and embeds), fonts load with `font-display: swap`.

**AI-slop and generic-template tells (flag and remove)**
- Inter or system default used for everything, with no typographic personality.
- Purple-to-blue gradients, glowing dark-mode blobs, gradient text on every heading.
- Cards inside cards, or every section wrapped in a card.
- A rounded-square icon tile above every heading, and emoji used as icons (use SVG icons such as Lucide, Heroicons or Phosphor).
- Gray text on colored backgrounds, and pure `#000` or pure gray (tint neutrals instead).
- Side-accent borders on every card, bounce or elastic easing, everything centered.
- Three identical feature columns with no hierarchy.

## Step 4 — Fix (pick the modes the diagnosis calls for)

| Mode | Fixes |
|---|---|
| **typeset** | Font choice and pairing, scale, weights, line height (1.4–1.6 for body, 1.1–1.25 for headings), line length (45–75 characters), heading wrapping |
| **layout** | Spacing rhythm, alignment to a grid, grouping (related items closer, sections further apart), whitespace, density |
| **colorize** | Strategic color: a neutral base, one accent used sparingly for actions and emphasis, and meaning that never relies on color alone |
| **bolder** | Bland pages: stronger scale contrast, a confident hero, a real focal point, more decisive color |
| **quieter** | Noisy pages: fewer colors, effects, borders and shadows; calmer motion |
| **distill** | Cluttered pages: cut sections, merge duplicates, remove decoration that carries no meaning |
| **harden** | Edge cases: long text and URLs wrap instead of clipping, empty, loading and error states exist, chips and badges wrap or collapse to "+n", the layout survives 200% zoom and translated text |
| **clarify** | UX copy: specific headings, verb-first buttons, helpful error messages |
| **animate** | Purposeful motion only: 150–300ms, ease-out, honors `prefers-reduced-motion`, and the final state stays correct if an animation is interrupted |
| **adapt** | Make it responsive (see the standards below) |
| **polish** | Final pass: align to the design system, make spacing and radius consistent, finish hover, focus and active states, run the pre-delivery checklist |

Make the smallest change that fixes the problem. Reuse existing components and tokens. Keep behavior and content intact unless asked otherwise.

---

## Responsive standards (always on)

**Mobile-first**
- Write base styles for the smallest screen, then add larger layouts with `min-width` media queries (Tailwind: unprefixed first, then `sm:`, `md:`, `lg:`, `xl:`).
- Always include `<meta name="viewport" content="width=device-width, initial-scale=1">`.

**Breakpoints:** use the project's existing set. Default to 640 / 768 / 1024 / 1280 / 1536px, matching Tailwind. Choose breakpoints where the content breaks, not for specific devices, and never invent one-off values.

**Fluid layouts**
- Use Flexbox and Grid. Prefer `grid-template-columns: repeat(auto-fit, minmax(16rem, 1fr))` over fixed column counts.
- Use relative units: `rem` for type and spacing, `%`, `fr`, `vw` and `ch` for widths. No fixed pixel widths on containers. Use `max-width` with `width: 100%`.
- Constrain the content width (e.g. a max of 1200–1280px) with responsive side padding: 16px on mobile, 24–32px on tablet, 48px+ on desktop.
- Use fluid type for headings with `clamp()`, e.g. `font-size: clamp(2rem, 1.2rem + 3vw, 3.5rem)`.
- Use container queries for components that appear in different-width slots.

**Touch and interaction**
- Tap targets are at least 44×44px, with at least 8px between adjacent targets.
- Nothing depends on hover alone. Every hover reveal has a tap or focus equivalent.
- Form inputs use 16px or larger text (prevents iOS zoom), and use the correct `type` and `inputmode`.
- Add `cursor: pointer` to every clickable element.

**Content adaptation**
- Navigation collapses to a menu or bottom bar on mobile, and the primary CTA stays reachable.
- Multi-column sections stack in a sensible reading order. Don't hide important content on mobile; reprioritize it instead.
- Tables scroll horizontally inside a wrapper, or turn into stacked cards on mobile.
- Images use `max-width: 100%; height: auto`, plus `srcset`/`sizes` or the framework's image component. Set `aspect-ratio` to prevent layout shift.
- Respect safe areas on notched phones with `env(safe-area-inset-*)` for fixed bars.
- Use `100dvh` instead of `100vh` for full-height mobile sections.

**Test widths:** 320, 375, 768, 1024, 1440 and 1920px, in both orientations, plus 200% browser zoom.

---

## Pre-delivery checklist

- [ ] No horizontal scroll at 320px; layout verified at 375 / 768 / 1024 / 1440
- [ ] Text contrast is at least 4.5:1 (3:1 for large text and UI), in light and dark modes
- [ ] Visible focus states and logical tab order
- [ ] Tap targets are at least 44×44px; no hover-only interactions
- [ ] Body text is at least 16px on mobile; line length is 45–75 characters
- [ ] All spacing, color, type and radius values come from the design system
- [ ] No AI-slop tells remain (see the list in Step 3)
- [ ] SVG icons instead of emoji; icon buttons are labeled
- [ ] Long text, empty, loading and error states handled
- [ ] `prefers-reduced-motion` respected
- [ ] Images sized and lazy-loaded, with no layout shift

## Output format

1. **Findings:** a short scored list (OK / Minor / Major) with the top 3–5 problems.
2. **Changes:** what you changed and why, grouped by mode, with file references.
3. **Before/after:** screenshots or a mockup at mobile and desktop widths when possible.
4. **Checklist:** the pre-delivery checklist with every item ticked or explained.

---

*Credits: condensed and rewritten from ideas in UI UX Pro Max (nextlevelbuilder, MIT), Impeccable (Paul Bakaus, Apache-2.0) and Frontend Responsive Design Standards (am-will/codex-skills). No scripts or data files are required.*
