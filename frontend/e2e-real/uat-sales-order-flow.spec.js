import { test, expect } from '@playwright/test';
import { uatSessionFor, disposeSessions } from './helpers/api.js';
import { UAT_SALES_ROLES } from './helpers/uat-accounts.js';
import {
  AUTO_STAGE_MESSAGE,
  FACTORY_QUOTE_STATUS,
  PRICING_DECISION_STATUS,
  PRICING_REQUEST_STATUS,
  QUOTATION_STATUS,
  STAGE_FOR_RECIPIENT,
  TICKET_STATUS,
  acceptCustomerQuotation,
  approvePricingDecision,
  cancelDeal,
  confirmOrder,
  createCustomerQuotation,
  createDeal,
  createPricingRequest,
  generateFactoryEmailDrafts,
  issueCustomerQuotation,
  markFactoryQuoteReadyForCosting,
  pickupPricingRequest,
  readDeal,
  readPricingRequest,
  receiveFactoryQuote,
  resolveFactory,
  setPricingDecisionMinimums,
  stageHistory,
  startPricingDecision,
  submitPricingRequest,
} from './helpers/sales.js';

// ─────────────────────────────────────────────────────────────────────────────────────────────
// SLICE 3 — the pricing-request chain, end to end: a deal driven from creation through
// PricingRequest -> FactoryQuote -> PricingDecision -> CustomerQuotation -> OrderConfirmation,
// to ORDER_RECEIVED.
//
// Twelve hops, four roles, and a THIRD status axis (PricingRequestStatus, alongside
// TicketStatus/DealLifecycle/DealStage — see helpers/sales.js's own header on THAT chain).
// A single `expect(finalStatus).toBe('ORDER_RECEIVED')` at the end would be satisfied by any
// number of wrong routes through this graph — CANCELLED wrong-role attempts, a status skipped
// entirely, confirm-order reached without the facts (payment/stage) that are supposed to gate
// it. So every hop below asserts the PCR's own status immediately after making it, by name, and
// the four endpoints that do NOT echo the PCR's status in their own response
// (factory-email-drafts, receive, mark-ready-for-costing, pricing-decisions, pricing-decisions/
// approve, quotations, issue, outcome) are followed by a fresh readPricingRequest — proving what
// the database now holds, not what the write echoed back.
//
// Every hop also acts as the specific role each service requires — sales for the PCR/quotation
// half, import for the factory-quote half, ceo for the pricing decision. A different role is not
// a convenience this suite tests for leniency on — it is a 403 from the real service, and this
// spec relies on helpers/sales.js's functions each hard-coding the correct `sessions.<role>`
// rather than taking a role parameter, so a copy-paste mistake here would fail loudly rather
// than silently pass with the wrong actor.
// ─────────────────────────────────────────────────────────────────────────────────────────────

