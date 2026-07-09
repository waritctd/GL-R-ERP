# Product

## Register

product

## Platform

web

## Users

Internal GL&R staff, in three overlapping contexts. Design must serve all three, not favor the admin:

- **HR / payroll administrators** — desktop-first. They run payroll, reconcile attendance, manage employees, and process leave/overtime. High-density tables, multi-step forms, and correctness under time pressure (month-end payroll runs). They live in the app for stretches at a time.
- **Managers (division / department heads)** — mixed desktop and mobile. They approve overtime, leave, and attendance exceptions for their team. They want to act on a request quickly and get out; they are not power users of the whole system.
- **Employees** — mobile-first. They check in/out attendance, request leave, view payslips, and update their profile from a phone, often on the move or on a factory floor. This is the surface most people touch most often.

The primary language is Thai; English is co-present. Both must read correctly in the same layout.

## Product Purpose

GL-R-ERP is the internal HR portal for GL&R, stabilizing toward a broader ERP platform. It is **not** a complete ERP yet — v0.1.0 is a stable HR-core foundation (employees, attendance, leave, overtime, payroll, profile, auth, dashboards). The frozen sales/CRM stack is out of scope and flag-hidden.

Success is operational, not aspirational: payroll and attendance are processed correctly and on time, employees self-serve routine HR tasks without asking HR, and managers approve requests in seconds. The interface earns trust by being dependable and unremarkable in daily use — the win is that nobody has to think about the tool.

## Brand Personality

**Calm. Reliable. Efficient.**

Quiet confidence around money and records. The voice is plain, direct, and Thai-first — no marketing gloss, no cleverness near payroll or personal data. Where the interface has personality, it shows as precision and predictability, not decoration. Delight lives in a well-placed empty state or a fast, obvious flow, never in ornament.

## Anti-references

This should NOT look or feel like:

- **Flashy SaaS demo / gradient-heavy startup dashboard** — random gradients, neon "tech startup" colors, decorative animation, marketing choreography. Wrong tone next to payroll.
- **Generic AI / admin template** — identical card grids, hero-metric templates, uppercase tracked eyebrows, purple gradients, dashboard widgets that exist only for decoration.
- **Over-rounded AI UI** — excessive corner radii, heavy drop shadows, glassmorphism, cards nested inside cards.
- **Dark, intimidating control room** — the app is a calm daytime office tool, not a mission-control cockpit.
- **Consumer social / banking app clone / decorative portfolio** — playful icons everywhere, over-cute empty states; borrowed consumer patterns that don't fit an internal HR tool.
- **Cluttered Excel replacement / old government form system** — cramped tables, everything the same weight, tiny gray low-contrast text, no hierarchy or guidance.
- **Squeezed-desktop mobile** — mobile pages that are just a shrunk desktop layout instead of flows designed for the phone.

Concrete bans carried from the above: random gradients, excessive shadows, glassmorphism, nested cards, tiny gray text, low contrast, cramped tables, playful icons as default, unnecessary animation, overly cute empty states.

## Design Principles

1. **The tool disappears into the task.** Earned familiarity over novelty. Standard affordances, consistent component vocabulary screen to screen, nothing that makes a user pause at an unfamiliar control. If it's clever, it's probably wrong here.
2. **Trust is the product near money and records.** Payroll, attendance, and personal data demand precision and predictability — no flashy motion, no decorative color, no ambiguity in a number. Correctness reads visually.
3. **Mobile is a first-class surface, not a leftover.** Employee flows (attendance, leave, payslips, profile) are designed for the phone in hand — reachable actions, real touch targets, card reflow — never a compressed desktop grid. Desktop-only admin flows are labeled as such.
4. **Bilingual by construction.** Thai and English both have to look right in the same layout with Sarabun. No design decision may depend on Latin-only metrics; watch line-height, truncation, and label width in both scripts.
5. **Restraint is the default.** One accent for actions and state, semantic color for meaning only. Density where the work needs it, calm everywhere else. Add nothing that doesn't carry information or aid a task.

## Accessibility & Inclusion

Target **WCAG 2.1 AA**.

- Body text ≥ 4.5:1, large text ≥ 3:1; no tiny gray low-contrast labels (an explicit anti-reference here).
- Full keyboard operability and a visible focus state on every interactive element.
- Honor `prefers-reduced-motion` — motion conveys state, so its removal must never hide information.
- **Thai + English typography (Sarabun)** treated as a first-class requirement, not an afterthought: correct diacritic spacing, line-height that fits Thai ascenders/descenders, and layouts that survive both scripts' widths.
