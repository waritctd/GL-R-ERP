import { expect } from '@playwright/test';
import { apiWrite } from './api.js';

// Fixture builders for the sales-pipeline journey specs.
//
// ── Every deal is created by the test, never borrowed ────────────────────────────────────────
// These run against a SHARED, long-lived UAT database that human testers navigate. UAT-TKT-01..14
// and UAT-GOLD-01 are their fixtures: a test that advanced one would silently rewrite somebody's
// acceptance script. So each test creates its own deal, tagged with the run id global-setup
// stamped, and cancels it afterwards.
//
// ── Nothing here is hardcoded to a seeded row ────────────────────────────────────────────────
// Customer and project are DISCOVERED at runtime. `projectId` is @NotNull on CreateTicketRequest
// and TicketService.create 400s without it, so a fixture that hardcoded one would break the first
// time UAT's customer master was re-seeded — which, given UAT was rebuilt from production once
// already, is not hypothetical.

// ⚠️ THE THREE STATUS AXES ON A DEAL DO NOT SHARE A CASING CONVENTION. A deal row carries three
// independent state fields, and asserting the wrong case is a failure that reads like a broken
// feature rather than a broken test:
//
//     summary.status      TicketStatus    lowercase   'cancelled', 'quotation_issued', 'draft'
//     summary.lifecycle   DealLifecycle   UPPERCASE   'ACTIVE', 'CANCELLED', 'CLOSED_LOST'
//     summary.salesStage  DealStage       UPPERCASE   'LEAD_APPROACH', 'ORDER_RECEIVED'
//
// Found the hard way in slice 2: `lifecycle` came back CANCELLED and `status` came back
// `cancelled` in the same response. It matters most at the confirm-order bridge, where the
// precondition is `ticket.status == 'quotation_issued'` — lowercase — while the stage it produces
// is 'ORDER_RECEIVED'. Use the constants below rather than inline literals.
export const TICKET_STATUS = {
  DRAFT: 'draft',
  QUOTATION_ISSUED: 'quotation_issued',
  CANCELLED: 'cancelled',
  CLOSED: 'closed',
};

/** The run id global-setup stamped. Every row a run creates carries it. */
export function runId() {
  const id = process.env.E2E_RUN_ID;
  if (!id) {
    throw new Error(
      'E2E_RUN_ID is not set — global-setup.js stamps it at the start of a remote run.\n' +
        '  Are you invoking playwright directly instead of `npm run test:e2e:uat`?'
    );
  }
  return id;
}

/** `2026-08-15`, in Asia/Bangkok — the timezone the backend's LocalDate fields are read in. */
function today() {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Bangkok' }).format(new Date());
}

/** `2026-09-14` — a follow-up date comfortably in the future, same timezone. */
function inThirtyDays() {
  const d = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000);
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Bangkok' }).format(d);
}

/**
 * Resolves a (customerId, projectId) pair that already exists on the target database.
 *
 * Deliberately does NOT create either. A customer or project created per-run would accumulate in
 * a shared database with no cleanup path — `cancel` reaches deals, nothing reaches a stray
 * project — and would drift the very master data human testers are looking at.
 */
async function resolveProject(session) {
  const customersResponse = await session.get('/api/customers', { failOnStatusCode: false });
  expect(customersResponse.status(), 'GET /api/customers').toBe(200);
  const { customers } = await customersResponse.json();
  expect(
    customers.length,
    'no customers exist on this database — the sales fixtures need at least one'
  ).toBeGreaterThan(0);

  for (const customer of customers) {
    const response = await session.get(`/api/customers/${customer.id}/projects`, {
      failOnStatusCode: false,
    });
    if (response.status() !== 200) continue;
    const { projects } = await response.json();
    if (projects.length > 0) {
      return { customerId: customer.id, customerName: customer.name, projectId: projects[0].id };
    }
  }

  throw new Error(
    `None of the ${customers.length} customers on this database has a project, and ` +
      'CreateTicketRequest.projectId is @NotNull — TicketService.create returns 400 without one.\n\n' +
      '  This fixture deliberately does not create a project: a per-run project would accumulate ' +
      'in a shared database with no cleanup path. Add one through the UI (or ask whoever owns ' +
      'UAT to), then re-run.'
  );
}

/**
 * Creates a deal owned by `sales`, tracked and ready to advance.
 *
 * Sets `nextFollowUpAt` in the same breath, because it is half of the forward-stage readiness
 * gate (TicketService.requireStageAdvanceReadiness) and every journey needs it for the whole run.
 * The other half — an activity newer than the last STAGE_CHANGED — is handled per-move by
 * `advanceStage`, since every stage change (auto-advances included) resets that clock.
 *
 * @param sessions  must contain a `sales` context — TicketService.create is SALES_ROLES only
 * @returns {{id: number, code: string, customerId: number, projectId: number}}
 */
