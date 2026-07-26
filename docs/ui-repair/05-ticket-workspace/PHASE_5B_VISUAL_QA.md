# Phase 5B Visual QA

Date: 2026-07-25

Status: PASS.

Evidence root:
`docs/ui-repair/evidence/proposed/phase-5b-visual-stabilization/`

## Rendered Coverage

Primary dense-state matrix:

| Area | Result |
| --- | --- |
| Ticket | `/tickets/13` |
| Roles | `sales`, `sales_manager`, `import`, `account`, `ceo` |
| Viewports | `390x844`, `768x1024`, `1024x768`, `1366x768`, `1440x900` |
| Tabs | Overview, Pricing, Quotations, Money, Fulfilment, Documents, Activity where role-projected |
| Before screenshots | 160 captured, 15 hidden by role projection |
| After screenshots | 160 captured, 15 hidden by role projection |
| Horizontal overflow | 0 before, 0 after |
| Summary files | `before/capture-summary.json`, `after/capture-summary.json` |

Additional post-fix spot checks:

| State | Screenshot | Result |
| --- | --- | --- |
| Long Thai / empty lead | `after/spot-sales-390x844-ticket15-overview.png` | No overflow; long Thai identity wraps inside the header. |
| Empty pricing | `after/spot-sales-390x844-ticket15-pricing.png` | No overflow; empty state remains within active tab width. |
| Empty documents | `after/spot-sales-1366x768-ticket15-documents.png` | No overflow; Documents uses full tab width. |
| Completed old-flow deal | `after/spot-sales-1366x768-ticket9-overview.png` | No overflow; terminal state badge remains meaningful. |
| Cancelled deal | `after/spot-sales-390x844-ticket10-overview.png` | No overflow; non-active lifecycle badge is preserved. |
| Ready-to-close money state | `after/spot-account-768x1024-ticket14-money.png` | No overflow; Money remains role-projected and tab-local. |

Spot summary:
`after/spot-check-summary.json`

## Behavior Checks

| Check | Evidence | Result |
| --- | --- | --- |
| Keyboard focus | `after/spot-sales-1366x768-keyboard-focus.png` | Focus ring visible; measured box shadow present on focused navigation link. |
| Tab switching | `after/spot-sales-1366x768-tab-behavior-documents.png` | Clicking Pricing then Documents updates URL to `/tickets/13?t=documents`, selects `เอกสาร`, and keeps one tabpanel. |
| Console errors after login | `after/spot-check-summary.json` | 0 route-time console errors. |
| Network failures after login | `after/spot-check-summary.json` | 0 route-time request failures. |
| Page errors | `after/spot-check-summary.json` | 0 page errors. |

Known mock-only note: `mockApi.auth.login` fires a background `/api/auth/login`
request to create a backend document-download session. When the Spring backend
is not running, Vite can log a 502 during login. The route-time diagnostics were
attached after login so this does not mask ticket-workspace errors.

## Validation Commands

The following commands are required after the visual verdict is PASS:

| Command | Status | Notes |
| --- | --- | --- |
| `npm run lint` | PASS | One pre-existing warning remains in `PayrollPage.jsx` for `react-hooks/exhaustive-deps`. |
| `npm test` | PASS | 75 test files, 674 tests. Existing non-failing stderr includes jsdom navigation warnings and one unmatched `/tickets/701/deposit` route warning. |
| `npm run build` | PASS | Vite production build completed successfully. |
| `npm run test:e2e` | PASS | 64 Playwright tests passed. Non-failing output includes the known mock `/api/auth/login` backend-session bridge 502s when Spring is not running, RBAC manifest drift logging, and an existing React key warning in `PricingRequestDetailPage`. |

## Remaining Visual Limitations

1. Returned pricing-request work was not present in the initial seeded mock
   store, so Phase 5B did not invent a returned state in production code.
2. Some older ticket surfaces still contain inline styles and bordered panel
   patterns. They were left alone unless screenshots showed a recurring visual
   inconsistency in this pass.
3. The `account` mobile Money header is slightly taller after the header action
   consolidation (`462px` to `474px`), but the role's primary action remains
   visible, the state is clearer, and there is no overflow.
