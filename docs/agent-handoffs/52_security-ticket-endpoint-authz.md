# Agent Handoff

## Task
Plan item 3 from the 2026-07-16 sales-ticket-flow audit (see handoff 51 + plan file
`~/.claude/plans/can-you-investigate-the-tranquil-newell.md`): close the authorization holes
in the ticket/deposit-notice endpoints that became prod-reachable when sales shipped enabled
by default (PR #204).

## Branch
`security/ticket-endpoint-authz` (off main `0dc144b`, the PR #212 merge)

## What changed

### 1. Factory-email endpoint is no longer an open mail relay
`POST /api/tickets/{id}/factory-emails/send` previously required only a session — any
authenticated employee could send arbitrary to/subject/body from the company address.
Now: `TicketService.assertFactoryEmailAllowed` — **import role** (the flow's real actor:
factory outreach during price proposal) + ticket must exist. Wired in `TicketController`.

### 2. One read-access rule for tickets: `VIEWER_ROLES = {sales, import, ceo, account}`
- `TicketService.requireViewAccess(ticketId, actor)` — viewer role + sales-owner scoping —
  is now the single gate used by `get()`, `comment()`, and `loadQuotationContext`
  (quotation xlsx/pdf downloads).
- `list()`/`listPage()` also require a viewer role. Previously **any** authenticated user
  (hr, employee) could enumerate all tickets with prices; `comment()` additionally returned
  the full TicketDto to anyone, bypassing `get()`'s owner scoping.
- Matches the frontend's `canViewTickets` and the mock's list/get gates (the mock was
  *stricter* than prod here — the dangerous direction is now closed).

### 3. Deposit-notice reads/downloads gated
`DepositNoticeService` gets the same `requireTicketViewer` rule; applied to `listByTicket`,
`getById`, `preview`, `getXlsx`, `getPdf`, `getRemainingInvoiceXlsx` (all previously
session-only — any employee could download any customer's financial documents).
`DepositNoticeController` passes the principal through (`listByTicket`/`getDoc`/`file`
signatures updated).

### 4. Dual-track ownership
`confirmCustomer` and `issueDepositNotice` now require the ticket **owner** (previously any
sales rep could advance a colleague's payment track) — consistent with submit/quotation/
close/cancel. The account/ceo money confirmations intentionally have no ownership concept.

### 5. mockApi mirrors (authz still not authoritative, but direction-aligned)
New `requireTicketViewer` helper mirroring the backend rule; applied to `tickets.comment`,
`tickets.downloadRemainingInvoice`, `depositNotices.{listByTicket,get,preview,downloadXlsx,
downloadPdf}`, `factoryConfigs.sendEmail` (import + ticket existence), and owner checks on
`confirmCustomer`/`issueDepositNotice`.

## Files Changed
- `backend/.../ticket/TicketService.java` — VIEWER_ROLES, requireViewAccess, requireOwner,
  assertFactoryEmailAllowed; list/get/comment/quotation-context/confirmCustomer/
  issueDepositNotice rewired
- `backend/.../ticket/TicketController.java` — factory-email gate call
- `backend/.../deposit/DepositNoticeService.java` — VIEWER_ROLES + requireTicketViewer;
  listByTicket/getById signatures take the actor; preview/getXlsx/getPdf/remaining-invoice gated
- `backend/.../deposit/DepositNoticeController.java` — pass principal to reads
- `backend/src/test/.../TicketServiceTest.java` — +9 tests (viewer-role rejections for
  list/get/comment/quotation files, account read access, factory-email gate ×3, dual-track
  non-owner rejections ×2)
- `backend/src/test/.../DepositNoticeControllerTest.java` — getById mock signatures updated
- `frontend/src/api/mockApi.js` — mirrors above

## Commands Run
```bash
cd backend && ./mvnw -B clean verify
cd frontend && npm run lint && npm test && npm run build
```

## Tests / Build Results
- Frontend: lint 0 errors (10 pre-existing warnings), 94/94 tests, build green.
- Backend: `mvnw -B clean verify` — **413 tests, 0 failures, BUILD SUCCESS** (Testcontainers ran).

## Known Risks
1. **hr/employee lose ticket read access they technically had.** Nothing in the frontend ever
   routed them there (canViewTickets never included them), but any out-of-band API consumer
   would break. Intended.
2. **`sales_manager` remains locked out** of the sales flow entirely (audit finding #11,
   deliberate deferral — needs a product decision).
3. The broken `document/` module (plan item 5) still has its own unguarded-but-500ing
   endpoints; its one live route (`POST /tickets/{ticketId}/revision`) already had owner
   checks. Untouched here.
4. Attachments (`AttachmentController`) were not re-audited in this branch.

## Exact Next Prompt
```text
Repo GL-R-ERP, branch security/ticket-endpoint-authz. Read CLAUDE.md and
docs/agent-handoffs/52_security-ticket-endpoint-authz.md. Review the diff (main..HEAD) as a
reviewer: (1) is the VIEWER_ROLES read rule consistently applied across TicketService and
DepositNoticeService, (2) does any legitimate consumer (frontend page, mock flow) lose access
it needs — especially account-role pages and the import propose flow's factory email, (3) do
the mockApi mirrors match the Java gates. Then hand back for merge on the user's say-so.
```