test.describe('@uat-sales pricing-request chain — deal to ORDER_RECEIVED', () => {
  /** @type {Record<string, import('@playwright/test').APIRequestContext>} */
  const sessions = {};
  /** Every deal this file creates, so afterAll can take them back even if the test threw. */
  const created = [];

  test.beforeAll(async () => {
    for (const role of UAT_SALES_ROLES) {
      sessions[role] = await uatSessionFor(role);
    }
  });

  test.afterAll(async () => {
    // Best-effort, outside any expect(): teardown must never turn a passing assertion red, and
    // must never mask a real failure with a second one. TicketService.cancel only refuses a
    // CLOSED/CANCELLED ticket (ticket/TicketService.java:2120-2121) — by the end of this test the
    // ticket sits at 'quotation_issued', which is neither, so cancel() still succeeds here, and
    // its own cascade (PricingRequestRepository.findOpenIdsForTicket's `status <> 'CANCELLED'`,
    // pricingrequest/PricingRequestRepository.java:628-636) reaches the PCR even though
    // QUOTATION_ACCEPTED has no forward edge of its own — cancelForDeadDeal bypasses the normal
    // transition table on purpose for exactly this case.
    for (const ticketId of created) {
      await cancelDeal(sessions, ticketId);
    }
    await disposeSessions(sessions);
  });

  test('drives a deal through the pricing-request chain to ORDER_RECEIVED, asserting the PCR status after every hop', async () => {
    // Playwright's default 30s is not enough, and this is not a hang. The chain is ~14 sequential
    // round trips to a Render `starter` instance, several of which do real work — the CEO decision
    // computes a landed cost per item. The first run reached step 8 (approve) and was cut off
    // mid-request at exactly 30.0s; the reported "Request context disposed" was the timeout tearing
    // the context down around an in-flight fetch, not a session problem.
    //
    // 180s matches route-coverage.spec.js. Cases A–D will need more headroom still: Case A runs
    // THREE of these chains (DESIGNER → OWNER → BUYER) plus a fulfilment tail, so budget per-spec
    // rather than assuming this value carries over.
    test.setTimeout(180_000);

    const factory = await test.step('discover a factory this database can actually cost', async () => {
      const usable = await resolveFactory(sessions);
      expect(usable.factoryName, 'a usable factory must have a name').toBeTruthy();
      return usable;
    });

    const deal = await test.step('create the deal (baseline: LEAD_APPROACH, no stage history)', async () => {
      const d = await createDeal(sessions, { caseName: 'slice3 pricing chain' });
      created.push(d.id);
      const history = await stageHistory(sessions, 'sales', d.id);
      expect(history, 'a freshly created deal must have no STAGE_CHANGED event yet').toEqual([]);
      return d;
    });

    let pcr;
    await test.step('1. sales creates the pricing request -> DRAFT', async () => {
      pcr = await createPricingRequest(sessions, deal.id, {
        factoryName: factory.factoryName,
        recipientType: 'DESIGNER',
      });
      expect(pcr.summary.status, 'PCR status after createPricingRequest').toBe(PRICING_REQUEST_STATUS.DRAFT);
      expect(pcr.items, 'the PCR must carry exactly the one item the fixture sent').toHaveLength(1);
    });
    const pcrId = pcr.summary.id;
    const pcrItemId = pcr.items[0].id;

    await test.step('2. sales submits the pricing request -> SUBMITTED', async () => {
      pcr = await submitPricingRequest(sessions, pcrId);
      expect(pcr.summary.status, 'PCR status after submit').toBe(PRICING_REQUEST_STATUS.SUBMITTED);
    });

    await test.step('3. import picks up the pricing request -> IMPORT_REVIEWING', async () => {
      pcr = await pickupPricingRequest(sessions, pcrId);
      expect(pcr.summary.status, 'PCR status after pickup').toBe(PRICING_REQUEST_STATUS.IMPORT_REVIEWING);
    });

    let factoryQuote;
    await test.step('4. import generates the factory email draft -> factory quote DRAFT (PCR unchanged)', async () => {
      const drafts = await generateFactoryEmailDrafts(sessions, pcrId);
      expect(drafts, 'one item routed to one factory must produce exactly one draft').toHaveLength(1);
      factoryQuote = drafts[0];
      expect(factoryQuote.status, 'factory quote status after generateFactoryEmailDrafts').toBe(
        FACTORY_QUOTE_STATUS.DRAFT
      );
      expect(factoryQuote.factoryName, 'the draft must be addressed to the discovered factory').toBe(
        factory.factoryName
      );

      // generateDrafts never transitions the PCR — prove it, rather than assume it.
      pcr = await readPricingRequest(sessions, 'sales', pcrId);
      expect(pcr.summary.status, 'PCR status must be unchanged by generateFactoryEmailDrafts').toBe(
        PRICING_REQUEST_STATUS.IMPORT_REVIEWING
      );
    });

    await test.step('5. import records the factory response -> quote RESPONSE_RECEIVED, PCR AWAITING_FACTORY_RESPONSE', async () => {
      factoryQuote = await receiveFactoryQuote(sessions, factoryQuote.id, pcrItemId);
      expect(factoryQuote.status, 'factory quote status after receive').toBe(FACTORY_QUOTE_STATUS.RESPONSE_RECEIVED);

      pcr = await readPricingRequest(sessions, 'sales', pcrId);
      expect(pcr.summary.status, 'PCR status after the first factory response').toBe(
        PRICING_REQUEST_STATUS.AWAITING_FACTORY_RESPONSE
      );
    });

    await test.step('6. import marks the quote ready -> READY_FOR_COSTING; PCR auto-advances to READY_FOR_CEO_REVIEW', async () => {
      factoryQuote = await markFactoryQuoteReadyForCosting(sessions, factoryQuote.id);
      expect(factoryQuote.status, 'factory quote status after mark-ready-for-costing').toBe(
        FACTORY_QUOTE_STATUS.READY_FOR_COSTING
      );

      pcr = await readPricingRequest(sessions, 'sales', pcrId);
      expect(pcr.summary.status, 'PCR must auto-advance once every item resolves').toBe(
        PRICING_REQUEST_STATUS.READY_FOR_CEO_REVIEW
      );
    });

    let decision;
    await test.step('7. ceo starts the pricing decision -> decision DRAFT, PCR CEO_REVIEWING (landed cost computed here)', async () => {
      decision = await startPricingDecision(sessions, pcrId, { defaultMarginPct: 0.3 });
      expect(decision.status, 'decision status after startReview').toBe(PRICING_DECISION_STATUS.DRAFT);
      expect(decision.items, 'the decision must carry exactly the PCR\'s one item').toHaveLength(1);
      expect(
        decision.items[0].frozenLandedCostPerRequestedUnitThb,
        'the landed cost must have been computed at startReview'
      ).not.toBeNull();
      expect(decision.items[0].proposedMarginPct, 'defaultMarginPct must have seeded every item\'s margin').not.toBeNull();

      pcr = await readPricingRequest(sessions, 'sales', pcrId);
      expect(pcr.summary.status, 'PCR status after startReview').toBe(PRICING_REQUEST_STATUS.CEO_REVIEWING);
    });

    await test.step('(not a chain hop) ceo sets a minimum selling price — approve() 422s without it', async () => {
      decision = await setPricingDecisionMinimums(sessions, decision.id, decision.items);
      expect(
        decision.items[0].minimumSellingPricePerRequestedUnit,
        'minimum selling price must now be set, floored at the frozen cost'
      ).not.toBeNull();

      // A non-transitioning write: confirm the PCR really did stay put.
      pcr = await readPricingRequest(sessions, 'sales', pcrId);
      expect(pcr.summary.status, 'setting the minimum selling price must not move the PCR').toBe(
        PRICING_REQUEST_STATUS.CEO_REVIEWING
      );
    });

    await test.step('8. ceo approves the pricing decision -> APPROVED, PCR CEO_REVIEWING -> APPROVED_FOR_QUOTATION', async () => {
      decision = await approvePricingDecision(sessions, decision.id);
      expect(decision.status, 'decision status after approve').toBe(PRICING_DECISION_STATUS.APPROVED);

      pcr = await readPricingRequest(sessions, 'sales', pcrId);
      expect(pcr.summary.status, 'PCR status after approve').toBe(PRICING_REQUEST_STATUS.APPROVED_FOR_QUOTATION);
    });

    let quotation;
    await test.step('9. sales creates the customer quotation -> DRAFT (PCR unchanged — rule 6)', async () => {
      quotation = await createCustomerQuotation(sessions, pcrId);
      expect(quotation.docStatus, 'quotation docStatus after create').toBe(QUOTATION_STATUS.DRAFT);

      pcr = await readPricingRequest(sessions, 'sales', pcrId);
      expect(pcr.summary.status, 'creating a DRAFT quotation must not move the PCR').toBe(
        PRICING_REQUEST_STATUS.APPROVED_FOR_QUOTATION
      );
    });

    await test.step('10. sales issues the quotation -> ISSUED; PCR QUOTATION_ISSUED; deal stage auto-advances by recipient', async () => {
      quotation = await issueCustomerQuotation(sessions, quotation.id);
      expect(quotation.docStatus, 'quotation docStatus after issue').toBe(QUOTATION_STATUS.ISSUED);

      pcr = await readPricingRequest(sessions, 'sales', pcrId);
      expect(pcr.summary.status, 'PCR status after issue').toBe(PRICING_REQUEST_STATUS.QUOTATION_ISSUED);

      // The deal's own salesStage, auto-advanced by CustomerQuotationService.issue via
      // TicketService.advanceStageForCustomerQuotationIssue — asserted through the STAGE_CHANGED
      // event chain, not just the final value, so an illegal intermediate hop would be caught.
      const history = await stageHistory(sessions, 'sales', deal.id);
      expect(
        history,
        `issuing a DESIGNER quotation must auto-advance the deal to ${STAGE_FOR_RECIPIENT.DESIGNER}, ` +
          'with the AUTOMATIC event message — not a manual note'
      ).toEqual([{ from: 'LEAD_APPROACH', to: STAGE_FOR_RECIPIENT.DESIGNER, message: AUTO_STAGE_MESSAGE }]);
    });

    await test.step('11. sales records the customer\'s ACCEPTED outcome -> PCR QUOTATION_ISSUED -> QUOTATION_ACCEPTED', async () => {
      quotation = await acceptCustomerQuotation(sessions, quotation.id);
      expect(quotation.docStatus, 'quotation docStatus after outcome=ACCEPTED').toBe(QUOTATION_STATUS.ACCEPTED);

      pcr = await readPricingRequest(sessions, 'sales', pcrId);
      expect(pcr.summary.status, 'PCR status after the customer accepts').toBe(
        PRICING_REQUEST_STATUS.QUOTATION_ACCEPTED
      );
    });

    await test.step('12. sales confirms the order -> ticket quotation_issued / CUSTOMER_CONFIRMED / ORDER_RECEIVED', async () => {
      const result = await confirmOrder(sessions, pcrId);
      // confirm-order does not move the PCR status further — only orderConfirmedAt is stamped.
      expect(result.pricingRequest.status, 'confirm-order must not transition the PCR further').toBe(
        PRICING_REQUEST_STATUS.QUOTATION_ACCEPTED
      );
      expect(result.pricingRequest.orderConfirmedAt, 'orderConfirmedAt must now be stamped').not.toBeNull();

      // Re-READ the TICKET rather than trusting the response body: what matters is what the
      // database holds. All three axes asserted together on purpose — the stage without the
      // facts behind it is exactly the bug worth pinning: ORDER_RECEIVED is fact-gated on
      // payment_status, whose only writer is confirmCustomer, which itself requires
      // ticket.status == 'quotation_issued' — lowercase, a DIFFERENT casing convention than the
      // UPPERCASE salesStage it produces in the same response (see helpers/sales.js's own header
      // on TICKET_STATUS for the full three-axis picture).
      const ticket = await readDeal(sessions, 'sales', deal.id);
      expect(ticket.summary.status, 'ticket status (TicketStatus, lowercase)').toBe(TICKET_STATUS.QUOTATION_ISSUED);
      expect(ticket.summary.paymentStatus, 'payment status').toBe('CUSTOMER_CONFIRMED');
      expect(ticket.summary.salesStage, 'sales stage (DealStage, UPPERCASE)').toBe('ORDER_RECEIVED');

      // The full route, not just the destination: two automatic hops (issue's recipient-based
      // advance, then confirmCustomer's own), both carrying the AUTOMATIC message.
      const history = await stageHistory(sessions, 'sales', deal.id);
      expect(history, 'the complete stage route from LEAD_APPROACH to ORDER_RECEIVED').toEqual([
        { from: 'LEAD_APPROACH', to: STAGE_FOR_RECIPIENT.DESIGNER, message: AUTO_STAGE_MESSAGE },
        { from: STAGE_FOR_RECIPIENT.DESIGNER, to: 'ORDER_RECEIVED', message: AUTO_STAGE_MESSAGE },
      ]);
    });
  });
});
