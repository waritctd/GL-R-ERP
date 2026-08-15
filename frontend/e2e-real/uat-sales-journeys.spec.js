import { test, expect } from '@playwright/test';
import { uatSessionFor, disposeSessions } from './helpers/api.js';
import { UAT_SALES_ROLES } from './helpers/uat-accounts.js';
import {
  AUTO_STAGE_MESSAGE,
  PRICING_REQUEST_STATUS,
  TICKET_STATUS,
  acceptCustomerQuotation,
  advanceStage,
  cancelDeal,
  completeDelivery,
  confirmFinalPayment,
  confirmOrder,
  createDeal,
  declareStockCoverage,
  expectNeverEntered,
  expectRoute,
  issueImportRequest,
  markGoodsReceived,
  markIrSent,
  markShipping,
  readDeal,
  resolveFactory,
  runPricingChainToIssue,
  runPricingChainToOrder,
  setEntryChannel,
  stageDecisions,
  stageHistory,
  waiveDeposit,
} from './helpers/sales.js';

// ─────────────────────────────────────────────────────────────────────────────────────────────
// SLICE 5 — the four sales-pipeline JOURNEY specs (Cases A-D), proving the four real routes named
// in DealStage.java:78-100 are what a deal ACTUALLY WALKS, not merely a stage it happens to end
// on:
//
//   A  designer -> owner -> contractor, the full route:
//      S1->S2->S4->S3->S5->S6->S7->S8->S9->S10->(S11)->S12…S17->S18->S19∥S20
//   B  the owner buys direct: skips S3, S4, S7, S8
//   C  a contractor arrives with a BOQ and spec already in hand: starts at S8, skipping S1-S7
//   D  everything already in stock: skips S12-S17 (PROCUREMENT) only
//
// ── Why a destination assertion is not enough ────────────────────────────────────────────────
// `expect(salesStage).toBe('CLOSED_PAID')` is satisfied by any number of illegal routes — a stage
// skipped that should not have been, an extra hop, two hops swapped, or the TEST itself clicking
// a stage into place the system was supposed to reach on its own. Every case below instead reads
// the full STAGE_CHANGED chain (stageHistory) and runs it through expectRoute/expectNeverEntered
// (helpers/sales.js — read that file's own "Slice 5" header for the exact four-part contract:
// chain continuity, exact walk, no stage twice, and per-hop provenance).
//
// ── Reuses slice 3/4 wholesale ───────────────────────────────────────────────────────────────
// Every hop below is one of: createDeal (slice 2), advanceStage (slice 4), or a slice-3 pricing-
// request primitive via the new runPricingChainToIssue/runPricingChainToOrder composites (slice 5,
// helpers/sales.js). Nothing here talks to the API directly — a route this suite cannot express
// through the SAME helpers slices 1-4 already prove correct would not be trustworthy evidence
// about the route itself.
// ─────────────────────────────────────────────────────────────────────────────────────────────