export async function createDeal(sessions, { caseName, entryChannel, items = [] } = {}) {
  const tag = runId();
  const { customerId, customerName, projectId } = await resolveProject(sessions.sales);

  const response = await apiWrite(sessions.sales, 'post', '/api/tickets', {
    // The run id leads the title so one prefix search finds everything a run touched. This is the
    // only handle a human has: sales.ticket.code is server-generated (tickets.nextTicketCode()).
    title: `${tag} ${caseName ?? 'deal'}`,
    priority: 'NORMAL',
    customerId,
    customerName,
    projectId,
    note: `created by the e2e sales suite, run ${tag}`,
    // Omitted deliberately when undefined: EntryChannel.UNSPECIFIED is legal at creation and
    // ILLEGAL as a PATCH input (setEntryChannel 400s on it), and asserting that asymmetry needs
    // both paths to stay reachable.
    ...(entryChannel ? { entryChannel } : {}),
    items,
  });
  expect(response.status(), `POST /api/tickets (${caseName})`).toBe(200);
  const { ticket } = await response.json();

  await setTracking(sessions, ticket.summary.id, { nextFollowUpAt: inThirtyDays() });

  return {
    id: ticket.summary.id,
    code: ticket.summary.code,
    customerId,
    projectId,
  };
}

/**
 * PUT /api/tickets/{id}/tracking — FULL REPLACE.
 *
 * TrackingUpdateRequest carries every tracking field, and a null clears the stored value. Send
 * everything you want kept, every time; a partial PUT silently wipes `nextFollowUpAt` and the
 * next forward stage move fails the readiness gate for a reason that points nowhere near here.
 */
export async function setTracking(sessions, ticketId, fields) {
  const response = await apiWrite(sessions.sales, 'put', `/api/tickets/${ticketId}/tracking`, fields);
  expect(response.status(), `PUT /api/tickets/${ticketId}/tracking`).toBe(200);
  return response;
}

/** POST /api/tickets/{id}/activities. `kind` is NotBlank; `activityDate` NotNull. */
export async function logActivity(sessions, ticketId, { kind = 'CALL', note } = {}) {
  const tag = runId();
  const response = await apiWrite(sessions.sales, 'post', `/api/tickets/${ticketId}/activities`, {
    activityDate: today(),
    kind,
    note: note ? `${tag} ${note}` : `${tag} e2e activity`,
  });
  expect(response.status(), `POST /api/tickets/${ticketId}/activities`).toBe(200);
  return response;
}

/** GET /api/tickets/{id} → the full TicketDto (summary, items, events, quotations). */
export async function readDeal(sessions, role, ticketId) {
  const response = await sessions[role].get(`/api/tickets/${ticketId}`, { failOnStatusCode: false });
  expect(response.status(), `${role} GET /api/tickets/${ticketId}`).toBe(200);
  const { ticket } = await response.json();
  return ticket;
}

/**
 * The ordered STAGE_CHANGED chain, as `[{ from, to, message }]`.
 *
 * This is what proves a ROUTE rather than a destination. `expect(salesStage).toBe('CLOSED_PAID')`
 * would be satisfied by one illegal jump; the event chain would not. TicketRepository orders
 * events `created_at ASC, event_id ASC`, and every stage write — manual (updateStage) and
 * automatic (autoAdvanceStage) — emits exactly one, so the sequence is complete and ordered.
 *
 * Auto hops carry the message 'อัตโนมัติจากขั้นตอนของดีล'; manual ones carry the caller's note
 * or null. That difference is how a journey tells "the system advanced it" from "the rep clicked".
 */
export async function stageHistory(sessions, role, ticketId) {
  const ticket = await readDeal(sessions, role, ticketId);
  return (ticket.events ?? [])
    .filter((event) => event.kind === 'STAGE_CHANGED')
    .map((event) => ({ from: event.fromStatus, to: event.toStatus, message: event.message }));
}

/**
 * Best-effort teardown. NEVER throws.
 *
 * `cancel` is owner-only (TicketService:2123 compares createdById to the actor) and the `sales`
 * persona is the owner, so it works — but teardown must not turn a passing assertion red, and
 * must not mask a real failure with a second one. Mirrors write-overtime.spec.js's cancelOvertime.
 *
 * Deliberately `cancel`, not `markLost`: lost writes lost_reason, a reporting input the CEO
 * actually reads. "Cancelled is not lost" is the distinction DealCancelReason exists to preserve.
 *
 * Cancel is terminal, so the row survives as CANCELLED — hidden from the active-deal queue but
 * present in an unfiltered list. That residue is the accepted cost; the run-id prefix is what
 * makes it sweepable (`WHERE title LIKE 'E2E-%'`).
 */
export async function cancelDeal(sessions, ticketId) {
  try {
    await apiWrite(sessions.sales, 'post', `/api/tickets/${ticketId}/cancel`, {
      reason: 'OTHER',
      note: `${runId()} e2e teardown`,
    });
  } catch {
    // Swallowed on purpose — see the header.
  }
}
