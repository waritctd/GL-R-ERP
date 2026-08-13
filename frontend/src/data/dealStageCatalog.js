// The canned GET /api/meta/deal-stages payload for VITE_USE_MOCKS=true.
//
// THIS IS A FIXTURE, NOT A RULE. It mirrors the SHAPE of what
// th.co.glr.hr.ticket.DealStageMetaController serves, exactly as every other mock fixture mirrors
// its DTO — the mock has to answer the request with something. What it deliberately does NOT do is
// re-implement any decision: nothing here computes who may set a stage, or whether a move needs a
// written reason. Those come from the ticket's own decision payload (TicketService.stageDecisions).
//
// Mirrors DealStage.ORDER / gateOf / phaseOf / displayNoOf / businessCodeOf / isAutoAdvanced, and
// DealLostReason.ORDER / DealCancelReason.ORDER.
//
// ⚠️ A mirror with no guard is the exact defect this whole change exists to remove: the frontend's
// previous copy of this list silently stopped at fourteen stages when V143 added QUOTE_OWNER.
// features/tickets/stageCatalog.test.js therefore reads DealStage.java out of the backend source
// tree and asserts this file's codes, order, gates and phases against it. If you edit one, the
// test tells you about the other.

export const DEAL_STAGE_CATALOG = {
  stages: [
    { code: 'LEAD_APPROACH',       no: 1,  businessCode: 'S1',      phase: 1, gate: 'sales',   auto: false },
    { code: 'PRESENTATION',        no: 2,  businessCode: 'S2',      phase: 1, gate: 'sales',   auto: false },
    { code: 'SPEC_APPROVED',       no: 3,  businessCode: 'S3',      phase: 2, gate: 'sales',   auto: false },
    { code: 'QUOTE_DESIGN_SIDE',   no: 4,  businessCode: 'S4',      phase: 2, gate: 'sales',   auto: false },
    { code: 'QUOTE_OWNER',         no: 5,  businessCode: 'S5',      phase: 2, gate: 'sales',   auto: false },
    { code: 'OWNER_SIGNOFF',       no: 6,  businessCode: 'S6',      phase: 2, gate: 'sales',   auto: false },
    { code: 'AWAITING_BUYER',      no: 7,  businessCode: 'S7',      phase: 3, gate: 'sales',   auto: false },
    { code: 'QUOTE_BUYER',         no: 8,  businessCode: 'S8',      phase: 3, gate: 'sales',   auto: false },
    { code: 'NEGOTIATION',         no: 9,  businessCode: 'S9',      phase: 3, gate: 'sales',   auto: false },
    { code: 'ORDER_RECEIVED',      no: 10, businessCode: 'S10',     phase: 4, gate: 'sales',   auto: true },
    { code: 'DEPOSIT_RECEIVED',    no: 11, businessCode: 'S11',     phase: 4, gate: 'account', auto: true },
    { code: 'PROCUREMENT',         no: 12, businessCode: 'S12–S17', phase: 4, gate: 'import',  auto: true },
    { code: 'DELIVERY_SCHEDULING', no: 13, businessCode: 'S18',     phase: 5, gate: 'sales',   auto: false },
    { code: 'DELIVERED',           no: 14, businessCode: 'S19',     phase: 5, gate: 'sales',   auto: false },
    { code: 'CLOSED_PAID',         no: 15, businessCode: 'S20',     phase: 5, gate: 'account', auto: true },
  ],
  phases: [1, 2, 3, 4, 5],
  lostReasons: [
    'PRODUCT_FIT', 'PRICE', 'LEAD_TIME', 'PAYMENT_TERMS',
    'RELATIONSHIP', 'PROJECT_ON_HOLD', 'PROJECT_CANCELLED', 'ALREADY_PURCHASED',
  ],
  cancelReasons: ['OWNER_CANCELLED', 'PROJECT_SUSPENDED', 'BUDGET_CANCELLED', 'OTHER'],
};
