// Demo seed data for the sales/CRM PricingRequest chain (Ticket/Deal ->
// PricingRequest -> FactoryQuote -> PricingCosting -> PricingDecision ->
// CustomerQuotation -> DepositNotice -> FactoryPurchaseOrder), plus
// DealActivity and sales attachments — the 100%-empty aggregate this seed
// exists to fill (see chore/mock-demo-seed-state-matrix).
//
// Every record shape below mirrors the exact fields mockApi.js's own
// create/transition handlers set — not just what the DTO projections read —
// so a seeded row that claims to already be at a later status carries every
// field that status implies (catalog snapshot on a SUBMITTED+ item,
// assignedImportId on an IMPORT_REVIEWING+ request, etc).
//
// IDs are generated at module-eval time via local counters (not hand-typed),
// so there is no risk of an accidental duplicate id inside this file. The
// exported `nextSeq` object tells mockApi.js where its own counters must
// resume so the first user-created row after boot never collides with these.

const TODAY = '2026-08-02';

function daysAgoIso(days, timePart = 'T09:00:00Z') {
  const d = new Date(`${TODAY}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() - days);
  return d.toISOString().slice(0, 10) + timePart;
}

function uuid(seed) {
  return `88888888-8888-4888-8888-${String(seed).padStart(12, '0')}`;
}

export function buildDemoSalesSeed() {
  let prSeq = 0;
  let prItemSeq = 0;
  let prEventSeq = 0;
  let fqSeq = 0;
  let fqItemSeq = 0;
  let costingSeq = 0;
  let decisionSeq = 0;
  let decisionItemSeq = 0;
  let quotationSeq = 0;
  let quotationItemSeq = 0;
  let docSeq = 0;
  let docNumberSeq = 0;
  let dealActivitySeq = 0;
  let attachSeq = 0;
  let prAttachSeq = 0;

  const pricingRequests = [];
  const factoryQuotes = [];
  const pricingCostings = [];
  const pricingDecisions = [];
  const customerQuotations = [];
  const depositNotices = [];
  const dealActivities = [];
  const attachments = [];

  const SALES1 = { id: 6, name: 'คุณสมหมาย ขายดี' };
  const SALES2 = { id: 12, name: 'คุณอรุณี ขายเก่ง' };
  const IMPORT1 = { id: 7, name: 'คุณนำเข้า พานิช' };
  const CEO = { id: 8, name: 'คุณวิชัย ธนาคาร' };

  // ── PricingRequest + items builder ──────────────────────────────────────
  function makePr({
    ticketId, recipientType, recipientLabel = null, status,
    requestedBy, assignedImport = null,
    items, revisionNo = 1, parentPricingRequestId = null,
    submittedAt = null, pickedUpAt = null, cancelledAt = null,
    createdAt, clientRequestSeed,
  }) {
    prSeq += 1;
    const id = prSeq;
    const submitted = submittedAt != null;
    const builtItems = items.map((it, idx) => {
      prItemSeq += 1;
      return {
        id: prItemSeq,
        pricingRequestId: id,
        sourceTicketItemId: it.sourceTicketItemId ?? null,
        productId: it.productId ?? null,
        variantId: null,
        brand: it.brand ?? null,
        model: it.model ?? null,
        productDescription: it.productDescription ?? null,
        color: it.color ?? null,
        texture: it.texture ?? null,
        size: it.size ?? null,
        factory: it.factory ?? null,
        requestedQty: it.requestedQty,
        requestedQtySqm: it.requestedQtySqm ?? null,
        requestedUnit: it.requestedUnit,
        requestedUnitBasis: it.requestedUnitBasis,
        quantityType: it.quantityType,
        targetDeliveryDate: null,
        deliveryLocation: null,
        specialRequirement: it.specialRequirement ?? null,
        sortOrder: idx,
        priceListVersionId: submitted ? 1 : null,
        catalogPriceId: submitted ? prItemSeq : null,
        catalogBasePrice: submitted ? (it.catalogBasePrice ?? null) : null,
        catalogCurrency: submitted ? 'THB' : null,
        catalogEffectiveDate: submitted ? '2026-01-10' : null,
        resolvedFactoryId: submitted ? prItemSeq : null,
        resolvedFactoryName: submitted ? (it.factory ?? null) : null,
        catalogProductCode: null,
        catalogBrand: submitted ? (it.brand ?? null) : null,
        catalogCollection: null,
        catalogModel: submitted ? (it.model ?? null) : null,
      };
    });
    const pr = {
      id,
      requestCode: `PCR-2026-${String(id).padStart(4, '0')}`,
      ticketId,
      recipientType,
      recipientContactId: null,
      recipientLabel,
      status,
      requestedById: requestedBy.id,
      requestedByName: requestedBy.name,
      assignedImportId: assignedImport?.id ?? null,
      assignedImportName: assignedImport?.name ?? null,
      requiredDate: null,
      customerTargetPrice: null,
      targetCurrency: null,
      note: null,
      clientRequestId: uuid(clientRequestSeed),
      revisionNo,
      parentPricingRequestId,
      submittedAt,
      pickedUpAt,
      cancelledAt,
      createdAt,
      updatedAt: createdAt,
      items: builtItems,
      events: [],
      attachments: [],
    };
    pricingRequests.push(pr);
    return pr;
  }

  function pushPrEvent(pr, actor, kind, from, to, message = null, at = pr.updatedAt) {
    prEventSeq += 1;
    pr.events.push({
      id: prEventSeq, pricingRequestId: pr.id, ticketId: pr.ticketId,
      actorId: actor.id, actorName: actor.name, eventKind: kind,
      fromStatus: from, toStatus: to, message, metadata: null, createdAt: at,
    });
    pr.updatedAt = at;
  }

  function addPrAttachment(pr, { fileName, mimeType = 'application/pdf', fileSize = 102400, includeInFactoryEmail = false, uploadedBy, at }) {
    prAttachSeq += 1;
    pr.attachments.unshift({
      id: prAttachSeq, pricingRequestId: pr.id, fileName, mimeType, fileSize,
      includeInFactoryEmail, uploadedBy: uploadedBy.id, uploadedAt: at,
    });
  }

  // ── FactoryQuote builder ────────────────────────────────────────────────
  function makeFactoryQuote(pr, {
    status, dispatchStatus = null, factoryName, lines, createdAt,
    negotiationNote = null, note = null, revisionNo = 1, parentFactoryQuoteId = null,
    current = true, response = false,
  }) {
    fqSeq += 1;
    const id = fqSeq;
    const rootFactoryQuoteId = parentFactoryQuoteId
      ? factoryQuotes.find((q) => q.id === parentFactoryQuoteId).rootFactoryQuoteId
      : id;
    const items = lines.map((line) => {
      fqItemSeq += 1;
      const base = {
        id: fqItemSeq, factoryQuoteId: id, pricingRequestItemId: line.prItemId,
        quotedQuantity: line.qty, quotedUnit: line.unitBasis, unitBasis: line.unitBasis,
        sortOrder: 0,
      };
      if (!response) return { ...base, rawUnitPrice: null, currency: null };
      return {
        ...base,
        rawUnitPrice: line.rawUnitPrice, currency: line.currency ?? 'THB',
        minimumOrderQuantity: line.moq ?? null,
        sqmPerUnit: line.sqmPerUnit ?? null, piecesPerBox: line.piecesPerBox ?? null,
        linearMPerUnit: null,
      };
    });
    const quote = {
      id, quoteCode: `FQ-2026-${String(id).padStart(4, '0')}`,
      pricingRequestId: pr.id, factoryId: null, factoryName,
      status, emailTo: null, emailSubject: `Pricing request ${pr.requestCode}`,
      emailBody: `รายการขอราคาโรงงาน ${factoryName} สำหรับ ${pr.requestCode}`,
      emailSentAt: response || status !== 'DRAFT' ? createdAt : null,
      sentBy: response || status !== 'DRAFT' ? IMPORT1.id : null,
      supplierQuoteRef: response ? `REF-${factoryName.slice(0, 3).toUpperCase()}-${id}` : null,
      defaultCurrency: 'THB',
      paymentTerms: response ? '30 วัน' : null,
      leadTimeText: response ? '45 วัน' : null,
      note, negotiationNote,
      requestedAt: status !== 'DRAFT' ? createdAt : null,
      receivedAt: response ? createdAt : null,
      rootFactoryQuoteId, parentFactoryQuoteId, revisionNo,
      revisionReason: parentFactoryQuoteId ? 'โรงงานปรับราคาใหม่หลังต่อรอง' : null,
      current, createdAt, updatedAt: createdAt,
      attachments: [], items,
      dispatchStatus, dispatchAttemptCount: dispatchStatus ? 1 : 0,
      dispatchFailureMessage: null, dispatchNextAttemptAt: null,
    };
    factoryQuotes.push(quote);
    return quote;
  }

  // ── PricingCosting builder ──────────────────────────────────────────────
  function makeCosting(pr, { status, lines, createdAt, submittedAt = null }) {
    costingSeq += 1;
    const id = costingSeq;
    const items = status === 'DRAFT' ? [] : lines.map((line) => ({
      pricingRequestItemId: line.prItemId,
      factoryQuoteId: line.fqId, factoryQuoteItemId: line.fqItemId, factoryQuoteRevisionNo: 1,
      factoryName: line.factoryName,
      rawUnitPrice: line.rawUnitPrice, rawCurrency: 'THB', rawUnit: line.unitBasis,
      unitBasis: line.unitBasis, requestedQuantity: line.qty, requestedUnit: line.unitLabel,
      requestedUnitBasis: line.unitBasis, normalizedQuantityPieces: line.qty,
      sqmPerUnit: line.sqmPerUnit ?? null, piecesPerBox: null, linearMPerUnit: null,
      goodsCostThb: line.rawUnitPrice, landedCostPerUnitThb: line.landedCostPerUnitThb,
      totalLandedCostThb: line.landedCostPerUnitThb * line.qty,
    }));
    const totalLandedCostThb = status === 'DRAFT' ? null
      : items.reduce((sum, it) => sum + it.totalLandedCostThb, 0);
    const costing = {
      id, costingCode: `PCO-2026-${String(id).padStart(4, '0')}`,
      pricingRequestId: pr.id, versionNo: id, status, stale: false, staleReason: null,
      note: null, createdBy: IMPORT1.id, createdAt, updatedAt: createdAt,
      calculatedAt: status === 'DRAFT' ? null : createdAt,
      submittedBy: submittedAt ? IMPORT1.id : null, submittedAt,
      totalLandedCostThb, items,
    };
    pricingCostings.push(costing);
    return costing;
  }

  // ── PricingDecision builder ─────────────────────────────────────────────
  function makeDecision(pr, costing, { status, marginPct = 0.2, createdAt, approvedAt = null, returnedAt = null, returnReason = null }) {
    decisionSeq += 1;
    const id = decisionSeq;
    const items = costing.items.map((ci) => {
      decisionItemSeq += 1;
      const prItem = pr.items.find((i) => i.id === ci.pricingRequestItemId);
      const perUnit = ci.landedCostPerUnitThb;
      const proposedSelling = Math.round(perUnit * (1 + marginPct));
      const approved = status === 'APPROVED';
      return {
        id: decisionItemSeq, pricingDecisionId: id,
        pricingRequestItemId: ci.pricingRequestItemId, pricingCostingItemId: ci.pricingRequestItemId,
        brand: prItem?.brand ?? null, model: prItem?.model ?? null,
        productDescription: prItem?.productDescription ?? null,
        factoryName: ci.factoryName ?? null, requestedUnitBasis: ci.requestedUnitBasis,
        requestedQuantity: ci.requestedQuantity, normalizedQuantityPieces: ci.normalizedQuantityPieces,
        frozenLandedCostPerPieceThb: perUnit, frozenLandedCostPerRequestedUnitThb: perUnit,
        currency: 'THB', proposedMarginPct: marginPct, approvedMarginPct: approved ? marginPct : null,
        proposedSellingPricePerRequestedUnit: proposedSelling,
        approvedSellingPricePerRequestedUnit: approved ? proposedSelling : null,
        discountCeilingPct: approved ? 0.05 : null,
        minimumSellingPricePerRequestedUnit: approved ? Math.round(perUnit * 1.1) : null,
        decisionNote: null, createdAt, updatedAt: createdAt,
      };
    });
    const decision = {
      id, decisionCode: `PCD-2026-${String(id).padStart(4, '0')}`,
      pricingRequestId: pr.id, pricingCostingId: costing.id, decisionVersionNo: 1,
      status, defaultMarginPct: marginPct, currency: 'THB', fxRateUsed: 1, fxSource: 'THB',
      fxEffectiveDate: createdAt.slice(0, 10), ceoNote: null, returnReason,
      approveClientRequestId: approvedAt ? uuid(9000 + id) : null,
      createdBy: CEO.id, createdAt, updatedAt: approvedAt ?? returnedAt ?? createdAt,
      approvedBy: approvedAt ? CEO.id : null, approvedAt,
      returnedAt, items,
    };
    pricingDecisions.push(decision);
    return decision;
  }

  // ── CustomerQuotation builder ───────────────────────────────────────────
  function makeQuotation(pr, decision, {
    docStatus, createdAt, issuedAt = null, sentAt = null, acceptedAt = null, rejectedAt = null,
    quotationRevisionNo = 1, parentQuotationId = null, discountPerItem = 0, outcomeNote = null,
  }) {
    quotationSeq += 1;
    const id = quotationSeq;
    const items = decision.items
      .filter((di) => di.approvedSellingPricePerRequestedUnit != null)
      .map((di, idx) => {
        quotationItemSeq += 1;
        const approvedUnitPrice = di.approvedSellingPricePerRequestedUnit;
        const finalUnitPrice = approvedUnitPrice - discountPerItem;
        const lineSubtotal = Math.round(finalUnitPrice * di.requestedQuantity * 100) / 100;
        const vat = Math.round(lineSubtotal * 0.07 * 100) / 100;
        return {
          id: quotationItemSeq, seq: idx + 1,
          pricingRequestItemId: di.pricingRequestItemId, pricingDecisionItemId: di.id,
          description: di.productDescription || [di.brand, di.model].filter(Boolean).join(' '),
          itemNotes: null, requestedUnitBasis: di.requestedUnitBasis,
          requestedQuantity: di.requestedQuantity, approvedUnitPrice,
          salesDiscount: discountPerItem, finalUnitPrice,
          minimumSellingPricePerRequestedUnit: di.minimumSellingPricePerRequestedUnit,
          lineSubtotal, vat, lineTotal: Math.round((lineSubtotal + vat) * 100) / 100,
        };
      });
    const subtotalAmount = items.reduce((s, i) => s + i.lineSubtotal, 0);
    const vatAmount = items.reduce((s, i) => s + i.vat, 0);
    const quotation = {
      id, number: `QT-2026-${String(id).padStart(4, '0')}`, ticketId: pr.ticketId,
      pricingRequestId: pr.id, pricingDecisionId: decision.id,
      recipientType: pr.recipientType, recipientLabel: pr.recipientLabel,
      docStatus, quotationVersion: quotationRevisionNo, quotationRevisionNo, parentQuotationId,
      issuedById: SALES1.id, issuedByName: SALES1.name, issuedAt,
      subtotalAmount, vatAmount, grandTotal: Math.round((subtotalAmount + vatAmount) * 100) / 100,
      currency: 'THB', paymentTerms: 'ชำระเงินสด 100% ก่อนส่งมอบ', leadTime: '30-45 วัน',
      deliveryTerms: 'ส่งมอบหน้างาน', validityDate: '2026-09-30', customerNotes: null,
      sentAt, acceptedAt, rejectedAt, createdAt, clientRequestId: uuid(9500 + id),
      issueClientRequestId: issuedAt ? uuid(9600 + id) : null,
      customerName: null, customerAddress: null, customerTaxId: null, customerPhone: null,
      projectName: null,
      outcomeNote, outcomeRecordedAt: outcomeNote ? (rejectedAt ?? acceptedAt ?? createdAt) : null,
      outcomeClientRequestId: outcomeNote ? uuid(9700 + id) : null,
      items,
    };
    customerQuotations.push(quotation);
    return quotation;
  }

  // ── DepositNotice builder ───────────────────────────────────────────────
  function makeDepositNotice(ticket, quotation, { status, createdAt, issueDate = null, version = 1 }) {
    docSeq += 1;
    const id = docSeq;
    const items = quotation.items.map((it, idx) => ({
      seq: idx + 1, description: it.description, qty: it.requestedQuantity,
      unit: it.requestedUnitBasis === 'PER_SQM' ? 'ตร.ม.' : it.requestedUnitBasis === 'PER_BOX' ? 'กล่อง' : it.requestedUnitBasis === 'PER_LINEAR_M' ? 'เมตร' : 'แผ่น',
      unitPrice: it.approvedUnitPrice,
      discountLabel: it.salesDiscount > 0 ? `ส่วนลด ${it.salesDiscount} ต่อหน่วย` : null,
      netUnitPrice: it.finalUnitPrice,
    }));
    const subtotal = items.reduce((s, it) => s + it.netUnitPrice * it.qty, 0);
    const depositPercent = 0.5;
    const depositAmount = Math.round(subtotal * depositPercent * 100) / 100;
    const vatAmount = Math.round(depositAmount * 0.07 * 100) / 100;
    const doc = {
      id, ticketId: ticket.id, docType: 'DEPOSIT_NOTICE', version, docNumber: null,
      issueDate, status, customerName: ticket.customerName, customerTaxId: null,
      customerAddress: null, projectName: null, reference: quotation.number,
      depositPercent, vatPercent: 0.07,
      notes: ['ราคารวมค่าขนส่งถึงชั้น 1 ของหน่วยงานในเขต กทม. แต่ไม่รวมค่าตัด/ติดตั้ง'],
      items, issuedByName: status === 'ISSUED' ? SALES1.name : null,
      preparerName: 'จินตนา หาญมนตรี', hasPdf: status === 'ISSUED', hasXlsx: status === 'ISSUED',
      createdAt, updatedAt: createdAt,
      subtotal, depositAmount, vatAmount,
      totalPayable: Math.round((depositAmount + vatAmount) * 100) / 100,
    };
    if (status === 'ISSUED') {
      docNumberSeq += 1;
      doc.docNumber = `GLRD26${String(docNumberSeq).padStart(3, '0')}`;
    }
    depositNotices.push(doc);
    return doc;
  }

  function addDealActivity(ticketId, { kind, activityDate, note, createdBy, at }) {
    dealActivitySeq += 1;
    dealActivities.push({
      id: dealActivitySeq, ticketId, activityDate, kind, note,
      createdById: createdBy.id, createdByName: createdBy.name, createdAt: at,
    });
  }

  function addAttachment(ticketId, { fileName, attachType, mimeType = 'application/pdf', fileSize = 51200, uploadedBy, at }) {
    attachSeq += 1;
    attachments.push({
      id: attachSeq, ticketId, quotationId: null, fileName, attachType, mimeType, fileSize,
      uploadedBy: uploadedBy.id, uploadedAt: at,
    });
  }

  // ══════════════════════════════════════════════════════════════════════
  // New tickets: rep2's two deals (ownership/DRAFT-visibility demo) + a 3rd
  // deal that hosts the extra downstream-status coverage this PR chain needs
  // (see the report for exactly which statuses land where).
  // ══════════════════════════════════════════════════════════════════════
  const tickets = [
    {
      id: 16, code: 'PR-2026-0016', type: 'PRICE_REQUEST',
      title: 'โรงแรมริเวอร์ไซด์ กรุงเทพ',
      status: 'draft', priority: 'NORMAL',
      createdById: SALES2.id, createdByName: SALES2.name,
      assignedToId: null, assignedToName: null,
      customerName: 'โรงแรมริเวอร์ไซด์ กรุงเทพ',
      note: 'ลูกค้าใหม่ ติดต่อผ่านงานแสดงสินค้า',
      createdAt: daysAgoIso(7, '').slice(0, 10), updatedAt: daysAgoIso(5, '').slice(0, 10), closedAt: null,
      salesStage: 'LEAD_APPROACH', lostReason: null, lostAt: null,
      stageUpdatedAt: daysAgoIso(5),
      lifecycle: 'ACTIVE', tenderRequirement: 'UNKNOWN', depositPolicy: 'REQUIRED',
      depositPolicyReason: null, entryChannel: 'DESIGNER_LED',
      paymentStatus: null, fulfillmentStatus: null,
      quotations: [], quotation: null,
      items: [
        { id: 18, ticketId: 16, brand: 'SCG', model: 'Crystal White', color: 'ขาวมุก', texture: 'มัน', size: '60x120 ซม.', factory: 'SCG Ceramics', qty: 150, proposedPrice: null, approvedPrice: null, currency: 'THB', sortOrder: 0, qtyDelivered: 0, qtyFromStock: 0, stockNote: null },
      ],
      events: [
        { id: 92, ticketId: 16, actorId: SALES2.id, actorName: SALES2.name, kind: 'CREATED', fromStatus: null, toStatus: 'draft', message: null, createdAt: daysAgoIso(7) },
      ],
    },
    {
      id: 17, code: 'PR-2026-0017', type: 'PRICE_REQUEST',
      title: 'คอนโดมิเนียม ลุมพินี สาทร',
      status: 'submitted', priority: 'NORMAL',
      createdById: SALES2.id, createdByName: SALES2.name,
      assignedToId: null, assignedToName: null,
      customerName: 'บริษัท ลุมพินี พร็อพเพอร์ตี้ จำกัด',
      note: null,
      createdAt: daysAgoIso(18, '').slice(0, 10), updatedAt: daysAgoIso(1, '').slice(0, 10), closedAt: null,
      salesStage: 'QUOTE_DESIGN_SIDE', lostReason: null, lostAt: null,
      stageUpdatedAt: daysAgoIso(3),
      lifecycle: 'ACTIVE', tenderRequirement: 'UNKNOWN', depositPolicy: 'REQUIRED',
      depositPolicyReason: null, entryChannel: 'DESIGNER_LED',
      paymentStatus: null, fulfillmentStatus: null,
      quotations: [], quotation: null,
      items: [
        { id: 19, ticketId: 17, brand: 'Cotto', model: 'Stone Series', color: 'เทาเข้ม', texture: 'หยาบ', size: '60x60 ซม.', factory: 'Cotto Industry', qty: 400, proposedPrice: null, approvedPrice: null, currency: 'THB', sortOrder: 0, qtyDelivered: 0, qtyFromStock: 0, stockNote: null },
      ],
      events: [
        { id: 93, ticketId: 17, actorId: SALES2.id, actorName: SALES2.name, kind: 'CREATED', fromStatus: null, toStatus: 'draft', message: null, createdAt: daysAgoIso(18) },
        { id: 94, ticketId: 17, actorId: SALES2.id, actorName: SALES2.name, kind: 'SUBMITTED', fromStatus: 'draft', toStatus: 'submitted', message: null, createdAt: daysAgoIso(1) },
      ],
    },
    {
      id: 18, code: 'PR-2026-0018', type: 'PRICE_REQUEST',
      title: 'ศูนย์การค้า Fashion Island',
      status: 'submitted', priority: 'HIGH',
      createdById: SALES1.id, createdByName: SALES1.name,
      assignedToId: IMPORT1.id, assignedToName: IMPORT1.name,
      customerName: 'บริษัท แฟชั่นไอส์แลนด์ จำกัด',
      note: null,
      createdAt: '2026-06-25', updatedAt: daysAgoIso(2, '').slice(0, 10), closedAt: null,
      salesStage: 'QUOTE_BUYER', lostReason: null, lostAt: null,
      stageUpdatedAt: daysAgoIso(6),
      lifecycle: 'ACTIVE', tenderRequirement: 'UNKNOWN', depositPolicy: 'REQUIRED',
      depositPolicyReason: null, entryChannel: 'DESIGNER_LED',
      paymentStatus: null, fulfillmentStatus: null,
      quotations: [], quotation: null,
      items: [
        { id: 20, ticketId: 18, brand: 'SCG', model: 'Elegance Series', color: 'ขาวนวล', texture: 'ด้าน', size: '60x60 ซม.', factory: 'SCG Ceramics', qty: 600, proposedPrice: null, approvedPrice: null, currency: 'THB', sortOrder: 0, qtyDelivered: 0, qtyFromStock: 0, stockNote: null },
        { id: 21, ticketId: 18, brand: 'Cotto', model: 'Metro Square', color: 'ครีม', texture: 'ด้าน', size: '30x30 ซม.', factory: 'Cotto Industry', qty: 900, proposedPrice: null, approvedPrice: null, currency: 'THB', sortOrder: 1, qtyDelivered: 0, qtyFromStock: 0, stockNote: null },
        { id: 22, ticketId: 18, brand: 'Duragres', model: 'Granite Plus', color: 'เทากลาง', texture: 'หยาบกึ่งมัน', size: '60x60 ซม.', factory: 'Duragres Thailand', qty: 300, proposedPrice: null, approvedPrice: null, currency: 'THB', sortOrder: 2, qtyDelivered: 0, qtyFromStock: 0, stockNote: null },
        { id: 23, ticketId: 18, brand: 'Panaria', model: 'Trilogy', color: 'Ivory', texture: 'Lappato', size: '60x120 cm', factory: 'Panaria SpA', qty: 120, proposedPrice: null, approvedPrice: null, currency: 'THB', sortOrder: 3, qtyDelivered: 0, qtyFromStock: 0, stockNote: null },
      ],
      events: [
        { id: 95, ticketId: 18, actorId: SALES1.id, actorName: SALES1.name, kind: 'CREATED', fromStatus: null, toStatus: 'draft', message: null, createdAt: '2026-06-25T09:00:00Z' },
        { id: 96, ticketId: 18, actorId: SALES1.id, actorName: SALES1.name, kind: 'SUBMITTED', fromStatus: 'draft', toStatus: 'submitted', message: null, createdAt: '2026-06-25T09:30:00Z' },
      ],
    },
  ];

  // ══════════════════════════════════════════════════════════════════════
  // PricingRequest state matrix — hosted mostly on the existing 15 tickets,
  // plus the 3 new ones above. See the handoff report for the full status ->
  // ticket map.
  // ══════════════════════════════════════════════════════════════════════

  // PR1 — ticket 1 — DRAFT
  const pr1 = makePr({
    ticketId: 1, recipientType: 'OWNER', recipientLabel: 'เจ้าของโครงการ Central',
    status: 'DRAFT', requestedBy: SALES1, createdAt: daysAgoIso(3), clientRequestSeed: 1,
    items: [{ sourceTicketItemId: 1, brand: 'SCG', model: 'Premium Matte', color: 'ขาว', texture: 'ด้าน', size: '60x60 ซม.', factory: 'SCG Ceramics', requestedQty: 200, requestedUnit: 'แผ่น', requestedUnitBasis: 'PER_PIECE', quantityType: 'ESTIMATE' }],
  });
  pushPrEvent(pr1, SALES1, 'PRICING_REQUEST_CREATED', null, 'DRAFT', null, daysAgoIso(3));

  // PR2 — ticket 1 — SUBMITTED
  const pr2 = makePr({
    ticketId: 1, recipientType: 'DESIGNER', recipientLabel: 'ผู้ออกแบบ Premium Design Group',
    status: 'SUBMITTED', requestedBy: SALES1, submittedAt: daysAgoIso(9),
    createdAt: daysAgoIso(10), clientRequestSeed: 2,
    items: [{ sourceTicketItemId: 2, brand: 'SCG', model: 'Premium Matte', color: 'ดำ', texture: 'ด้าน', size: '60x60 ซม.', factory: 'SCG Ceramics', requestedQty: 150, requestedUnit: 'แผ่น', requestedUnitBasis: 'PER_PIECE', quantityType: 'CONFIRMED', catalogBasePrice: 130 }],
  });
  pushPrEvent(pr2, SALES1, 'PRICING_REQUEST_CREATED', null, 'DRAFT', null, daysAgoIso(10));
  pushPrEvent(pr2, SALES1, 'PRICING_REQUEST_SUBMITTED', 'DRAFT', 'SUBMITTED', null, daysAgoIso(9));
  addPrAttachment(pr2, { fileName: 'boq-central-premium.xlsx', mimeType: 'application/vnd.ms-excel', includeInFactoryEmail: false, uploadedBy: SALES1, at: daysAgoIso(9) });

  // PR3 — ticket 2 — IMPORT_REVIEWING, multi-item, BUYER
  const pr3 = makePr({
    ticketId: 2, recipientType: 'BUYER', recipientLabel: 'ผู้รับเหมา ABC',
    status: 'IMPORT_REVIEWING', requestedBy: SALES1, assignedImport: IMPORT1,
    submittedAt: daysAgoIso(14), pickedUpAt: daysAgoIso(13), createdAt: daysAgoIso(15), clientRequestSeed: 3,
    items: [
      { sourceTicketItemId: 4, brand: 'Cotto', model: 'Floral Gloss', color: 'ลายดอกไม้', texture: 'มัน', size: '30x30 ซม.', factory: 'Cotto Industry', requestedQty: 500, requestedUnit: 'แผ่น', requestedUnitBasis: 'PER_PIECE', quantityType: 'ESTIMATE', catalogBasePrice: 75 },
      { sourceTicketItemId: null, productDescription: 'กระเบื้องกันลื่นสำหรับระเบียง ผิวหยาบ 30x30 ซม.', brand: null, model: null, factory: 'Cotto Industry', requestedQty: 80, requestedUnit: 'แผ่น', requestedUnitBasis: 'PER_PIECE', quantityType: 'REFERENCE', catalogBasePrice: 70 },
    ],
  });
  pushPrEvent(pr3, SALES1, 'PRICING_REQUEST_CREATED', null, 'DRAFT', null, daysAgoIso(15));
  pushPrEvent(pr3, SALES1, 'PRICING_REQUEST_SUBMITTED', 'DRAFT', 'SUBMITTED', null, daysAgoIso(14));
  pushPrEvent(pr3, IMPORT1, 'PRICING_REQUEST_PICKED_UP', 'SUBMITTED', 'IMPORT_REVIEWING', null, daysAgoIso(13));

  // PR4 — ticket 2 — AWAITING_FACTORY_RESPONSE (FactoryQuote REQUESTED)
  const pr4 = makePr({
    ticketId: 2, recipientType: 'DESIGNER', recipientLabel: 'ผู้ออกแบบ ABC',
    status: 'AWAITING_FACTORY_RESPONSE', requestedBy: SALES1, assignedImport: IMPORT1,
    submittedAt: daysAgoIso(12), pickedUpAt: daysAgoIso(11), createdAt: daysAgoIso(13), clientRequestSeed: 4,
    items: [{ sourceTicketItemId: 4, brand: 'Cotto', model: 'Floral Gloss', color: 'ลายดอกไม้', texture: 'มัน', size: '30x30 ซม.', factory: 'Cotto Industry', requestedQty: 500, requestedUnit: 'แผ่น', requestedUnitBasis: 'PER_PIECE', quantityType: 'ESTIMATE', catalogBasePrice: 75 }],
  });
  pushPrEvent(pr4, SALES1, 'PRICING_REQUEST_CREATED', null, 'DRAFT', null, daysAgoIso(13));
  pushPrEvent(pr4, SALES1, 'PRICING_REQUEST_SUBMITTED', 'DRAFT', 'SUBMITTED', null, daysAgoIso(12));
  pushPrEvent(pr4, IMPORT1, 'PRICING_REQUEST_PICKED_UP', 'SUBMITTED', 'IMPORT_REVIEWING', null, daysAgoIso(11));
  pushPrEvent(pr4, IMPORT1, 'FACTORY_EMAIL_SENT', 'IMPORT_REVIEWING', 'AWAITING_FACTORY_RESPONSE', null, daysAgoIso(10));
  makeFactoryQuote(pr4, {
    status: 'REQUESTED', dispatchStatus: 'SENT', factoryName: 'Cotto Industry',
    lines: [{ prItemId: pr4.items[0].id, qty: 500, unitBasis: 'PER_PIECE' }],
    createdAt: daysAgoIso(10),
  });

  // PR5 — ticket 3 — AWAITING_FACTORY_RESPONSE (FactoryQuote RESPONSE_RECEIVED, Costing DRAFT).
  // V139 merged COSTING_IN_PROGRESS into AWAITING_FACTORY_RESPONSE and dropped it from the DB
  // constraint, so the seed moves with it — exactly what the migration does to a live row. The
  // demo state this row exists to cover (a costing in DRAFT) is unchanged; only the status the
  // request carries while that costing is open has.
  const pr5 = makePr({
    ticketId: 3, recipientType: 'OWNER', recipientLabel: 'เจ้าของโครงการ XYZ',
    status: 'AWAITING_FACTORY_RESPONSE', requestedBy: SALES1, assignedImport: IMPORT1,
    submittedAt: daysAgoIso(20), pickedUpAt: daysAgoIso(19), createdAt: daysAgoIso(21), clientRequestSeed: 5,
    items: [{ sourceTicketItemId: 5, brand: 'Duragres', model: 'Wood Texture', color: 'ขาว', texture: 'ลายไม้', size: '20x100 ซม.', factory: 'Duragres Thailand', requestedQty: 50, requestedUnit: 'แผ่น', requestedUnitBasis: 'PER_PIECE', quantityType: 'ESTIMATE', catalogBasePrice: 200 }],
  });
  pushPrEvent(pr5, SALES1, 'PRICING_REQUEST_CREATED', null, 'DRAFT', null, daysAgoIso(21));
  pushPrEvent(pr5, SALES1, 'PRICING_REQUEST_SUBMITTED', 'DRAFT', 'SUBMITTED', null, daysAgoIso(20));
  pushPrEvent(pr5, IMPORT1, 'PRICING_REQUEST_PICKED_UP', 'SUBMITTED', 'IMPORT_REVIEWING', null, daysAgoIso(19));
  pushPrEvent(pr5, IMPORT1, 'FACTORY_RESPONSE_RECEIVED', 'IMPORT_REVIEWING', 'COSTING_IN_PROGRESS', null, daysAgoIso(17));
  const fq5 = makeFactoryQuote(pr5, {
    status: 'RESPONSE_RECEIVED', response: true, factoryName: 'Duragres Thailand',
    lines: [{ prItemId: pr5.items[0].id, qty: 50, unitBasis: 'PER_PIECE', rawUnitPrice: 200, sqmPerUnit: 0.2 }],
    createdAt: daysAgoIso(18),
  });
  fq5.receivedAt = daysAgoIso(17);
  makeCosting(pr5, { status: 'DRAFT', lines: [], createdAt: daysAgoIso(16) });

  // PR6 — ticket 3 — READY_FOR_CEO_REVIEW (Costing SUBMITTED)
  const pr6 = makePr({
    ticketId: 3, recipientType: 'BUYER', recipientLabel: 'ผู้รับเหมา XYZ',
    status: 'READY_FOR_CEO_REVIEW', requestedBy: SALES1, assignedImport: IMPORT1,
    submittedAt: daysAgoIso(30), pickedUpAt: daysAgoIso(29), createdAt: daysAgoIso(31), clientRequestSeed: 6,
    items: [{ sourceTicketItemId: 6, brand: 'Duragres', model: 'Cement Series', color: 'เทา', texture: 'ด้าน', size: '60x60 ซม.', factory: 'Duragres Thailand', requestedQty: 50, requestedUnit: 'แผ่น', requestedUnitBasis: 'PER_PIECE', quantityType: 'CONFIRMED', catalogBasePrice: 120 }],
  });
  pushPrEvent(pr6, SALES1, 'PRICING_REQUEST_CREATED', null, 'DRAFT', null, daysAgoIso(31));
  pushPrEvent(pr6, SALES1, 'PRICING_REQUEST_SUBMITTED', 'DRAFT', 'SUBMITTED', null, daysAgoIso(30));
  pushPrEvent(pr6, IMPORT1, 'PRICING_REQUEST_PICKED_UP', 'SUBMITTED', 'IMPORT_REVIEWING', null, daysAgoIso(29));
  pushPrEvent(pr6, IMPORT1, 'PRICING_COSTING_SUBMITTED', 'COSTING_IN_PROGRESS', 'READY_FOR_CEO_REVIEW', null, daysAgoIso(24));
  const fq6 = makeFactoryQuote(pr6, {
    status: 'READY_FOR_COSTING', response: true, factoryName: 'Duragres Thailand',
    lines: [{ prItemId: pr6.items[0].id, qty: 50, unitBasis: 'PER_PIECE', rawUnitPrice: 120, sqmPerUnit: 0.36 }],
    createdAt: daysAgoIso(27),
  });
  makeCosting(pr6, {
    status: 'SUBMITTED', submittedAt: daysAgoIso(24), createdAt: daysAgoIso(26),
    lines: [{ prItemId: pr6.items[0].id, fqId: fq6.id, fqItemId: fq6.items[0].id, factoryName: 'Duragres Thailand', rawUnitPrice: 120, unitBasis: 'PER_PIECE', unitLabel: 'แผ่น', qty: 50, landedCostPerUnitThb: 138, sqmPerUnit: 0.36 }],
  });

  // PR7 — ticket 4 — CEO_REVIEWING (Decision DRAFT)
  const pr7 = makePr({
    ticketId: 4, recipientType: 'DESIGNER', recipientLabel: 'ผู้ออกแบบร้านค้าปลีก',
    status: 'CEO_REVIEWING', requestedBy: SALES1, assignedImport: IMPORT1,
    submittedAt: daysAgoIso(35), pickedUpAt: daysAgoIso(34), createdAt: daysAgoIso(36), clientRequestSeed: 7,
    items: [{ sourceTicketItemId: 7, brand: 'Cotto', model: 'Classic Gloss', color: 'ครีม', texture: 'มัน', size: '30x30 ซม.', factory: 'Cotto Industry', requestedQty: 1000, requestedUnit: 'แผ่น', requestedUnitBasis: 'PER_PIECE', quantityType: 'CONFIRMED', catalogBasePrice: 60 }],
  });
  pushPrEvent(pr7, SALES1, 'PRICING_REQUEST_CREATED', null, 'DRAFT', null, daysAgoIso(36));
  pushPrEvent(pr7, SALES1, 'PRICING_REQUEST_SUBMITTED', 'DRAFT', 'SUBMITTED', null, daysAgoIso(35));
  pushPrEvent(pr7, IMPORT1, 'PRICING_REQUEST_PICKED_UP', 'SUBMITTED', 'IMPORT_REVIEWING', null, daysAgoIso(34));
  pushPrEvent(pr7, CEO, 'PRICING_DECISION_STARTED', 'READY_FOR_CEO_REVIEW', 'CEO_REVIEWING', null, daysAgoIso(28));
  const fq7 = makeFactoryQuote(pr7, {
    status: 'READY_FOR_COSTING', response: true, factoryName: 'Cotto Industry',
    lines: [{ prItemId: pr7.items[0].id, qty: 1000, unitBasis: 'PER_PIECE', rawUnitPrice: 60, sqmPerUnit: 0.09 }],
    createdAt: daysAgoIso(31),
  });
  const costing7 = makeCosting(pr7, {
    status: 'SUBMITTED', submittedAt: daysAgoIso(29), createdAt: daysAgoIso(30),
    lines: [{ prItemId: pr7.items[0].id, fqId: fq7.id, fqItemId: fq7.items[0].id, factoryName: 'Cotto Industry', rawUnitPrice: 60, unitBasis: 'PER_PIECE', unitLabel: 'แผ่น', qty: 1000, landedCostPerUnitThb: 69, sqmPerUnit: 0.09 }],
  });
  makeDecision(pr7, costing7, { status: 'DRAFT', createdAt: daysAgoIso(28) });

  // PR8 — ticket 4 — APPROVED_FOR_QUOTATION (Decision APPROVED)
  const pr8 = makePr({
    ticketId: 4, recipientType: 'OWNER', recipientLabel: 'เจ้าของร้านค้าปลีก',
    status: 'APPROVED_FOR_QUOTATION', requestedBy: SALES1, assignedImport: IMPORT1,
    submittedAt: daysAgoIso(45), pickedUpAt: daysAgoIso(44), createdAt: daysAgoIso(46), clientRequestSeed: 8,
    items: [{ sourceTicketItemId: 7, brand: 'Cotto', model: 'Classic Gloss', color: 'ครีม', texture: 'มัน', size: '30x30 ซม.', factory: 'Cotto Industry', requestedQty: 1000, requestedUnit: 'แผ่น', requestedUnitBasis: 'PER_PIECE', quantityType: 'CONFIRMED', catalogBasePrice: 60 }],
  });
  pushPrEvent(pr8, SALES1, 'PRICING_REQUEST_CREATED', null, 'DRAFT', null, daysAgoIso(46));
  pushPrEvent(pr8, SALES1, 'PRICING_REQUEST_SUBMITTED', 'DRAFT', 'SUBMITTED', null, daysAgoIso(45));
  pushPrEvent(pr8, IMPORT1, 'PRICING_REQUEST_PICKED_UP', 'SUBMITTED', 'IMPORT_REVIEWING', null, daysAgoIso(44));
  pushPrEvent(pr8, CEO, 'PRICING_DECISION_APPROVED', 'CEO_REVIEWING', 'APPROVED_FOR_QUOTATION', null, daysAgoIso(38));
  const fq8 = makeFactoryQuote(pr8, {
    status: 'READY_FOR_COSTING', response: true, factoryName: 'Cotto Industry',
    lines: [{ prItemId: pr8.items[0].id, qty: 1000, unitBasis: 'PER_PIECE', rawUnitPrice: 60, sqmPerUnit: 0.09 }],
    createdAt: daysAgoIso(41),
  });
  const costing8 = makeCosting(pr8, {
    status: 'SUBMITTED', submittedAt: daysAgoIso(39), createdAt: daysAgoIso(40),
    lines: [{ prItemId: pr8.items[0].id, fqId: fq8.id, fqItemId: fq8.items[0].id, factoryName: 'Cotto Industry', rawUnitPrice: 60, unitBasis: 'PER_PIECE', unitLabel: 'แผ่น', qty: 1000, landedCostPerUnitThb: 69, sqmPerUnit: 0.09 }],
  });
  makeDecision(pr8, costing8, { status: 'APPROVED', createdAt: daysAgoIso(38), approvedAt: daysAgoIso(38) });

  // PR9 — ticket 5 — COSTING_REVISION_REQUIRED (Decision RETURNED)
  const pr9 = makePr({
    ticketId: 5, recipientType: 'BUYER', recipientLabel: 'ผู้จัดงาน Event Organizer',
    status: 'COSTING_REVISION_REQUIRED', requestedBy: SALES1, assignedImport: IMPORT1,
    submittedAt: daysAgoIso(24), pickedUpAt: daysAgoIso(23), createdAt: daysAgoIso(25), clientRequestSeed: 9,
    items: [{ sourceTicketItemId: 8, brand: 'SCG', model: 'Granite Black', color: 'ดำ', texture: 'หยาบ', size: '60x60 ซม.', factory: 'SCG Ceramics', requestedQty: 300, requestedUnit: 'แผ่น', requestedUnitBasis: 'PER_PIECE', quantityType: 'ESTIMATE', catalogBasePrice: 95 }],
  });
  pushPrEvent(pr9, SALES1, 'PRICING_REQUEST_CREATED', null, 'DRAFT', null, daysAgoIso(25));
  pushPrEvent(pr9, SALES1, 'PRICING_REQUEST_SUBMITTED', 'DRAFT', 'SUBMITTED', null, daysAgoIso(24));
  pushPrEvent(pr9, IMPORT1, 'PRICING_REQUEST_PICKED_UP', 'SUBMITTED', 'IMPORT_REVIEWING', null, daysAgoIso(23));
  pushPrEvent(pr9, CEO, 'PRICING_DECISION_RETURNED', 'CEO_REVIEWING', 'COSTING_REVISION_REQUIRED', 'มาร์จิ้นต่ำเกินไปเทียบราคาตลาด', daysAgoIso(16));
  const fq9 = makeFactoryQuote(pr9, {
    status: 'READY_FOR_COSTING', response: true, factoryName: 'SCG Ceramics',
    lines: [{ prItemId: pr9.items[0].id, qty: 300, unitBasis: 'PER_PIECE', rawUnitPrice: 95, sqmPerUnit: 0.36 }],
    createdAt: daysAgoIso(20),
  });
  const costing9 = makeCosting(pr9, {
    status: 'SUBMITTED', submittedAt: daysAgoIso(18), createdAt: daysAgoIso(19),
    lines: [{ prItemId: pr9.items[0].id, fqId: fq9.id, fqItemId: fq9.items[0].id, factoryName: 'SCG Ceramics', rawUnitPrice: 95, unitBasis: 'PER_PIECE', unitLabel: 'แผ่น', qty: 300, landedCostPerUnitThb: 109, sqmPerUnit: 0.36 }],
  });
  makeDecision(pr9, costing9, {
    status: 'RETURNED', marginPct: 0.05, createdAt: daysAgoIso(17),
    returnedAt: daysAgoIso(16), returnReason: 'มาร์จิ้นต่ำเกินไปเทียบราคาตลาด',
  });

  // PR10 — ticket 5 — QUOTATION_ISSUED (Quotation history: SUPERSEDED -> ISSUED, a
  // commercial revision — createCustomerQuotationRevision, mockApi.js, always leaves the
  // source quotation SUPERSEDED once a revision exists; REJECTED is a terminal outcome that
  // can never grow a revision, see D5 in the review report)
  const pr10 = makePr({
    ticketId: 5, recipientType: 'DESIGNER', recipientLabel: 'ผู้ออกแบบ Event Organizer',
    status: 'QUOTATION_ISSUED', requestedBy: SALES1, assignedImport: IMPORT1,
    submittedAt: daysAgoIso(50), pickedUpAt: daysAgoIso(49), createdAt: daysAgoIso(51), clientRequestSeed: 10,
    items: [{ sourceTicketItemId: 8, brand: 'SCG', model: 'Granite Black', color: 'ดำ', texture: 'หยาบ', size: '60x60 ซม.', factory: 'SCG Ceramics', requestedQty: 300, requestedUnit: 'แผ่น', requestedUnitBasis: 'PER_PIECE', quantityType: 'CONFIRMED', catalogBasePrice: 95 }],
  });
  pushPrEvent(pr10, SALES1, 'PRICING_REQUEST_CREATED', null, 'DRAFT', null, daysAgoIso(51));
  pushPrEvent(pr10, SALES1, 'PRICING_REQUEST_SUBMITTED', 'DRAFT', 'SUBMITTED', null, daysAgoIso(50));
  pushPrEvent(pr10, IMPORT1, 'PRICING_REQUEST_PICKED_UP', 'SUBMITTED', 'IMPORT_REVIEWING', null, daysAgoIso(49));
  pushPrEvent(pr10, SALES1, 'CUSTOMER_QUOTATION_ISSUED', 'APPROVED_FOR_QUOTATION', 'QUOTATION_ISSUED', null, daysAgoIso(30));
  const fq10 = makeFactoryQuote(pr10, {
    status: 'READY_FOR_COSTING', response: true, factoryName: 'SCG Ceramics',
    lines: [{ prItemId: pr10.items[0].id, qty: 300, unitBasis: 'PER_PIECE', rawUnitPrice: 95, sqmPerUnit: 0.36 }],
    createdAt: daysAgoIso(45),
  });
  const costing10 = makeCosting(pr10, {
    status: 'SUBMITTED', submittedAt: daysAgoIso(43), createdAt: daysAgoIso(44),
    lines: [{ prItemId: pr10.items[0].id, fqId: fq10.id, fqItemId: fq10.items[0].id, factoryName: 'SCG Ceramics', rawUnitPrice: 95, unitBasis: 'PER_PIECE', unitLabel: 'แผ่น', qty: 300, landedCostPerUnitThb: 109, sqmPerUnit: 0.36 }],
  });
  const decision10 = makeDecision(pr10, costing10, { status: 'APPROVED', createdAt: daysAgoIso(35), approvedAt: daysAgoIso(35) });
  const q10Old = makeQuotation(pr10, decision10, {
    docStatus: 'SUPERSEDED', createdAt: daysAgoIso(33), issuedAt: daysAgoIso(33), sentAt: daysAgoIso(33),
    outcomeNote: 'ลูกค้าขอราคาต่ำกว่านี้',
  });
  makeQuotation(pr10, decision10, {
    docStatus: 'ISSUED', createdAt: daysAgoIso(30), issuedAt: daysAgoIso(30), sentAt: daysAgoIso(30),
    quotationRevisionNo: 2, parentQuotationId: q10Old.id, discountPerItem: 5,
  });

  // PR11 — ticket 7 — QUOTATION_ACCEPTED, 3 items/factories (full downstream: Quotation
  // SUPERSEDED+ACCEPTED, DepositNotice SUPERSEDED+ISSUED, FPO OPEN+SHIPPING+RECEIVED)
  const pr11 = makePr({
    ticketId: 7, recipientType: 'OWNER', recipientLabel: 'เจ้าของโครงการสยามพารากอน',
    status: 'QUOTATION_ACCEPTED', requestedBy: SALES1, assignedImport: IMPORT1,
    submittedAt: daysAgoIso(60), pickedUpAt: daysAgoIso(59), createdAt: daysAgoIso(61), clientRequestSeed: 11,
    items: [
      { sourceTicketItemId: 10, brand: 'SCG', model: 'Metro White', color: 'ขาว', texture: 'ด้าน', size: '60x60 ซม.', factory: 'SCG Ceramics', requestedQty: 400, requestedUnit: 'แผ่น', requestedUnitBasis: 'PER_PIECE', quantityType: 'CONFIRMED', catalogBasePrice: 90 },
      { sourceTicketItemId: null, productDescription: 'กระเบื้อง Cotto ลายหินอ่อน 60x120', brand: 'Cotto', model: 'Marble Series', factory: 'Cotto Industry', requestedQty: 200, requestedUnit: 'แผ่น', requestedUnitBasis: 'PER_PIECE', quantityType: 'CONFIRMED', catalogBasePrice: 450 },
      { sourceTicketItemId: null, productDescription: 'กระเบื้อง Duragres ลายไม้ 20x100', brand: 'Duragres', model: 'Forest Brown', factory: 'Duragres Thailand', requestedQty: 250, requestedUnit: 'แผ่น', requestedUnitBasis: 'PER_PIECE', quantityType: 'CONFIRMED', catalogBasePrice: 200 },
    ],
  });
  pushPrEvent(pr11, SALES1, 'PRICING_REQUEST_CREATED', null, 'DRAFT', null, daysAgoIso(61));
  pushPrEvent(pr11, SALES1, 'PRICING_REQUEST_SUBMITTED', 'DRAFT', 'SUBMITTED', null, daysAgoIso(60));
  pushPrEvent(pr11, IMPORT1, 'PRICING_REQUEST_PICKED_UP', 'SUBMITTED', 'IMPORT_REVIEWING', null, daysAgoIso(59));
  pushPrEvent(pr11, SALES1, 'CUSTOMER_QUOTATION_ACCEPTED', 'QUOTATION_ISSUED', 'QUOTATION_ACCEPTED', null, daysAgoIso(20));
  pushPrEvent(pr11, SALES1, 'ORDER_CONFIRMED', 'QUOTATION_ACCEPTED', 'QUOTATION_ACCEPTED', null, daysAgoIso(19));
  pr11.orderConfirmedAt = daysAgoIso(19);
  pr11.orderConfirmedBy = SALES1.id;
  const fq11a = makeFactoryQuote(pr11, { status: 'READY_FOR_COSTING', response: true, factoryName: 'SCG Ceramics', lines: [{ prItemId: pr11.items[0].id, qty: 400, unitBasis: 'PER_PIECE', rawUnitPrice: 90, sqmPerUnit: 0.36 }], createdAt: daysAgoIso(55) });
  const fq11b = makeFactoryQuote(pr11, { status: 'READY_FOR_COSTING', response: true, factoryName: 'Cotto Industry', lines: [{ prItemId: pr11.items[1].id, qty: 200, unitBasis: 'PER_PIECE', rawUnitPrice: 450, sqmPerUnit: 0.72 }], createdAt: daysAgoIso(54) });
  const fq11c = makeFactoryQuote(pr11, { status: 'READY_FOR_COSTING', response: true, factoryName: 'Duragres Thailand', lines: [{ prItemId: pr11.items[2].id, qty: 250, unitBasis: 'PER_PIECE', rawUnitPrice: 200, sqmPerUnit: 0.2 }], createdAt: daysAgoIso(53) });
  const costing11 = makeCosting(pr11, {
    status: 'SUBMITTED', submittedAt: daysAgoIso(48), createdAt: daysAgoIso(50),
    lines: [
      { prItemId: pr11.items[0].id, fqId: fq11a.id, fqItemId: fq11a.items[0].id, factoryName: 'SCG Ceramics', rawUnitPrice: 90, unitBasis: 'PER_PIECE', unitLabel: 'แผ่น', qty: 400, landedCostPerUnitThb: 104, sqmPerUnit: 0.36 },
      { prItemId: pr11.items[1].id, fqId: fq11b.id, fqItemId: fq11b.items[0].id, factoryName: 'Cotto Industry', rawUnitPrice: 450, unitBasis: 'PER_PIECE', unitLabel: 'แผ่น', qty: 200, landedCostPerUnitThb: 518, sqmPerUnit: 0.72 },
      { prItemId: pr11.items[2].id, fqId: fq11c.id, fqItemId: fq11c.items[0].id, factoryName: 'Duragres Thailand', rawUnitPrice: 200, unitBasis: 'PER_PIECE', unitLabel: 'แผ่น', qty: 250, landedCostPerUnitThb: 230, sqmPerUnit: 0.2 },
    ],
  });
  const decision11 = makeDecision(pr11, costing11, { status: 'APPROVED', createdAt: daysAgoIso(45), approvedAt: daysAgoIso(45) });
  const q11Old = makeQuotation(pr11, decision11, { docStatus: 'SUPERSEDED', createdAt: daysAgoIso(40), issuedAt: daysAgoIso(40), sentAt: daysAgoIso(40) });
  const q11 = makeQuotation(pr11, decision11, {
    docStatus: 'ACCEPTED', createdAt: daysAgoIso(25), issuedAt: daysAgoIso(25), sentAt: daysAgoIso(25),
    acceptedAt: daysAgoIso(20), quotationRevisionNo: 2, parentQuotationId: q11Old.id, outcomeNote: 'ลูกค้ายืนยันสั่งซื้อ',
  });
  const ticket7 = { id: 7, customerName: 'สยามพารากอน' };
  const dn11Old = makeDepositNotice(ticket7, q11, { status: 'SUPERSEDED', createdAt: daysAgoIso(24), issueDate: daysAgoIso(23), version: 1 });
  makeDepositNotice(ticket7, q11, { status: 'ISSUED', createdAt: daysAgoIso(18), issueDate: daysAgoIso(17), version: 2 });
  void dn11Old;

  // PR12 — ticket 8 — IMPORT_REVIEWING.
  //
  // This row used to seed at MORE_INFO_REQUIRED. V139 retired that status: it is no longer in
  // PricingRequestStatus.VALUES or the chk_pricing_request_status constraint, so a demo row
  // sitting in it represented a state the real backend can no longer hold. IMPORT_REVIEWING is
  // exactly where V139's data migration sends such a row when no resume_status was recorded,
  // which is this row's case — so the seed now mirrors what the migration actually does.
  //
  // The MORE_INFO_REQUESTED event below is KEPT on purpose. V139 deliberately does not rewrite
  // pricing_request_event history, and PricingRequestEventKind still accepts that kind, so a
  // demo request whose timeline shows a question once asked is faithful, not stale.
  const pr12 = makePr({
    ticketId: 8, recipientType: 'DESIGNER', recipientLabel: 'ผู้ออกแบบ Siam Mall',
    status: 'IMPORT_REVIEWING', requestedBy: SALES1, assignedImport: IMPORT1,
    submittedAt: daysAgoIso(40), pickedUpAt: daysAgoIso(39), createdAt: daysAgoIso(41), clientRequestSeed: 12,
    items: [{ sourceTicketItemId: 11, brand: 'Cotto', model: 'Basic White', color: 'ขาว', texture: 'ด้าน', size: '30x30 ซม.', factory: 'Cotto Industry', requestedQty: 800, requestedUnit: 'แผ่น', requestedUnitBasis: 'PER_PIECE', quantityType: 'ESTIMATE', catalogBasePrice: 50 }],
  });
  pushPrEvent(pr12, SALES1, 'PRICING_REQUEST_CREATED', null, 'DRAFT', null, daysAgoIso(41));
  pushPrEvent(pr12, SALES1, 'PRICING_REQUEST_SUBMITTED', 'DRAFT', 'SUBMITTED', null, daysAgoIso(40));
  pushPrEvent(pr12, IMPORT1, 'PRICING_REQUEST_PICKED_UP', 'SUBMITTED', 'IMPORT_REVIEWING', null, daysAgoIso(39));
  pushPrEvent(pr12, IMPORT1, 'MORE_INFO_REQUESTED', 'IMPORT_REVIEWING', 'MORE_INFO_REQUIRED', 'ต้องการทราบเฉดสีที่ต้องการเจาะจง เนื่องจากรุ่นนี้มีหลายเฉด', daysAgoIso(37));
  makeFactoryQuote(pr12, { status: 'NOT_AVAILABLE', factoryName: 'Cotto Industry', lines: [{ prItemId: pr12.items[0].id, qty: 800, unitBasis: 'PER_PIECE' }], createdAt: daysAgoIso(38), note: 'โรงงานแจ้งสินค้าหมดสต็อกรุ่นนี้ชั่วคราว' });
  addPrAttachment(pr12, { fileName: 'color-shade-reference.jpg', mimeType: 'image/jpeg', fileSize: 245000, includeInFactoryEmail: true, uploadedBy: SALES1, at: daysAgoIso(36) });

  // PR13 — ticket 9 — CANCELLED
  const pr13 = makePr({
    ticketId: 9, recipientType: 'OWNER', recipientLabel: 'เจ้าของเทพสตูดิโอ',
    status: 'CANCELLED', requestedBy: SALES1, submittedAt: daysAgoIso(70),
    cancelledAt: daysAgoIso(65), createdAt: daysAgoIso(71), clientRequestSeed: 13,
    items: [{ sourceTicketItemId: 12, brand: 'Duragres', model: 'Cement Series', color: 'เทา', texture: 'ด้าน', size: '60x60 ซม.', factory: 'Duragres Thailand', requestedQty: 600, requestedUnit: 'แผ่น', requestedUnitBasis: 'PER_PIECE', quantityType: 'ESTIMATE', catalogBasePrice: 120 }],
  });
  pushPrEvent(pr13, SALES1, 'PRICING_REQUEST_CREATED', null, 'DRAFT', null, daysAgoIso(71));
  pushPrEvent(pr13, SALES1, 'PRICING_REQUEST_SUBMITTED', 'DRAFT', 'SUBMITTED', null, daysAgoIso(70));
  pushPrEvent(pr13, SALES1, 'PRICING_REQUEST_CANCELLED', 'SUBMITTED', 'CANCELLED', 'ลูกค้าเปลี่ยนใจเลือกซัพพลายเออร์อื่น', daysAgoIso(65));

  // PR14 (parent, SUPERSEDED) + PR15 (revision 2, SUBMITTED) — ticket 10
  const pr14 = makePr({
    ticketId: 10, recipientType: 'BUYER', recipientLabel: 'บริษัท ยกเลิก จำกัด',
    status: 'SUPERSEDED', requestedBy: SALES1, assignedImport: IMPORT1,
    submittedAt: daysAgoIso(55), pickedUpAt: daysAgoIso(54), createdAt: daysAgoIso(56), clientRequestSeed: 14,
    items: [{ sourceTicketItemId: 13, brand: 'SCG', model: 'Wood Look', color: 'น้ำตาล', texture: 'ลายไม้', size: '20x120 ซม.', factory: 'SCG Ceramics', requestedQty: 200, requestedUnit: 'แผ่น', requestedUnitBasis: 'PER_PIECE', quantityType: 'ESTIMATE', catalogBasePrice: 140 }],
  });
  pushPrEvent(pr14, SALES1, 'PRICING_REQUEST_CREATED', null, 'DRAFT', null, daysAgoIso(56));
  pushPrEvent(pr14, SALES1, 'PRICING_REQUEST_SUBMITTED', 'DRAFT', 'SUBMITTED', null, daysAgoIso(55));
  pushPrEvent(pr14, IMPORT1, 'PRICING_REQUEST_PICKED_UP', 'SUBMITTED', 'IMPORT_REVIEWING', null, daysAgoIso(54));
  pushPrEvent(pr14, SALES1, 'PRICING_REQUEST_REVISED', 'IMPORT_REVIEWING', 'SUPERSEDED', 'ลูกค้าขอเปลี่ยนสเปกสินค้าใหม่', daysAgoIso(50));

  const pr15 = makePr({
    ticketId: 10, recipientType: 'BUYER', recipientLabel: 'บริษัท ยกเลิก จำกัด',
    status: 'SUBMITTED', requestedBy: SALES1, submittedAt: daysAgoIso(49),
    createdAt: daysAgoIso(50), clientRequestSeed: 15,
    revisionNo: 2, parentPricingRequestId: pr14.id,
    items: [{ sourceTicketItemId: 13, brand: 'SCG', model: 'Wood Look Premium', color: 'น้ำตาลเข้ม', texture: 'ลายไม้', size: '20x120 ซม.', factory: 'SCG Ceramics', requestedQty: 200, requestedUnit: 'แผ่น', requestedUnitBasis: 'PER_PIECE', quantityType: 'CONFIRMED', catalogBasePrice: 155 }],
  });
  pushPrEvent(pr15, SALES1, 'PRICING_REQUEST_CREATED', null, 'DRAFT', null, daysAgoIso(50));
  pushPrEvent(pr15, SALES1, 'PRICING_REQUEST_SUBMITTED', 'DRAFT', 'SUBMITTED', null, daysAgoIso(49));

  // PR16 — ticket 16 — rep2's own DRAFT (DRAFT-visibility demo: only rep2/CEO/sales_manager see it)
  const pr16 = makePr({
    ticketId: 16, recipientType: 'OWNER', recipientLabel: 'เจ้าของโรงแรมริเวอร์ไซด์',
    status: 'DRAFT', requestedBy: SALES2, createdAt: daysAgoIso(5), clientRequestSeed: 16,
    items: [{ sourceTicketItemId: 18, brand: 'SCG', model: 'Crystal White', color: 'ขาวมุก', texture: 'มัน', size: '60x120 ซม.', factory: 'SCG Ceramics', requestedQty: 150, requestedUnit: 'แผ่น', requestedUnitBasis: 'PER_PIECE', quantityType: 'ESTIMATE' }],
  });
  pushPrEvent(pr16, SALES2, 'PRICING_REQUEST_CREATED', null, 'DRAFT', null, daysAgoIso(5));
  addPrAttachment(pr16, { fileName: 'hotel-floorplan.pdf', includeInFactoryEmail: false, uploadedBy: SALES2, at: daysAgoIso(5) });

  // PR17 — ticket 17 — rep2's SUBMITTED request
  const pr17 = makePr({
    ticketId: 17, recipientType: 'DESIGNER', recipientLabel: 'ผู้ออกแบบลุมพินี สาทร',
    status: 'SUBMITTED', requestedBy: SALES2, submittedAt: daysAgoIso(1),
    createdAt: daysAgoIso(2), clientRequestSeed: 17,
    items: [{ sourceTicketItemId: 19, brand: 'Cotto', model: 'Stone Series', color: 'เทาเข้ม', texture: 'หยาบ', size: '60x60 ซม.', factory: 'Cotto Industry', requestedQty: 400, requestedUnit: 'แผ่น', requestedUnitBasis: 'PER_PIECE', quantityType: 'CONFIRMED', catalogBasePrice: 250 }],
  });
  pushPrEvent(pr17, SALES2, 'PRICING_REQUEST_CREATED', null, 'DRAFT', null, daysAgoIso(2));
  pushPrEvent(pr17, SALES2, 'PRICING_REQUEST_SUBMITTED', 'DRAFT', 'SUBMITTED', null, daysAgoIso(1));

  // PR18 — ticket 18 — APPROVED_FOR_QUOTATION; FactoryQuote revision chain
  // (SUPERSEDED -> READY_FOR_COSTING); CustomerQuotation DRAFT + a separate CANCELLED attempt.
  const pr18 = makePr({
    ticketId: 18, recipientType: 'OWNER', recipientLabel: 'เจ้าของศูนย์การค้า Fashion Island',
    status: 'APPROVED_FOR_QUOTATION', requestedBy: SALES1, assignedImport: IMPORT1,
    submittedAt: '2026-06-25T09:30:00Z', pickedUpAt: '2026-06-26T08:00:00Z',
    createdAt: '2026-06-25T09:00:00Z', clientRequestSeed: 18,
    items: [{ sourceTicketItemId: 20, brand: 'SCG', model: 'Elegance Series', color: 'ขาวนวล', texture: 'ด้าน', size: '60x60 ซม.', factory: 'SCG Ceramics', requestedQty: 600, requestedUnit: 'แผ่น', requestedUnitBasis: 'PER_PIECE', quantityType: 'CONFIRMED', catalogBasePrice: 95 }],
  });
  pushPrEvent(pr18, SALES1, 'PRICING_REQUEST_CREATED', null, 'DRAFT', null, '2026-06-25T09:00:00Z');
  pushPrEvent(pr18, SALES1, 'PRICING_REQUEST_SUBMITTED', 'DRAFT', 'SUBMITTED', null, '2026-06-25T09:30:00Z');
  pushPrEvent(pr18, IMPORT1, 'PRICING_REQUEST_PICKED_UP', 'SUBMITTED', 'IMPORT_REVIEWING', null, '2026-06-26T08:00:00Z');
  pushPrEvent(pr18, CEO, 'PRICING_DECISION_APPROVED', 'CEO_REVIEWING', 'APPROVED_FOR_QUOTATION', null, daysAgoIso(20));
  const fq18Old = makeFactoryQuote(pr18, { status: 'SUPERSEDED', current: false, response: true, factoryName: 'SCG Ceramics', lines: [{ prItemId: pr18.items[0].id, qty: 600, unitBasis: 'PER_PIECE', rawUnitPrice: 105, sqmPerUnit: 0.36 }], createdAt: daysAgoIso(33) });
  const fq18 = makeFactoryQuote(pr18, { status: 'READY_FOR_COSTING', response: true, factoryName: 'SCG Ceramics', lines: [{ prItemId: pr18.items[0].id, qty: 600, unitBasis: 'PER_PIECE', rawUnitPrice: 95, sqmPerUnit: 0.36 }], createdAt: daysAgoIso(28), parentFactoryQuoteId: fq18Old.id, revisionNo: 2 });
  const costing18 = makeCosting(pr18, {
    status: 'SUBMITTED', submittedAt: daysAgoIso(22), createdAt: daysAgoIso(24),
    lines: [{ prItemId: pr18.items[0].id, fqId: fq18.id, fqItemId: fq18.items[0].id, factoryName: 'SCG Ceramics', rawUnitPrice: 95, unitBasis: 'PER_PIECE', unitLabel: 'แผ่น', qty: 600, landedCostPerUnitThb: 109, sqmPerUnit: 0.36 }],
  });
  const decision18 = makeDecision(pr18, costing18, { status: 'APPROVED', createdAt: daysAgoIso(20), approvedAt: daysAgoIso(20) });
  makeQuotation(pr18, decision18, { docStatus: 'DRAFT', createdAt: daysAgoIso(6) });
  makeQuotation(pr18, decision18, { docStatus: 'CANCELLED', createdAt: daysAgoIso(19) });

  // PR19 — ticket 18 — QUOTATION_ISSUED; FactoryQuote NEGOTIATING; CustomerQuotation
  // REVISION_REQUESTED.
  const pr19 = makePr({
    ticketId: 18, recipientType: 'DESIGNER', recipientLabel: 'ผู้ออกแบบ Fashion Island',
    status: 'QUOTATION_ISSUED', requestedBy: SALES1, assignedImport: IMPORT1,
    submittedAt: '2026-06-25T09:31:00Z', pickedUpAt: '2026-06-26T08:05:00Z',
    createdAt: '2026-06-25T09:01:00Z', clientRequestSeed: 19,
    items: [{ sourceTicketItemId: 21, brand: 'Cotto', model: 'Metro Square', color: 'ครีม', texture: 'ด้าน', size: '30x30 ซม.', factory: 'Cotto Industry', requestedQty: 900, requestedUnit: 'แผ่น', requestedUnitBasis: 'PER_PIECE', quantityType: 'CONFIRMED', catalogBasePrice: 55 }],
  });
  pushPrEvent(pr19, SALES1, 'PRICING_REQUEST_CREATED', null, 'DRAFT', null, '2026-06-25T09:01:00Z');
  pushPrEvent(pr19, SALES1, 'PRICING_REQUEST_SUBMITTED', 'DRAFT', 'SUBMITTED', null, '2026-06-25T09:31:00Z');
  pushPrEvent(pr19, IMPORT1, 'PRICING_REQUEST_PICKED_UP', 'SUBMITTED', 'IMPORT_REVIEWING', null, '2026-06-26T08:05:00Z');
  pushPrEvent(pr19, SALES1, 'CUSTOMER_QUOTATION_ISSUED', 'APPROVED_FOR_QUOTATION', 'QUOTATION_ISSUED', null, daysAgoIso(12));
  makeFactoryQuote(pr19, { status: 'NEGOTIATING', response: true, factoryName: 'Cotto Industry', lines: [{ prItemId: pr19.items[0].id, qty: 900, unitBasis: 'PER_PIECE', rawUnitPrice: 55, sqmPerUnit: 0.09 }], createdAt: daysAgoIso(25), negotiationNote: 'ขอต่อรองราคาลงอีก 3% เนื่องจากสั่งจำนวนมาก' });
  const costing19 = makeCosting(pr19, {
    status: 'SUBMITTED', submittedAt: daysAgoIso(18), createdAt: daysAgoIso(19),
    lines: [{ prItemId: pr19.items[0].id, fqId: factoryQuotes[factoryQuotes.length - 1].id, fqItemId: factoryQuotes[factoryQuotes.length - 1].items[0].id, factoryName: 'Cotto Industry', rawUnitPrice: 55, unitBasis: 'PER_PIECE', unitLabel: 'แผ่น', qty: 900, landedCostPerUnitThb: 63, sqmPerUnit: 0.09 }],
  });
  const decision19 = makeDecision(pr19, costing19, { status: 'APPROVED', createdAt: daysAgoIso(14), approvedAt: daysAgoIso(14) });
  const quotation19 = makeQuotation(pr19, decision19, {
    docStatus: 'REVISION_REQUESTED', createdAt: daysAgoIso(12), issuedAt: daysAgoIso(12), sentAt: daysAgoIso(12),
    outcomeNote: 'ลูกค้าขอปรับเงื่อนไขการชำระเงินเป็นแบ่งจ่าย 2 งวด',
  });
  void quotation19;

  // PR20 — ticket 18 — QUOTATION_ACCEPTED; leftover unsent DRAFT FactoryQuote;
  // FactoryPurchaseOrder CANCELLED; DepositNotice DRAFT (pre-drafted off the accepted
  // quotation while the deposit is awaited — D4 fix: was built from quotation19's
  // REVISION_REQUESTED doc, which neither createDepositNoticeFromQuotation (mockApi.js's
  // hard 409) nor mockPickTicketQuotationForDepositNotice's ACCEPTED->ISSUED fallback would
  // ever ACTUALLY pick, so that row could not exist in the real app).
  const pr20 = makePr({
    ticketId: 18, recipientType: 'BUYER', recipientLabel: 'ผู้รับเหมา Fashion Island',
    status: 'QUOTATION_ACCEPTED', requestedBy: SALES1, assignedImport: IMPORT1,
    submittedAt: '2026-06-25T09:32:00Z', pickedUpAt: '2026-06-26T08:10:00Z',
    createdAt: '2026-06-25T09:02:00Z', clientRequestSeed: 20,
    items: [{ sourceTicketItemId: 22, brand: 'Duragres', model: 'Granite Plus', color: 'เทากลาง', texture: 'หยาบกึ่งมัน', size: '60x60 ซม.', factory: 'Duragres Thailand', requestedQty: 300, requestedUnit: 'แผ่น', requestedUnitBasis: 'PER_PIECE', quantityType: 'CONFIRMED', catalogBasePrice: 110 }],
  });
  pushPrEvent(pr20, SALES1, 'PRICING_REQUEST_CREATED', null, 'DRAFT', null, '2026-06-25T09:02:00Z');
  pushPrEvent(pr20, SALES1, 'PRICING_REQUEST_SUBMITTED', 'DRAFT', 'SUBMITTED', null, '2026-06-25T09:32:00Z');
  pushPrEvent(pr20, IMPORT1, 'PRICING_REQUEST_PICKED_UP', 'SUBMITTED', 'IMPORT_REVIEWING', null, '2026-06-26T08:10:00Z');
  pushPrEvent(pr20, SALES1, 'CUSTOMER_QUOTATION_ACCEPTED', 'QUOTATION_ISSUED', 'QUOTATION_ACCEPTED', null, daysAgoIso(9));
  pushPrEvent(pr20, SALES1, 'ORDER_CONFIRMED', 'QUOTATION_ACCEPTED', 'QUOTATION_ACCEPTED', null, daysAgoIso(8));
  pr20.orderConfirmedAt = daysAgoIso(8);
  pr20.orderConfirmedBy = SALES1.id;
  // Draft quote for a second, never-used factory — a plausible leftover artifact.
  makeFactoryQuote(pr20, { status: 'DRAFT', factoryName: 'Panaria SpA', lines: [{ prItemId: pr20.items[0].id, qty: 50, unitBasis: 'PER_SQM' }], createdAt: daysAgoIso(9) });
  const fq20 = makeFactoryQuote(pr20, { status: 'READY_FOR_COSTING', response: true, factoryName: 'Duragres Thailand', lines: [{ prItemId: pr20.items[0].id, qty: 300, unitBasis: 'PER_PIECE', rawUnitPrice: 110, sqmPerUnit: 0.36 }], createdAt: daysAgoIso(16) });
  const costing20 = makeCosting(pr20, {
    status: 'SUBMITTED', submittedAt: daysAgoIso(13), createdAt: daysAgoIso(14),
    lines: [{ prItemId: pr20.items[0].id, fqId: fq20.id, fqItemId: fq20.items[0].id, factoryName: 'Duragres Thailand', rawUnitPrice: 110, unitBasis: 'PER_PIECE', unitLabel: 'แผ่น', qty: 300, landedCostPerUnitThb: 127, sqmPerUnit: 0.36 }],
  });
  const decision20 = makeDecision(pr20, costing20, { status: 'APPROVED', createdAt: daysAgoIso(11), approvedAt: daysAgoIso(11) });
  const quotation20 = makeQuotation(pr20, decision20, { docStatus: 'ACCEPTED', createdAt: daysAgoIso(10), issuedAt: daysAgoIso(10), sentAt: daysAgoIso(10), acceptedAt: daysAgoIso(9) });
  makeDepositNotice({ id: 18, customerName: 'บริษัท แฟชั่นไอส์แลนด์ จำกัด' }, quotation20, { status: 'DRAFT', createdAt: daysAgoIso(4) });

  // PR21 — ticket 18 — AWAITING_FACTORY_RESPONSE with a CALCULATED (not yet submitted) costing.
  // Same V139 status merge as PR5 above.
  const pr21 = makePr({
    ticketId: 18, recipientType: 'OWNER', recipientLabel: 'เจ้าของศูนย์การค้า Fashion Island',
    status: 'AWAITING_FACTORY_RESPONSE', requestedBy: SALES1, assignedImport: IMPORT1,
    submittedAt: '2026-06-25T09:33:00Z', pickedUpAt: '2026-06-26T08:15:00Z',
    createdAt: '2026-06-25T09:03:00Z', clientRequestSeed: 21,
    items: [{ sourceTicketItemId: 23, brand: 'Panaria', model: 'Trilogy', color: 'Ivory', texture: 'Lappato', size: '60x120 cm', factory: 'Panaria SpA', requestedQty: 120, requestedUnit: 'แผ่น', requestedUnitBasis: 'PER_PIECE', quantityType: 'ESTIMATE', catalogBasePrice: 1400 }],
  });
  pushPrEvent(pr21, SALES1, 'PRICING_REQUEST_CREATED', null, 'DRAFT', null, '2026-06-25T09:03:00Z');
  pushPrEvent(pr21, SALES1, 'PRICING_REQUEST_SUBMITTED', 'DRAFT', 'SUBMITTED', null, '2026-06-25T09:33:00Z');
  pushPrEvent(pr21, IMPORT1, 'PRICING_REQUEST_PICKED_UP', 'SUBMITTED', 'IMPORT_REVIEWING', null, '2026-06-26T08:15:00Z');
  pushPrEvent(pr21, IMPORT1, 'FACTORY_RESPONSE_READY_FOR_COSTING', 'AWAITING_FACTORY_RESPONSE', 'COSTING_IN_PROGRESS', null, daysAgoIso(6));
  const fq21 = makeFactoryQuote(pr21, { status: 'READY_FOR_COSTING', response: true, factoryName: 'Panaria SpA', lines: [{ prItemId: pr21.items[0].id, qty: 120, unitBasis: 'PER_PIECE', rawUnitPrice: 1400, sqmPerUnit: 0.72 }], createdAt: daysAgoIso(10) });
  makeCosting(pr21, {
    status: 'CALCULATED', createdAt: daysAgoIso(5),
    lines: [{ prItemId: pr21.items[0].id, fqId: fq21.id, fqItemId: fq21.items[0].id, factoryName: 'Panaria SpA', rawUnitPrice: 1400, unitBasis: 'PER_PIECE', unitLabel: 'แผ่น', qty: 120, landedCostPerUnitThb: 1610, sqmPerUnit: 0.72 }],
  });

  // ══════════════════════════════════════════════════════════════════════
  // Deal activities — independent of the PricingRequest chain (keyed by
  // ticketId only). Covers every ACTIVITY_KINDS value and demonstrates the
  // ready-to-advance / stale gates both ways.
  // ══════════════════════════════════════════════════════════════════════
  const followUpOverrides = [];
  function setNextFollowUp(ticketId, date) {
    followUpOverrides.push({ ticketId, nextFollowUpAt: date });
  }

  // Ticket 1: nextFollowUpAt set + recent activity -> ready AND fresh.
  addDealActivity(1, { kind: 'CALL', activityDate: daysAgoIso(2, '').slice(0, 10), note: 'โทรติดตามยืนยันสเปกสินค้ากับลูกค้า', createdBy: SALES1, at: daysAgoIso(2) });
  setNextFollowUp(1, daysAgoIso(-5, '').slice(0, 10));

  // Ticket 2: no nextFollowUpAt, no activity -> NOT ready, STALE.
  // (deliberately no rows)

  // Ticket 3: nextFollowUpAt set + OLD activity (after last stage change, but >7 days ago)
  // -> ready-to-advance AND stale at the same time.
  addDealActivity(3, { kind: 'MEETING', activityDate: daysAgoIso(20, '').slice(0, 10), note: 'นัดพบลูกค้าที่ไซต์งานเพื่อนำเสนอตัวอย่างกระเบื้อง', createdBy: SALES1, at: daysAgoIso(20) });
  setNextFollowUp(3, daysAgoIso(-3, '').slice(0, 10));

  // Ticket 4: nextFollowUpAt set + recent activity -> ready + fresh.
  addDealActivity(4, { kind: 'SITE_VISIT', activityDate: daysAgoIso(1, '').slice(0, 10), note: 'เข้าหน้างานตรวจสอบพื้นที่ก่อนติดตั้ง', createdBy: SALES1, at: daysAgoIso(1) });
  setNextFollowUp(4, daysAgoIso(-7, '').slice(0, 10));

  // Ticket 5: recent activity but NO nextFollowUpAt -> not ready, fresh.
  addDealActivity(5, { kind: 'EMAIL', activityDate: daysAgoIso(3, '').slice(0, 10), note: 'ส่งอีเมลสรุปรายละเอียดสินค้าที่ลูกค้าสนใจ', createdBy: SALES1, at: daysAgoIso(3) });

  // Ticket 6: QUOTATION_FOLLOWUP kind, ready + fresh.
  addDealActivity(6, { kind: 'QUOTATION_FOLLOWUP', activityDate: daysAgoIso(2, '').slice(0, 10), note: 'ติดตามผลใบเสนอราคาที่ส่งไปเมื่อสัปดาห์ก่อน', createdBy: SALES1, at: daysAgoIso(2) });
  setNextFollowUp(6, daysAgoIso(-4, '').slice(0, 10));

  // Ticket 15: already has a real STAGE_CHANGED event (2026-07-14). Activity logged
  // after that stage change but > 7 days before "today" -> ready + stale.
  addDealActivity(15, { kind: 'MESSAGE', activityDate: '2026-07-20', note: 'ทักไลน์สอบถามความคืบหน้าการนำเสนอ', createdBy: SALES1, at: '2026-07-20T10:00:00Z' });
  setNextFollowUp(15, daysAgoIso(-2, '').slice(0, 10));

  // Ticket 16 (rep2, new): OTHER kind, recent -> not ready (no nextFollowUpAt), fresh.
  addDealActivity(16, { kind: 'OTHER', activityDate: daysAgoIso(4, '').slice(0, 10), note: 'ได้รับนามบัตรจากงานแสดงสินค้า บันทึกไว้ติดตามต่อ', createdBy: SALES2, at: daysAgoIso(4) });

  // Ticket 18: multiple kinds, ready + fresh (no STAGE_CHANGED event yet -> anchor is
  // ticket.createdAt, 2026-06-25, so any activity after that counts).
  addDealActivity(18, { kind: 'CALL', activityDate: daysAgoIso(6, '').slice(0, 10), note: 'โทรแจ้งความคืบหน้าคำขอราคาให้ลูกค้าทราบ', createdBy: SALES1, at: daysAgoIso(6) });
  addDealActivity(18, { kind: 'EMAIL', activityDate: daysAgoIso(1, '').slice(0, 10), note: 'ส่งอีเมลแจ้งรายละเอียดใบเสนอราคาที่ปรับใหม่', createdBy: SALES1, at: daysAgoIso(1) });
  setNextFollowUp(18, daysAgoIso(-6, '').slice(0, 10));

  // ── Attachments (ticket-level) ──────────────────────────────────────────
  addAttachment(6, { fileName: 'tax-invoice-QT-2026-0001.pdf', attachType: 'INVOICE', uploadedBy: SALES1, at: daysAgoIso(30) });
  addAttachment(12, { fileName: 'purchase-order-terminal21.pdf', attachType: 'PO', uploadedBy: IMPORT1, at: daysAgoIso(25) });
  addAttachment(18, { fileName: 'site-photos-fashion-island.zip', attachType: 'OTHER', mimeType: 'application/zip', fileSize: 2048000, uploadedBy: SALES1, at: daysAgoIso(5) });

  return {
    tickets,
    pricingRequests, factoryQuotes, pricingCostings, pricingDecisions,
    customerQuotations, depositNotices,
    dealActivities, attachments, followUpOverrides,
    nextSeq: {
      pricingRequest: prSeq + 1,
      pricingRequestItem: prItemSeq + 1,
      pricingRequestEvent: prEventSeq + 1,
      pricingRequestAttachment: prAttachSeq + 1,
      factoryQuote: fqSeq + 1,
      factoryQuoteItem: fqItemSeq + 1,
      pricingCosting: costingSeq + 1,
      pricingDecision: decisionSeq + 1,
      pricingDecisionItem: decisionItemSeq + 1,
      customerQuotation: quotationSeq + 1,
      customerQuotationItem: quotationItemSeq + 1,
      doc: docSeq + 1,
      docNumber: docNumberSeq + 1,
      dealActivity: dealActivitySeq + 1,
      attach: attachSeq + 1,
    },
  };
}
