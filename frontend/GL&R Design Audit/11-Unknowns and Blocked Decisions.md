---
title: Unknowns and Blocked Decisions
tags: [glr, blocked, product-decisions]
---
# Unknowns and Blocked Decisions

Codex must not decide these from screenshots.

| Topic | Why blocked | Safe work now |
|---|---|---|
| Final product name (`GL&R HR`, `GL&R ERP`, other) | Screens conflict; no approved brand spec supplied | make shell product label configurable |
| `คำขอราคา` vs `ใบขอราคา` vs `PCR` | multiple visible terms; official vocabulary unknown | centralize display strings; do not rename backend values |
| Meaning of every status chip | screenshots do not expose enum mapping/transitions | build a display map only after reading existing code/constants |
| Why account is not employee-linked | configuration/identity rule not shown | contextual error component with generic recovery owner placeholder |
| Difference between `/procurement` and `/factory-purchase-orders` | similar screenshots do not prove duplicate workflow | clarify headings and reuse components; keep both routes |
| Difference between HR `dashboard` and `hr-dashboard` | two visible summaries; intended entry points unknown | clarify current titles; preserve both routes |
| Which deal tabs each role should prioritize or hide | visibility policy not supplied | reorder only if current role-specific code already proves intent; otherwise preserve order |
| Financial confirmation/reversal rules | screenshots do not show transaction semantics | add review UI only where the current action already has a confirmation or can be safely wrapped frontend-side |
| Date calendar standard | screenshots mix 2026 and 2569 | centralize formatter; product owner chooses locale/calendar |
| Whether bilingual labels are required | no language policy supplied | default recommendation is Thai-first; keep strings configurable |

## Backend-blocked implementation log

Recorded on 2026-08-02 after the Batch 4 complex-workflow pass and the follow-up full audit sweep. These items must not be "fixed" by changing frontend assumptions, because the required decision or behavior belongs to backend, API, data ownership, permissions, workflow rules, or product policy.

| Item | Evidence / issue IDs | Why frontend-only work is blocked | Safe frontend-only work |
|---|---|---|---|
| Employee account is not linked to an employee | `G-004`, attendance/profile screenshots with `User is not linked to an employee` | The cause and recovery path depend on identity/account-linking rules that are not visible in screenshots and must not be invented in UI code. | Replace raw technical copy with a contextual Thai state, preserve retry/navigation, and log technical detail separately. |
| Denied-route behavior and permission correctness | `G-005`, denied manifests across roles | Role access, guard rules, and route eligibility are permission decisions. Do not grant, remove, or infer access from navigation visibility. | Preserve existing redirects/guards and show a safe non-sensitive denial message when the current frontend already knows access was denied. |
| Backend status meanings and transitions | `G-002`, `G-016`, `I-002`, `S-002`-`S-003` | Enum meaning, transition order, and whether two labels represent the same state require backend/constants or product confirmation. | Use display-label maps for existing values only; never rename enum values, merge statuses, or alter transition logic. |
| Deal current-step hierarchy and next action | `S-002`-`S-004`, deal detail captures across Sales, Sales Manager, CEO, Import, Account | Choosing a canonical workflow status or computing a new progress/next-action value would change business interpretation unless existing API fields prove it. | Visually group existing statuses by section, keep one visible primary action where already present, and avoid new progress calculations. |
| Deal creation and pricing-request creation semantics | `S-006`, `S-007` | Required fields, payload shape, draft/send behavior, validation rules, and role eligibility are API/workflow contracts. | Re-present the same fields in clearer sections or steps while preserving payloads, validation outcomes, and submissions. |
| Pricing-request detail unavailable causes | `I-003`, previous Batch 4 pricing-request detail pass | The frontend can distinguish only the outcomes the API exposes, such as loading, 403, 404, and retryable errors; it cannot decide backend cause or access policy. | Batch 4 implemented structured loading/not-found/denied/error/retry presentation for existing outcomes without backend/API changes. |
| Procurement vs factory purchase-order route meaning | `I-004`, procurement and factory-order screenshots | Similar screenshots do not prove duplicate workflows or permission overlap; merging routes or changing queue ownership would be workflow/backend behavior. | Clarify headings and reuse list/card presentation while preserving both routes, filters, statuses, and actions. |
| Payroll, commission, tax, pricing formulas, rates, and overrides | `H-006`, `A-002`, `C-002` | Calculations, rounding, exchange rates, formula sources, override semantics, and reversal rules are financial/business logic. | Group the existing fields for review and confirmation where compatible with current submissions; do not change values or formulas. |
| Employee edit and profile-request approvals | `H-004`, `H-005` | Field ownership, approval transitions, personal-data visibility, and decision permissions are backend/role-policy rules. | Improve sectioning, labels, before/after comparison, and confirmation presentation while keeping current fields and decisions. |
| Calendar/date standard | `G-017` | Screens mix Gregorian and Buddhist-era years; the canonical locale/calendar affects stored/display interpretation and needs product decision. | Centralize formatting after decision; do not transform stored values or infer a calendar rule from screenshots. |

## Decision request template

```md
### Decision: [name]
- Affected roles/routes:
- Screenshot evidence:
- Existing code evidence:
- Options:
- UX impact:
- Business-logic risk:
- Owner/decision:
```
