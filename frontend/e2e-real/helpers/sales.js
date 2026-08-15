import { expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';
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

// ── Slice 3: the pricing-request chain ──────────────────────────────────────────────────────
//
// createDeal (above) gets a deal to LEAD_APPROACH. Everything below drives it from there to
// ORDER_RECEIVED through the PricingRequest -> FactoryQuote -> PricingDecision ->
// CustomerQuotation -> OrderConfirmation aggregate chain (backend/src/main/java/th/co/glr/hr/
// {pricingrequest,factoryquote,pricingdecision,customerquotation,orderconfirmation}/).
//
// ── Every hop's role is enforced, not a convenience ──────────────────────────────────────────
// sales creates/submits the PCR and everything customer-facing (quotation, outcome,
// confirm-order); import owns pickup through factory costing; ceo alone computes the landed
// cost and decides the selling price. A wrong role on any of these is a 403 from the real
// service (PricingRequestService/FactoryQuoteService/PricingDecisionService each keep their own
// role set — see each function's own file:line below), not a client-side convenience the mock
// would let slide.
//
// ── Currency is pinned THB throughout, on purpose ────────────────────────────────────────────
// FxResolver.resolve (pricing/FxResolver.java:34-39) returns rate 1 for THB via a synthetic
// fallback with no `sales.fx_rates` row required; any other currency needs a BOT-sourced rate
// less than 7 days old, and UAT's BOT_FX_API_TOKEN is sync:false, so that fetcher may be
// silently no-op'ing. Every currency field below — the PCR item's targetCurrency, the factory
// quote item's own currency, the pricing decision's currency — is 'THB', independently: each is
// resolved through FxResolver separately (PricingRequestService never touches FX at all; the
// factory-quote item's currency feeds LandedCostCalculator.calculate's per-line resolveFx;
// PricingDecisionService.startReview resolves a SECOND, independent FX pin for the decision's
// own currency).
//
// ── sqmPerUnit is required unconditionally, not only for a PER_SQM line ──────────────────────
// LandedCostCalculator.calculate calls resolveSqmPerPiece for EVERY line regardless of unit
// basis (pricingcosting/LandedCostCalculator.java:90, 212-234) — freight/insurance/inland are
// always priced per sqm of product. Every fixture below uses PER_PIECE for both the requested
// and quoted unit basis (the simplest basis — pricePerPiece/quantityToPieces need no conversion
// factor for it) but still supplies the factory quote item's own sqmPerUnit, which
// resolveSqmPerPiece prefers over the PCR item's requestedQtySqm/requestedQty fallback.
export const PRICING_REQUEST_STATUS = {
  DRAFT: 'DRAFT',
  SUBMITTED: 'SUBMITTED',
  IMPORT_REVIEWING: 'IMPORT_REVIEWING',
  AWAITING_FACTORY_RESPONSE: 'AWAITING_FACTORY_RESPONSE',
  READY_FOR_CEO_REVIEW: 'READY_FOR_CEO_REVIEW',
  CEO_REVIEWING: 'CEO_REVIEWING',
  APPROVED_FOR_QUOTATION: 'APPROVED_FOR_QUOTATION',
  QUOTATION_ISSUED: 'QUOTATION_ISSUED',
  QUOTATION_ACCEPTED: 'QUOTATION_ACCEPTED',
  CANCELLED: 'CANCELLED',
  SUPERSEDED: 'SUPERSEDED',
};

export const FACTORY_QUOTE_STATUS = {
  DRAFT: 'DRAFT',
  RESPONSE_RECEIVED: 'RESPONSE_RECEIVED',
  READY_FOR_COSTING: 'READY_FOR_COSTING',
};

export const PRICING_DECISION_STATUS = {
  DRAFT: 'DRAFT',
  APPROVED: 'APPROVED',
};

export const QUOTATION_STATUS = {
  DRAFT: 'DRAFT',
  ISSUED: 'ISSUED',
  ACCEPTED: 'ACCEPTED',
};

/** th.co.glr.hr.pricingrequest.PricingRequestRecipient's three values. */
export const PRICING_REQUEST_RECIPIENT = { DESIGNER: 'DESIGNER', OWNER: 'OWNER', BUYER: 'BUYER' };

/**
 * The DealStage each recipient auto-advances the deal to on the quotation's first issue —
 * TicketService.stageForQuotationRecipient (ticket/TicketService.java:1910-1921). Exported so a
 * spec can assert the mapping without hand-copying it a second time.
 */
export const STAGE_FOR_RECIPIENT = {
  DESIGNER: 'QUOTE_DESIGN_SIDE',
  OWNER: 'QUOTE_OWNER',
  BUYER: 'QUOTE_BUYER',
};

/** The literal STAGE_CHANGED message every AUTOMATIC hop carries — see stageHistory's Javadoc above. */
export const AUTO_STAGE_MESSAGE = 'อัตโนมัติจากขั้นตอนของดีล';

/**
 * Discovers a factory this database can actually cost, so PricingDecisionService.startReview
 * (the CEO step, eight calls after this one) does not 422.
 *
 * LandedCostCalculator.calculate (pricingcosting/LandedCostCalculator.java:76-84) looks the
 * factory up by name and requires a non-null country; it then requires a
 * sales.price_calc_config row for THAT country (priceConfigs.findCurrentByCountry, line 85-87).
 * A factory with no country, or a country with no config, 422s — at the CEO step, i.e. long
 * after the pricing request, factory quote and factory response were already built on it. This
 * checks both gates up front instead.
 *
 * Both reads need `import` (or `ceo`) — FactoryConfigController.READ_ROLES and
 * PriceCalcConfigController.READ_ROLES are both {ceo, import}
 * (factory/FactoryConfigController.java:28, pricing/PriceCalcConfigController.java:34); `sales`
 * cannot call either.
 *
 * @returns {{id: number, factoryName: string, email: string, currency: string, unit: string, country: string}}
 */
export async function resolveFactory(sessions) {
  const factoriesResponse = await sessions.import.get('/api/factory-configs', { failOnStatusCode: false });
  expect(factoriesResponse.status(), 'GET /api/factory-configs').toBe(200);
  const { factories } = await factoriesResponse.json();

  const configsResponse = await sessions.import.get('/api/price-calc-configs', { failOnStatusCode: false });
  expect(configsResponse.status(), 'GET /api/price-calc-configs').toBe(200);
  const { configs } = await configsResponse.json();
  const countriesWithConfig = new Set(configs.map((config) => config.country));

  const usable = factories.find((factory) => factory.country && countriesWithConfig.has(factory.country));
  expect(
    usable,
    `none of the ${factories.length} factories on this database has both a country and a ` +
      'matching price_calc_config — LandedCostCalculator.calculate 422s on either gap ' +
      '(pricingcosting/LandedCostCalculator.java:76-87). This is a UAT master-data gap, not a ' +
      'test bug: raise it with whoever owns the UAT factory-config / price-calc-config data ' +
      'rather than changing this fixture.'
  ).toBeTruthy();
  return usable;
}

/**
 * Step 1. sales POST /api/tickets/{id}/pricing-requests -> PCR DRAFT.
 *
 * PricingRequestController.createDraft (pricingrequest/PricingRequestController.java:57-66);
 * body shape th.co.glr.hr.pricingrequest.PricingRequestRequests.CreatePricingRequestRequest
 * (pricingrequest/PricingRequestRequests.java:15-25), items shape
 * PricingRequestItemRequest (same file, :39-61).
 *
 * The single item identifies itself by model/productDescription rather than a
 * sourceTicketItemId (PricingRequestService.isProductIdentified accepts either — :897-899) since
 * createDeal's own deals carry no items. recipientLabel (free text) is used instead of
 * recipientContactId so this never depends on a customer contact existing on the discovered
 * project (validateRecipientContactBelongsToCustomer is a no-op when recipientContactId is null
 * — PricingRequestService.java:947-955).
 *
 * @param sessions   must contain `sales` — createDraft is SALES_ROLES-only and further requires
 *                    the acting session to be the deal's OWNER (ticket.createdById == actor.id).
 * @param ticketId   the deal (from createDeal) this pricing request is filed against.
 * @param opts.factoryName    from resolveFactory(sessions) — becomes the item's free-text
 *                    `factory` field, which FactoryQuoteService.groupByFactory
 *                    (factoryquote/FactoryQuoteService.java:680-691) and LandedCostCalculator
 *                    both key off verbatim.
 * @param opts.recipientType  one of PRICING_REQUEST_RECIPIENT; default DESIGNER.
 * @param opts.requestedQty   default 10.
 */
export async function createPricingRequest(
  sessions,
  ticketId,
  { factoryName, recipientType = PRICING_REQUEST_RECIPIENT.DESIGNER, requestedQty = 10 } = {}
) {
  const tag = runId();
  const response = await apiWrite(sessions.sales, 'post', `/api/tickets/${ticketId}/pricing-requests`, {
    recipientType,
    recipientLabel: `${tag} recipient`,
    targetCurrency: 'THB',
    note: `${tag} e2e pricing request`,
    clientRequestId: randomUUID(),
    items: [
      {
        brand: 'E2E',
        model: `${tag} model`,
        productDescription: `${tag} sales pipeline e2e product`,
        factory: factoryName,
        requestedQty,
        requestedQtySqm: requestedQty,
        requestedUnit: 'PCS',
        requestedUnitBasis: 'PER_PIECE',
        quantityType: 'CONFIRMED',
      },
    ],
  });
  expect(response.status(), `POST /api/tickets/${ticketId}/pricing-requests`).toBe(201);
  const { pricingRequest } = await response.json();
  return pricingRequest;
}

/**
 * Step 2. sales POST /api/pricing-requests/{id}/submit -> SUBMITTED.
 * PricingRequestController.submit (pricingrequest/PricingRequestController.java:101-105) takes
 * no request body.
 */
export async function submitPricingRequest(sessions, pricingRequestId) {
  const response = await apiWrite(sessions.sales, 'post', `/api/pricing-requests/${pricingRequestId}/submit`);
  expect(response.status(), `POST /api/pricing-requests/${pricingRequestId}/submit`).toBe(200);
  const { pricingRequest } = await response.json();
  return pricingRequest;
}

/**
 * Step 3. import POST /api/pricing-requests/{id}/pickup -> IMPORT_REVIEWING.
 * PricingRequestController.pickup (pricingrequest/PricingRequestController.java:107-111), no
 * request body.
 */
export async function pickupPricingRequest(sessions, pricingRequestId) {
  const response = await apiWrite(sessions.import, 'post', `/api/pricing-requests/${pricingRequestId}/pickup`);
  expect(response.status(), `POST /api/pricing-requests/${pricingRequestId}/pickup`).toBe(200);
  const { pricingRequest } = await response.json();
  return pricingRequest;
}

/**
 * Step 4. import POST /api/pricing-requests/{id}/factory-email-drafts -> one FactoryQuoteDto
 * per factory, each DRAFT. FactoryQuoteController.generateDrafts
 * (factoryquote/FactoryQuoteController.java:50-54), no request body.
 *
 * Does NOT transition the pricing request (FactoryQuoteService.generateDrafts never calls
 * PricingRequestRepository.transition) — a caller should re-read the PCR afterward to confirm
 * that, rather than assume it.
 *
 * @returns {Array} the created/current FactoryQuoteDto list (one per distinct factory name
 *                    among the PCR's items).
 */
export async function generateFactoryEmailDrafts(sessions, pricingRequestId) {
  const response = await apiWrite(sessions.import, 'post', `/api/pricing-requests/${pricingRequestId}/factory-email-drafts`);
  expect(response.status(), `POST /api/pricing-requests/${pricingRequestId}/factory-email-drafts`).toBe(200);
  const { items } = await response.json();
  return items;
}

/**
 * Step 5. import POST /api/factory-quotes/{id}/receive -> quote RESPONSE_RECEIVED; the FIRST
 * response on a PCR still IMPORT_REVIEWING also auto-advances it to AWAITING_FACTORY_RESPONSE
 * (FactoryQuoteService.receive, factoryquote/FactoryQuoteService.java:464-586 — the
 * DRAFT/REQUESTED branch at :499-538 that a freshly-drafted quote always takes).
 *
 * ⛔ Never call POST /api/factory-quotes/{id}/send or any factory-emails/send endpoint — UAT's
 * Resend integration ignores APP_MAIL_OVERRIDE_TO (issue #782) and would mail a real inbox.
 * receive() accepts a quote straight from DRAFT (line 499), so send() is never needed.
 *
 * currency is pinned THB (see this file's header). sqmPerUnit is supplied even though the
 * chosen unitBasis is PER_PIECE — see this file's header on why that field is unconditional.
 *
 * Body shape ReceiveFactoryQuoteRequest / ReceiveFactoryQuoteItemRequest
 * (factoryquote/FactoryQuoteRequests.java:28-55). quotedUnit is canonicalized through the SAME
 * UnitBasis.canonicalize as unitBasis (FactoryQuoteService.java:721-722, pricingrequest/
 * UnitBasis.java:42-55) — 'PCS' is one of its recognised PER_PIECE synonyms, not arbitrary free
 * text like the PCR item's own requestedUnit.
 *
 * @param pricingRequestItemId  the PCR item id this response covers (from createPricingRequest's
 *                    returned `.items[0].id`) — must exactly match ReceiveFactoryQuoteItemRequest
 *                    .pricingRequestItemId, or validateAndNormalizeResponseItems 400s
 *                    ("การตอบกลับของโรงงานต้องครบตามรายการที่ขอไปทุกรายการเท่านั้น").
 */
export async function receiveFactoryQuote(
  sessions,
  factoryQuoteId,
  pricingRequestItemId,
  { rawUnitPrice = 150, quotedQuantity = 10 } = {}
) {
  const tag = runId();
  const response = await apiWrite(sessions.import, 'post', `/api/factory-quotes/${factoryQuoteId}/receive`, {
    supplierQuoteRef: `${tag}-quote`,
    defaultCurrency: 'THB',
    paymentTerms: '30% deposit, 70% before shipment',
    leadTimeText: '30 days',
    items: [
      {
        pricingRequestItemId,
        supplierProductDescription: `${tag} supplier response`,
        quotedQuantity,
        quotedUnit: 'PCS',
        unitBasis: 'PER_PIECE',
        rawUnitPrice,
        currency: 'THB',
        sqmPerUnit: 1,
        leadTimeText: '30 days',
      },
    ],
    clientRequestId: randomUUID(),
  });
  expect(response.status(), `POST /api/factory-quotes/${factoryQuoteId}/receive`).toBe(200);
  const { factoryQuote } = await response.json();
  return factoryQuote;
}

/**
 * Step 6. import POST /api/factory-quotes/{id}/mark-ready-for-costing -> quote
 * READY_FOR_COSTING; the moment EVERY item on the PCR resolves
 * (LandedCostCalculator.isFullyResolvable), auto-advances the PCR AWAITING_FACTORY_RESPONSE ->
 * READY_FOR_CEO_REVIEW in the SAME call (FactoryQuoteService.markReadyForCosting,
 * factoryquote/FactoryQuoteService.java:611-660, the auto-advance at :642-658).
 * FactoryQuoteController.markReady (factoryquote/FactoryQuoteController.java:108-112), no
 * request body.
 */
export async function markFactoryQuoteReadyForCosting(sessions, factoryQuoteId) {
  const response = await apiWrite(sessions.import, 'post', `/api/factory-quotes/${factoryQuoteId}/mark-ready-for-costing`);
  expect(response.status(), `POST /api/factory-quotes/${factoryQuoteId}/mark-ready-for-costing`).toBe(200);
  const { factoryQuote } = await response.json();
  return factoryQuote;
}

/**
 * Step 7. ceo POST /api/pricing-requests/{id}/pricing-decisions -> decision DRAFT, PCR
 * READY_FOR_CEO_REVIEW -> CEO_REVIEWING. The landed cost is computed HERE
 * (PricingDecisionService.startReview, pricingdecision/PricingDecisionService.java:98-186 —
 * `LandedCostCalculator.calculate(summary)` at line 139); Import never submits a costing of its
 * own (V141, "CEO owns costing" — PricingCostingService is read-only).
 *
 * Body shape StartPricingDecisionRequest (pricingdecision/PricingDecisionRequests.java:11-12) —
 * every field optional. currency is pinned THB again, independently of the factory-quote item's
 * own currency pin (see this file's header) — this is the DECISION's own FX resolution
 * (PricingDecisionService.java:144-145). defaultMarginPct is supplied so every item's
 * proposedMarginPct/proposedSellingPricePerRequestedUnit is populated immediately
 * (PricingDecisionService.java:168-174, PricingDecisionRepository.WriteItem) — approve() 422s
 * ("ทุกรายการต้องระบุ margin และราคาขายขั้นต่ำก่อนอนุมัติ") on any item with a null margin.
 */
export async function startPricingDecision(sessions, pricingRequestId, { defaultMarginPct = 0.3 } = {}) {
  const tag = runId();
  const response = await apiWrite(sessions.ceo, 'post', `/api/pricing-requests/${pricingRequestId}/pricing-decisions`, {
    defaultMarginPct,
    currency: 'THB',
    ceoNote: `${tag} e2e pricing decision`,
    clientRequestId: randomUUID(),
  });
  expect(response.status(), `POST /api/pricing-requests/${pricingRequestId}/pricing-decisions`).toBe(200);
  const { decision } = await response.json();
  return decision;
}

/**
 * NOT one of the chain's 12 numbered hops — it moves no PCR status — but load-bearing: without
 * it, approve() (step 8) always 422s. insertItems never writes
 * minimum_selling_price_per_requested_unit at all (pricingdecision/
 * PricingDecisionRepository.java:97-134 — the INSERT's column list simply omits it), so every
 * item's minimumSellingPricePerRequestedUnit is null until a PUT sets it, and
 * PricingDecisionService.approve refuses to approve any item that is still null
 * (PricingDecisionService.java:381-395).
 *
 * Floors each item at its own frozen cost (zero-margin floor); startPricingDecision's
 * defaultMarginPct > 0 always clears that floor, so approve()'s own
 * `finalUnitPrice < minimumSellingPrice` check downstream (CustomerQuotationService.issue,
 * customerquotation/CustomerQuotationService.java:299-305) can never trip from this fixture.
 *
 * PricingDecisionController.update (pricingdecision/PricingDecisionController.java:63-71); the
 * repository UPDATE is a COALESCE (PricingDecisionRepository.java:168-183), so omitting
 * marginPct here does not disturb what startPricingDecision already set.
 *
 * @param items  the decision's own `.items` array (from startPricingDecision's response) — each
 *                must carry `id` and `frozenLandedCostPerRequestedUnitThb`.
 */
export async function setPricingDecisionMinimums(sessions, decisionId, items) {
  const response = await apiWrite(sessions.ceo, 'put', `/api/pricing-decisions/${decisionId}`, {
    items: items.map((item) => ({
      pricingDecisionItemId: item.id,
      minimumSellingPrice: item.frozenLandedCostPerRequestedUnitThb,
    })),
  });
  expect(response.status(), `PUT /api/pricing-decisions/${decisionId}`).toBe(200);
  const { decision } = await response.json();
  return decision;
}

/**
 * Step 8. ceo POST /api/pricing-decisions/{id}/approve -> APPROVED, PCR CEO_REVIEWING ->
 * APPROVED_FOR_QUOTATION. PricingDecisionController.approve
 * (pricingdecision/PricingDecisionController.java:100-108); body shape
 * ApprovePricingDecisionRequest (pricingdecision/PricingDecisionRequests.java:35) — deliberately
 * no selling-price/margin field, since approval always freezes whatever proposedMarginPct each
 * item currently holds.
 */
export async function approvePricingDecision(sessions, decisionId) {
  const response = await apiWrite(sessions.ceo, 'post', `/api/pricing-decisions/${decisionId}/approve`, {
    ceoNote: `${runId()} e2e approval`,
    clientRequestId: randomUUID(),
  });
  expect(response.status(), `POST /api/pricing-decisions/${decisionId}/approve`).toBe(200);
  const { decision } = await response.json();
  return decision;
}

/**
 * Step 9. sales POST /api/pricing-requests/{id}/quotations -> customer quotation DRAFT.
 * CustomerQuotationController.create (customerquotation/CustomerQuotationController.java:49-60);
 * body shape CreateCustomerQuotationRequest (customerquotation/CustomerQuotationRequests.java:
 * 13-20) — every field optional. Requires the PCR at APPROVED_FOR_QUOTATION with a current
 * APPROVED decision (CustomerQuotationService.create, :101-145) and sources every line's price
 * from PricingDecisionSalesViewDto — never from cost (design correction 2: sales can never see
 * cost/margin, only the approved selling price).
 *
 * Never moves the PCR status (rule 6 — a DRAFT quotation is not yet a commitment); a caller
 * should re-read the PCR afterward to confirm that, rather than assume it.
 */
export async function createCustomerQuotation(sessions, pricingRequestId) {
  const tag = runId();
  const response = await apiWrite(sessions.sales, 'post', `/api/pricing-requests/${pricingRequestId}/quotations`, {
    paymentTerms: '30% deposit, 70% before shipment',
    leadTime: '30 days',
    deliveryTerms: 'DAP site',
    customerNotes: `${tag} e2e customer quotation`,
    clientRequestId: randomUUID(),
  });
  expect(response.status(), `POST /api/pricing-requests/${pricingRequestId}/quotations`).toBe(201);
  const { quotation } = await response.json();
  return quotation;
}

/**
 * Step 10. sales POST /api/customer-quotations/{id}/issue -> ISSUED; PCR
 * APPROVED_FOR_QUOTATION -> QUOTATION_ISSUED; auto-advances the DEAL's own salesStage per
 * recipient type — see STAGE_FOR_RECIPIENT. CustomerQuotationController.issue
 * (customerquotation/CustomerQuotationController.java:87-94);
 * CustomerQuotationService.issue (customerquotation/CustomerQuotationService.java:272-349) is
 * where both transitions happen: the PCR transition at :323-329, then the deal-stage advance at
 * :334 via TicketService.advanceStageForCustomerQuotationIssue
 * (ticket/TicketService.java:1890-1897), which emits the STAGE_CHANGED event stageHistory()
 * reads carrying AUTO_STAGE_MESSAGE (ticket/TicketService.java:1934-1935).
 */
export async function issueCustomerQuotation(sessions, quotationId) {
  const response = await apiWrite(sessions.sales, 'post', `/api/customer-quotations/${quotationId}/issue`, {
    clientRequestId: randomUUID(),
  });
  expect(response.status(), `POST /api/customer-quotations/${quotationId}/issue`).toBe(200);
  const { quotation } = await response.json();
  return quotation;
}

/**
 * Step 11. sales POST /api/customer-quotations/{id}/outcome ACCEPTED -> PCR QUOTATION_ISSUED ->
 * QUOTATION_ACCEPTED. CustomerQuotationController.recordOutcome
 * (customerquotation/CustomerQuotationController.java:114-120); body shape
 * RecordQuotationOutcomeRequest (customerquotation/CustomerQuotationRequests.java:55-59). The
 * PCR transition only fires for outcome ACCEPTED, and only from QUOTATION_ISSUED
 * (CustomerQuotationService.java:509-516) — this is the one bridge confirmOrder (step 12) needs.
 */
export async function acceptCustomerQuotation(sessions, quotationId) {
  const tag = runId();
  const response = await apiWrite(sessions.sales, 'post', `/api/customer-quotations/${quotationId}/outcome`, {
    outcome: 'ACCEPTED',
    customerNote: `${tag} e2e customer accepted`,
    clientRequestId: randomUUID(),
  });
  expect(response.status(), `POST /api/customer-quotations/${quotationId}/outcome`).toBe(200);
  const { quotation } = await response.json();
  return quotation;
}

/**
 * Step 12. sales POST /api/pricing-requests/{id}/confirm-order -> the bridge out of the
 * PricingRequest chain into the legacy ticket payment/stage machinery: ticket.status
 * draft -> quotation_issued, then TicketService.confirmCustomer (called internally) sets
 * paymentStatus CUSTOMER_CONFIRMED and auto-advances salesStage to ORDER_RECEIVED.
 * OrderConfirmationController.confirmOrder
 * (orderconfirmation/OrderConfirmationController.java:36-44); body shape ConfirmOrderRequest
 * (orderconfirmation/OrderConfirmationRequests.java:11).
 *
 * Reachable ONLY from PCR QUOTATION_ACCEPTED (OrderConfirmationService.confirmOrder,
 * orderconfirmation/OrderConfirmationService.java:100-197, the gate at :127-131) — steps 10
 * (issue) and 11 (accept) must both have already run, in that order, or this 409s.
 *
 * Does not itself advance the PCR status further (it stays QUOTATION_ACCEPTED — only
 * orderConfirmedAt is stamped, at :133); the ticket-level status/paymentStatus/salesStage triple
 * is what actually moved, which is why a caller should re-read the TICKET (readDeal), not just
 * trust this call's own response, for the final assertion.
 *
 * @returns {{ticket: object, pricingRequest: object}} OrderConfirmationResultDto
 *          (orderconfirmation/OrderConfirmationDtos.java:9-16).
 */
export async function confirmOrder(sessions, pricingRequestId) {
  const response = await apiWrite(sessions.sales, 'post', `/api/pricing-requests/${pricingRequestId}/confirm-order`, {
    clientRequestId: randomUUID(),
  });
  expect(response.status(), `POST /api/pricing-requests/${pricingRequestId}/confirm-order`).toBe(200);
  const { result } = await response.json();
  return result;
}

/**
 * GET /api/pricing-requests/{id} -> the full PricingRequestDetailDto (summary, items, events).
 * Mirrors readDeal above — re-reading rather than trusting a mutating call's own response is
 * what several of the functions above call out by name as required, not optional, since most of
 * the chain's endpoints return a DIFFERENT aggregate's DTO (FactoryQuoteDto, PricingDecisionDto,
 * CustomerQuotationDto) and never echo the PCR's own summary at all.
 */
export async function readPricingRequest(sessions, role, pricingRequestId) {
  const response = await sessions[role].get(`/api/pricing-requests/${pricingRequestId}`, { failOnStatusCode: false });
  expect(response.status(), `${role} GET /api/pricing-requests/${pricingRequestId}`).toBe(200);
  const { pricingRequest } = await response.json();
  return pricingRequest;
}

// ── Slice 4: the refusal matrix ─────────────────────────────────────────────────────────────
//
// Everything above proves a deal CAN reach a stage. This proves the guards that decide it CANNOT.
// TicketService.updateStage (ticket/TicketService.java:1497-1534) evaluates, in order:
// write-access -> lifecycle -> same-stage -> the FACT GATE -> the NOTE RULE -> READINESS. Get
// that order wrong and a refusal test can pass for the wrong reason — e.g. asserting the 400 note
// message against ORDER_RECEIVED (a fact-gated target) would actually be observing the 409 FACT
// message instead, because requireStageMoveAllowed (:1555-1574) runs requireStageFactsHold
// (:1701-1718) before updateStage's own note check ever runs. uat-sales-refusals.spec.js is
// written around this ordering explicitly; see that file's own header.

/**
 * POST /api/tickets/{id}/stage as `role` — TicketController.updateStage
 * (ticket/TicketController.java:203-209), body shape UpdateStageRequest{stage, note}
 * (ticket/TicketController.java:303-304), decided by TicketService.updateStage
 * (ticket/TicketService.java:1497-1534).
 *
 * ALWAYS logs a fresh deal_activity row FIRST, via {@link logActivity} — which is hardcoded to
 * the `sales` session, same as that function's own contract, so this works regardless of which
 * role is about to attempt the move (the deal is always owned by `sales`; see createDeal). Two
 * independent reasons this is unconditional, not only on a call expected to succeed:
 *
 *   1. The forward readiness gate (requireStageAdvanceReadiness, ticket/TicketService.java:
 *      1587-1592) compares the newest deal_activity row against MAX(ticket_event.created_at
 *      WHERE kind = 'STAGE_CHANGED') (TicketRepository.hasActivitySinceLastStageChange, ticket/
 *      TicketRepository.java:1945-1960) — and EVERY accepted stage change, manual or automatic,
 *      moves that maximum forward. A test that calls this twice in a row for two successful
 *      moves would fail the second call's readiness gate for a reason unrelated to what it is
 *      testing, unless the activity clock is refreshed on every call.
 *   2. It is what lets a refusal be asserted through the exact same function as a success: the
 *      only thing that differs between a passing call and a failing one is targetStage/note/role/
 *      expect, never an incidental extra step a refusal happened to skip. That is what proves a
 *      refusal in uat-sales-refusals.spec.js is the RULE under test, not an artefact of a
 *      differently-shaped request.
 *
 * Refusal #8 (no NEW activity since the last stage change) deliberately does NOT route its
 * middle, refused call through this helper — that specific case would be unreachable if this
 * helper's own unconditional activity-log ran first. See that test's own comment.
 *
 * @param role          key into `sessions` — the actor attempting the move. Not necessarily the
 *                       deal's owner: several refusals are specifically about a non-owner or
 *                       wrong-role actor.
 * @param opts.note      defaults to `null` — the exact shape the note-rule refusals need. Pass a
 *                       non-blank string to satisfy DealStage.requiresJustification.
 * @param opts.expect    `'ok'` (default; asserts 200) or an exact HTTP status number for a
 *                       refusal. Named `expect` per the brief; destructured to a local
 *                       `expectation` so it does not shadow the `expect` imported from
 *                       @playwright/test that this function still needs to call.
 * @returns the raw response — callers read `.status()`/`.json()` themselves, since a refusal's
 *          body (ErrorResponse{message,status}) and a success's body (TicketDetailResponse
 *          {ticket}) are different shapes and only the caller knows which one to expect.
 */
export async function advanceStage(
  sessions,
  role,
  ticketId,
  targetStage,
  { note = null, expect: expectation = 'ok' } = {}
) {
  await logActivity(sessions, ticketId, { note: `advanceStage clock reset before -> ${targetStage}` });
  const response = await apiWrite(sessions[role], 'post', `/api/tickets/${ticketId}/stage`, {
    stage: targetStage,
    note,
  });
  const wantStatus = expectation === 'ok' ? 200 : expectation;
  expect(
    response.status(),
    `${role} POST /api/tickets/${ticketId}/stage -> ${targetStage} (note=${JSON.stringify(note)})`
  ).toBe(wantStatus);
  return response;
}

/**
 * GET /api/tickets/{id}/actions -> the server's own per-stage verdicts — TicketController.actions
 * (ticket/TicketController.java:68-72, gated only by requireViewAccess so any VIEWER_ROLES caller
 * may read it), TicketActionsResponse.stageDecisions (ticket/TicketResponses.java:13-32), computed
 * by the private TicketService.stageDecisions (ticket/TicketService.java:1628-1647): one entry per
 * DealStage.ORDER stage, `requiresReason` is DealStage.requiresJustification(currentStage,
 * thatStage) — populated even when `allowed` is false, since the note rule does not depend on the
 * gates. See refusal #6, which recomputes the same predicate independently in JS and asserts
 * agreement for all 15 stages.
 */
export async function stageDecisions(sessions, role, ticketId) {
  const response = await sessions[role].get(`/api/tickets/${ticketId}/actions`, { failOnStatusCode: false });
  expect(response.status(), `${role} GET /api/tickets/${ticketId}/actions`).toBe(200);
  const { stageDecisions: decisions } = await response.json();
  return decisions;
}