test.describe('@uat-sales pipeline journeys — Cases A-D', () => {
  /** @type {Record<string, import('@playwright/test').APIRequestContext>} */
  const sessions = {};
  /** Every deal this file creates, so afterAll can take them back even if a test threw. */
  const created = [];

  test.beforeAll(async () => {
    for (const role of UAT_SALES_ROLES) {
      sessions[role] = await uatSessionFor(role);
    }
  });

  test.afterAll(async () => {
    // Hooks carry their own timeout and test.setTimeout() on a TEST does not extend it — see
    // slices 3/4's identical comment. Five deals total across four cases (Case C alone creates
    // two), cancelled one at a time against a Render `starter` instance.
    test.setTimeout(180_000);
    for (const ticketId of created) {
      await cancelDeal(sessions, ticketId);
    }
    await disposeSessions(sessions);
  });

  // ── Case A — DESIGNER_LED, the full route ─────────────────────────────────────────────────
  test('Case A — DESIGNER_LED, the full route through all five phases', async () => {
    // Three full pricing-request chains (~50s each on a Render `starter` instance — see slice 3's
    // own timing note) plus a six-call fulfilment tail. Realistically ~200-250s; budgeted well
    // above that.
    test.setTimeout(720_000);

    const factory = await test.step('discover a factory this database can actually cost', async () => {
      const usable = await resolveFactory(sessions);
      expect(usable.factoryName, 'a usable factory must have a name').toBeTruthy();
      return usable;
    });

    const deal = await test.step('create the deal, confirm it starts bare, and label it DESIGNER_LED', async () => {
      const d = await createDeal(sessions, { caseName: 'caseA designer-led' });
      created.push(d.id);
      const history = await stageHistory(sessions, 'sales', d.id);
      expect(history, 'a freshly created deal must have no STAGE_CHANGED history').toEqual([]);
      await setEntryChannel(sessions, d.id, 'DESIGNER_LED');
      return d;
    });

    await test.step('S1 -> S2 (LEAD_APPROACH -> PRESENTATION): manual, no note — adjacent, nothing MANDATORY between', async () => {
      await advanceStage(sessions, 'sales', deal.id, 'PRESENTATION', { note: null });
    });

    // Neither the DESIGNER nor the OWNER chain's return value is needed again — only the auto
    // stage-advance their `issue` call triggers matters here. Only the BUYER chain (below) is the
    // one that goes on to accept + confirm-order, so only its result is captured.
    await test.step('S2 -> S4 (-> QUOTE_DESIGN_SIDE): auto, via the DESIGNER pricing-request chain issuing its quotation', async () => {
      await runPricingChainToIssue(sessions, deal.id, {
        factoryName: factory.factoryName,
        recipientType: 'DESIGNER',
      });
    });

    await test.step('S4 -> S3 (QUOTE_DESIGN_SIDE -> SPEC_APPROVED): manual backward, no note — the ONE hand-patched isRoutineBackwardMove exception', async () => {
      await advanceStage(sessions, 'sales', deal.id, 'SPEC_APPROVED', { note: null });
    });

    await test.step('S3 -> S5 (-> QUOTE_OWNER): auto, via the OWNER pricing-request chain issuing its quotation', async () => {
      await runPricingChainToIssue(sessions, deal.id, {
        factoryName: factory.factoryName,
        recipientType: 'OWNER',
      });
    });

    await test.step('S5 -> S6 -> S7 (-> OWNER_SIGNOFF -> AWAITING_BUYER): manual, no note on either (both adjacent)', async () => {
      await advanceStage(sessions, 'sales', deal.id, 'OWNER_SIGNOFF', { note: null });
      await advanceStage(sessions, 'sales', deal.id, 'AWAITING_BUYER', { note: null });
    });

    let buyerChain;
    await test.step('S7 -> S8 (-> QUOTE_BUYER): auto, via the BUYER pricing-request chain issuing its quotation', async () => {
      buyerChain = await runPricingChainToIssue(sessions, deal.id, {
        factoryName: factory.factoryName,
        recipientType: 'BUYER',
      });
    });

    await test.step('S8 -> S9 (QUOTE_BUYER -> NEGOTIATION): manual, no note — adjacent; NEGOTIATION is the target, never "skipped"', async () => {
      await advanceStage(sessions, 'sales', deal.id, 'NEGOTIATION', { note: null });
    });

    await test.step('S9 -> S10 (-> ORDER_RECEIVED): auto, the slice-3 tail on the BUYER chain (accept -> confirm-order)', async () => {
      await acceptCustomerQuotation(sessions, buyerChain.quotationId);
      const result = await confirmOrder(sessions, buyerChain.pricingRequestId);
      expect(result.pricingRequest.status, 'confirm-order must not transition the PCR further').toBe(
        PRICING_REQUEST_STATUS.QUOTATION_ACCEPTED
      );

      // Re-READ rather than trust confirm-order's own response — see confirmOrder's own doc in
      // helpers/sales.js on why. Without this bridge S10+ is unreachable at all.
      const ticket = await readDeal(sessions, 'sales', deal.id);
      expect(ticket.summary.status, 'ticket status (TicketStatus, lowercase)').toBe(
        TICKET_STATUS.QUOTATION_ISSUED
      );
      expect(ticket.summary.paymentStatus).toBe('CUSTOMER_CONFIRMED');
      expect(ticket.summary.salesStage).toBe('ORDER_RECEIVED');
    });

    await test.step('waive the deposit — needed BEFORE issueImportRequest below, or its depositReady gate 409s first', async () => {
      await waiveDeposit(sessions, deal.id, {
        reason: 'e2e Case A: full route proof needs no real DepositNoticeService document',
      });
    });

    await test.step('S10 -> S12 (-> PROCUREMENT): auto, issueImportRequest — S11 (DEPOSIT_RECEIVED) is a real, structural skip here, not an oversight', async () => {
      const updated = await issueImportRequest(sessions, deal.id);
      expect(updated.summary.salesStage).toBe('PROCUREMENT');
    });

    await test.step('the mid-fulfilment walk to SHIPPING (fulfilment axis only — no DealStage change from either call)', async () => {
      await markIrSent(sessions, deal.id);
      await markShipping(sessions, deal.id);
    });

    await test.step('S12 -> S18 (-> DELIVERY_SCHEDULING): auto, markGoodsReceived — the ONLY non-stock path there', async () => {
      const updated = await markGoodsReceived(sessions, deal.id);
      expect(updated.summary.salesStage).toBe('DELIVERY_SCHEDULING');
    });

    await test.step('S18 -> S19 (-> DELIVERED): auto, completeDelivery (WAREHOUSE source — nothing was ever declared from stock on this route)', async () => {
      const updated = await completeDelivery(sessions, deal.id);
      expect(updated.summary.salesStage).toBe('DELIVERED');
    });

    await test.step('S19 -> S20 (-> CLOSED_PAID): auto, confirmFinalPayment', async () => {
      const updated = await confirmFinalPayment(sessions, deal.id);
      expect(updated.summary.salesStage).toBe('CLOSED_PAID');
    });

    await test.step('prove the ROUTE, not just the destination', async () => {
      const history = await stageHistory(sessions, 'sales', deal.id);
      expectRoute(
        history,
        [
          'LEAD_APPROACH', 'PRESENTATION', 'QUOTE_DESIGN_SIDE', 'SPEC_APPROVED', 'QUOTE_OWNER',
          'OWNER_SIGNOFF', 'AWAITING_BUYER', 'QUOTE_BUYER', 'NEGOTIATION', 'ORDER_RECEIVED',
          'PROCUREMENT', 'DELIVERY_SCHEDULING', 'DELIVERED', 'CLOSED_PAID',
        ],
        [
          /* LEAD_APPROACH      -> PRESENTATION      */ null,
          /* PRESENTATION       -> QUOTE_DESIGN_SIDE  */ AUTO_STAGE_MESSAGE,
          /* QUOTE_DESIGN_SIDE  -> SPEC_APPROVED      */ null,
          /* SPEC_APPROVED      -> QUOTE_OWNER        */ AUTO_STAGE_MESSAGE,
          /* QUOTE_OWNER        -> OWNER_SIGNOFF      */ null,
          /* OWNER_SIGNOFF      -> AWAITING_BUYER     */ null,
          /* AWAITING_BUYER     -> QUOTE_BUYER        */ AUTO_STAGE_MESSAGE,
          /* QUOTE_BUYER        -> NEGOTIATION        */ null,
          /* NEGOTIATION        -> ORDER_RECEIVED     */ AUTO_STAGE_MESSAGE,
          /* ORDER_RECEIVED     -> PROCUREMENT        */ AUTO_STAGE_MESSAGE,
          /* PROCUREMENT        -> DELIVERY_SCHEDULING*/ AUTO_STAGE_MESSAGE,
          /* DELIVERY_SCHEDULING-> DELIVERED          */ AUTO_STAGE_MESSAGE,
          /* DELIVERED          -> CLOSED_PAID        */ AUTO_STAGE_MESSAGE,
        ]
      );
      // The one stage every other hop of the full route touches but this concrete deal does not —
      // "(S11 if a deposit is required)" in the route Javadoc, and this deal's is waived.
      expectNeverEntered(history, ['DEPOSIT_RECEIVED']);

      // Headline assertion, restated explicitly: every MANUAL hop above was called with note: null
      // and returned 200 (advanceStage would have failed the test at the call site otherwise) —
      // five of them, none carrying a note.
      const manualHops = history.filter((hop) => hop.message !== AUTO_STAGE_MESSAGE);
      expect(manualHops, 'the full route needs exactly five manual hops').toHaveLength(5);
      expect(
        manualHops.every((hop) => hop.message === null),
        'zero justification notes are required anywhere on this route'
      ).toBe(true);
    });
  });

  // ── Case B — OWNER_DIRECT ──────────────────────────────────────────────────────────────────
  test('Case B — OWNER_DIRECT, the owner buys direct (skips S3, S4, S7, S8)', async () => {
    test.setTimeout(240_000);

    const factory = await test.step('discover a factory this database can actually cost', async () => {
      const usable = await resolveFactory(sessions);
      expect(usable.factoryName).toBeTruthy();
      return usable;
    });

    const deal = await test.step('create the deal and label it OWNER_DIRECT', async () => {
      const d = await createDeal(sessions, { caseName: 'caseB owner-direct' });
      created.push(d.id);
      await setEntryChannel(sessions, d.id, 'OWNER_DIRECT');
      return d;
    });

    await test.step('S1 -> S2: manual, no note', async () => {
      await advanceStage(sessions, 'sales', deal.id, 'PRESENTATION', { note: null });
    });

    let ownerChain;
    await test.step('S2 -> S5 (-> QUOTE_OWNER): auto, via the OWNER pricing-request chain — S3/S4 are stepped over entirely', async () => {
      ownerChain = await runPricingChainToIssue(sessions, deal.id, {
        factoryName: factory.factoryName,
        recipientType: 'OWNER',
      });
    });

    await test.step('S5 -> S6 (-> OWNER_SIGNOFF): manual, no note', async () => {
      await advanceStage(sessions, 'sales', deal.id, 'OWNER_SIGNOFF', { note: null });
    });

    await test.step(
      'the subtle hop, S6 -> S9 (OWNER_SIGNOFF -> NEGOTIATION): NEGOTIATION is MANDATORY, but it is ' +
        "the TARGET — requiresJustification's subList(from+1, to) is exclusive of `to`, so the " +
        'target is never counted as "skipped" and no note is required. Checked against the SERVER, ' +
        'not just asserted: stageDecisions().requiresReason must independently read false too.',
      async () => {
        const decisions = await stageDecisions(sessions, 'sales', deal.id);
        const negotiation = decisions.find((d) => d.stage === 'NEGOTIATION');
        expect(negotiation, 'stageDecisions must report an entry for NEGOTIATION').toBeTruthy();
        expect(
          negotiation.allowed,
          'OWNER_SIGNOFF -> NEGOTIATION must actually be reachable, not merely note-exempt'
        ).toBe(true);
        expect(
          negotiation.requiresReason,
          'AWAITING_BUYER/QUOTE_BUYER lie strictly between OWNER_SIGNOFF and NEGOTIATION and ' +
            'neither is MANDATORY — the server must agree no reason is required'
        ).toBe(false);

        await advanceStage(sessions, 'sales', deal.id, 'NEGOTIATION', { note: null });
      }
    );

    await test.step('S9 -> S10 (-> ORDER_RECEIVED): auto, the slice-3 tail (accept -> confirm-order)', async () => {
      await acceptCustomerQuotation(sessions, ownerChain.quotationId);
      const result = await confirmOrder(sessions, ownerChain.pricingRequestId);
      expect(result.pricingRequest.status).toBe(PRICING_REQUEST_STATUS.QUOTATION_ACCEPTED);
    });

    await test.step('prove the ROUTE', async () => {
      const history = await stageHistory(sessions, 'sales', deal.id);
      expectRoute(
        history,
        ['LEAD_APPROACH', 'PRESENTATION', 'QUOTE_OWNER', 'OWNER_SIGNOFF', 'NEGOTIATION', 'ORDER_RECEIVED'],
        [
          /* LEAD_APPROACH -> PRESENTATION */ null,
          /* PRESENTATION  -> QUOTE_OWNER  */ AUTO_STAGE_MESSAGE,
          /* QUOTE_OWNER   -> OWNER_SIGNOFF*/ null,
          /* OWNER_SIGNOFF -> NEGOTIATION  */ null,
          /* NEGOTIATION   -> ORDER_RECEIVED*/ AUTO_STAGE_MESSAGE,
        ]
      );
      expectNeverEntered(history, ['SPEC_APPROVED', 'QUOTE_DESIGN_SIDE', 'AWAITING_BUYER', 'QUOTE_BUYER']);
    });
  });

  // ── Case C — BUYER_DIRECT ──────────────────────────────────────────────────────────────────
  test('Case C — BUYER_DIRECT, a contractor arrives with BOQ+spec already in hand (starts at S8)', async () => {
    test.setTimeout(300_000);

    const factory = await test.step('discover a factory this database can actually cost', async () => {
      const usable = await resolveFactory(sessions);
      expect(usable.factoryName).toBeTruthy();
      return usable;
    });

    // The six stages BUYER_DIRECT steps over, transcribed BY HAND from DealStage.ORDER (never
    // derived from the expectedStages array below) — none of them MANDATORY, which is the whole
    // reason "note: null must succeed" on a six-stage jump is the most counterintuitive result in
    // the rule set.
    const STEPPED_OVER = [
      'PRESENTATION', 'SPEC_APPROVED', 'QUOTE_DESIGN_SIDE', 'QUOTE_OWNER', 'OWNER_SIGNOFF', 'AWAITING_BUYER',
    ];

    await test.step('deal 1 (auto): the hop as a REAL auto-advance from a BUYER quotation issue', async () => {
      const deal = await createDeal(sessions, { caseName: 'caseC1 buyer-direct auto' });
      created.push(deal.id);
      await setEntryChannel(sessions, deal.id, 'BUYER_DIRECT');

      // The baseline that makes "starts at S8" falsifiable at all — without knowing this deal had
      // ZERO stage history beforehand, a one-hop assertion below would be satisfied by a deal that
      // had already moved before this test touched it.
      const baseline = await stageHistory(sessions, 'sales', deal.id);
      expect(baseline, 'a freshly created deal must have no STAGE_CHANGED history').toEqual([]);

      // No manual move needs inserting between issue and confirm-order for this route (unlike
      // Cases A/B/D), so runPricingChainToOrder's own tail applies unmodified.
      await runPricingChainToOrder(sessions, deal.id, {
        factoryName: factory.factoryName,
        recipientType: 'BUYER',
      });

      const history = await stageHistory(sessions, 'sales', deal.id);
      // The ONE hop the case is named for, index 0 -> 7: LEAD_APPROACH -> QUOTE_BUYER directly, no
      // note anywhere, driven entirely by the system (both hops carry AUTO_STAGE_MESSAGE).
      expectRoute(
        history,
        ['LEAD_APPROACH', 'QUOTE_BUYER', 'ORDER_RECEIVED'],
        [AUTO_STAGE_MESSAGE, AUTO_STAGE_MESSAGE]
      );
      // NEGOTIATION is skipped too here — a SECOND MANDATORY stage stepped over, this time by
      // confirm-order's own auto-advance — because auto-advances never consult the note rule at
      // all; only a manual POST /stage does.
      expectNeverEntered(history, [...STEPPED_OVER, 'NEGOTIATION']);
    });

    await test.step('deal 2 (manual): the SAME landing stage, this time an explicit manual move with note: null', async () => {
      const deal = await createDeal(sessions, { caseName: 'caseC2 buyer-direct manual' });
      created.push(deal.id);
      await setEntryChannel(sessions, deal.id, 'BUYER_DIRECT');

      const baseline = await stageHistory(sessions, 'sales', deal.id);
      expect(baseline, 'a freshly created deal must have no STAGE_CHANGED history').toEqual([]);

      // Six stages stepped over, none MANDATORY: DealStage.requiresJustification's forward rule
      // only asks whether a MANDATORY stage lies STRICTLY BETWEEN LEAD_APPROACH and QUOTE_BUYER,
      // and none of STEPPED_OVER is in MANDATORY = {NEGOTIATION, ORDER_RECEIVED,
      // DELIVERY_SCHEDULING, DELIVERED, CLOSED_PAID}.
      await advanceStage(sessions, 'sales', deal.id, 'QUOTE_BUYER', { note: null });

      const history = await stageHistory(sessions, 'sales', deal.id);
      // Differs from deal 1 ONLY in the hop's message: null (a human's own POST /stage call) in
      // place of AUTO_STAGE_MESSAGE. Same starting stage, same landing stage, provably different
      // mechanism.
      expectRoute(history, ['LEAD_APPROACH', 'QUOTE_BUYER'], [null]);
      expectNeverEntered(history, STEPPED_OVER);
    });
  });

  // ── Case D — everything already in stock ──────────────────────────────────────────────────
  test('Case D — everything already in stock, skips PROCUREMENT (S12-S17) only', async () => {
    test.setTimeout(300_000);

    const factory = await test.step('discover a factory this database can actually cost', async () => {
      const usable = await resolveFactory(sessions);
      expect(usable.factoryName).toBeTruthy();
      return usable;
    });

    const deal = await test.step('create the deal and label it OWNER_DIRECT (the shortest front half)', async () => {
      const d = await createDeal(sessions, { caseName: 'caseD stock owner-direct' });
      created.push(d.id);
      await setEntryChannel(sessions, d.id, 'OWNER_DIRECT');
      return d;
    });

    await test.step('S1 -> S2: manual, no note', async () => {
      await advanceStage(sessions, 'sales', deal.id, 'PRESENTATION', { note: null });
    });

    let ownerChain;
    await test.step('S2 -> S5 (-> QUOTE_OWNER): auto, via the OWNER pricing-request chain', async () => {
      ownerChain = await runPricingChainToIssue(sessions, deal.id, {
        factoryName: factory.factoryName,
        recipientType: 'OWNER',
      });
    });

    await test.step('S5 -> S6 -> S9 (-> OWNER_SIGNOFF -> NEGOTIATION): manual, no note on either (S6->S9 is Case B\'s subtle hop again)', async () => {
      await advanceStage(sessions, 'sales', deal.id, 'OWNER_SIGNOFF', { note: null });
      await advanceStage(sessions, 'sales', deal.id, 'NEGOTIATION', { note: null });
    });

    await test.step('S9 -> S10 (-> ORDER_RECEIVED): auto, the slice-3 tail', async () => {
      await acceptCustomerQuotation(sessions, ownerChain.quotationId);
      const result = await confirmOrder(sessions, ownerChain.pricingRequestId);
      expect(result.pricingRequest.status).toBe(PRICING_REQUEST_STATUS.QUOTATION_ACCEPTED);
    });

    // reserveStock is floored at ORDER_RECEIVED (TicketService:992) — this is exactly why the full
    // chain above has to run FIRST, and stock only substitutes for the import request afterwards.

    await test.step(
      "waive the deposit — MUST happen before issueImportRequest's 409 below, or that 409 would " +
        'prove the deposit gate rather than the fulfillment-status gate (see waiveDeposit\'s own doc)',
      async () => {
        await waiveDeposit(sessions, deal.id, {
          reason: 'e2e Case D: stock deal, no real DepositNoticeService document',
        });
      }
    );

    await test.step('S10 -> S18 (ORDER_RECEIVED -> DELIVERY_SCHEDULING): auto, declareStockCoverage — a THREE-index jump', async () => {
      const ticket = await readDeal(sessions, 'sales', deal.id);
      expect(
        ticket.items,
        "confirmOrder's reconciliation must have created exactly one ticket item from the OWNER PCR"
      ).toHaveLength(1);
      const item = ticket.items[0];

      const updated = await declareStockCoverage(sessions, 'sales', deal.id, [
        { itemId: item.id, qtyFromStock: item.qty },
      ]);
      expect(
        updated.summary.fulfillmentStatus,
        'full coverage on every line must set fulfillment_status FROM_STOCK'
      ).toBe('FROM_STOCK');
      expect(updated.summary.salesStage).toBe('DELIVERY_SCHEDULING');
    });

    await test.step(
      'the import path is now STRUCTURALLY closed — issueImportRequest refuses 409, for the ' +
        'fulfillment reason specifically, not the deposit one',
      async () => {
        const refused = await issueImportRequest(sessions, deal.id, { expect: 409 });
        const body = await refused.json();
        expect(
          body.message,
          'must be the fulfillmentStatus-already-set message ("คำขอนำเข้านี้ถูกออกไปแล้ว"), proving ' +
            'waiveDeposit above already cleared the OTHER possible 409 cause — this is what makes ' +
            '"skipped PROCUREMENT" a structural fact rather than an ordering coincidence'
        ).toContain('คำขอนำเข้านี้ถูกออกไปแล้ว');
      }
    );

    await test.step('S18 -> S19 (-> DELIVERED): auto, completeDelivery (STOCK source — fully covered by the declaration above)', async () => {
      const updated = await completeDelivery(sessions, deal.id);
      expect(updated.summary.salesStage).toBe('DELIVERED');
    });

    await test.step('S19 -> S20 (-> CLOSED_PAID): auto, confirmFinalPayment', async () => {
      const updated = await confirmFinalPayment(sessions, deal.id);
      expect(updated.summary.salesStage).toBe('CLOSED_PAID');
    });

    await test.step('prove the ROUTE — PROCUREMENT is the one stage this route must never touch', async () => {
      const history = await stageHistory(sessions, 'sales', deal.id);
      expectRoute(
        history,
        [
          'LEAD_APPROACH', 'PRESENTATION', 'QUOTE_OWNER', 'OWNER_SIGNOFF', 'NEGOTIATION',
          'ORDER_RECEIVED', 'DELIVERY_SCHEDULING', 'DELIVERED', 'CLOSED_PAID',
        ],
        [
          /* LEAD_APPROACH       -> PRESENTATION       */ null,
          /* PRESENTATION        -> QUOTE_OWNER        */ AUTO_STAGE_MESSAGE,
          /* QUOTE_OWNER         -> OWNER_SIGNOFF      */ null,
          /* OWNER_SIGNOFF       -> NEGOTIATION        */ null,
          /* NEGOTIATION         -> ORDER_RECEIVED     */ AUTO_STAGE_MESSAGE,
          /* ORDER_RECEIVED      -> DELIVERY_SCHEDULING*/ AUTO_STAGE_MESSAGE,
          /* DELIVERY_SCHEDULING -> DELIVERED          */ AUTO_STAGE_MESSAGE,
          /* DELIVERED           -> CLOSED_PAID        */ AUTO_STAGE_MESSAGE,
        ]
      );
      expectNeverEntered(history, [
        'SPEC_APPROVED', 'QUOTE_DESIGN_SIDE', 'AWAITING_BUYER', 'QUOTE_BUYER',
        'DEPOSIT_RECEIVED', 'PROCUREMENT',
      ]);
    });
  });
});
