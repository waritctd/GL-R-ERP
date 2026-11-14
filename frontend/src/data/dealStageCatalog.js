// The canned GET /api/meta/deal-stages payload for VITE_USE_MOCKS=true. THIS IS A FIXTURE, NOT A RULE. It
// mirrors the SHAPE of what th.co.glr.hr.ticket.DealStageMetaController serves, exactly as every other mock
// fixture mirrors its DTO — the mock has to answer the request with something.

export const DEAL_STAGE_CATALOG = {
  stages: [
    { code: 'LEAD_APPROACH',       no: 1,  businessCode: 'S1',      phase: 1, gate: 'sales',   auto: false },
    { code: 'PRESENTATION',        no: 2,  businessCode: 'S2',      phase: 1, gate: 'sales',   auto: false },
    { code: 'SPEC_APPROVED',       no: 3,  businessCode: 'S3',      phase: 2, gate: 'sales',   auto: false },
    // The three quote stages are `auto` since the owner's 2026-10-16 ruling: a quotation addressed
    // to that party is what moves the deal onto its stage, so the UI must explain where the stage
    // comes from instead of offering a one-click เลื่อนไป into it. Mirrors DealStage.AUTO_ADVANCED.
    { code: 'QUOTE_DESIGN_SIDE',   no: 4,  businessCode: 'S4',      phase: 2, gate: 'sales',   auto: true },
    { code: 'QUOTE_OWNER',         no: 5,  businessCode: 'S5',      phase: 2, gate: 'sales',   auto: true },
    { code: 'OWNER_SIGNOFF',       no: 6,  businessCode: 'S6',      phase: 2, gate: 'sales',   auto: false },
    { code: 'AWAITING_BUYER',      no: 7,  businessCode: 'S7',      phase: 3, gate: 'sales',   auto: false },
    { code: 'QUOTE_BUYER',         no: 8,  businessCode: 'S8',      phase: 3, gate: 'sales',   auto: true },
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
  // Stage CODES per entry channel, in DealStage.ORDER — what a deal LIST row reads to print its position on its
  // own route (`4/11`) instead of the catalog-wide `8/15`. UNSPECIFIED is all fifteen: DealRoute gives it no
  // off-route stage.
  routes: {
    DESIGNER_LED: [
      'LEAD_APPROACH', 'PRESENTATION', 'SPEC_APPROVED', 'QUOTE_DESIGN_SIDE', 'QUOTE_OWNER',
      'OWNER_SIGNOFF', 'AWAITING_BUYER', 'QUOTE_BUYER', 'NEGOTIATION', 'ORDER_RECEIVED',
      'DEPOSIT_RECEIVED', 'PROCUREMENT', 'DELIVERY_SCHEDULING', 'DELIVERED', 'CLOSED_PAID',
    ],
    OWNER_DIRECT: [
      'LEAD_APPROACH', 'PRESENTATION', 'SPEC_APPROVED', 'QUOTE_OWNER',
      'OWNER_SIGNOFF', 'AWAITING_BUYER', 'QUOTE_BUYER', 'NEGOTIATION', 'ORDER_RECEIVED',
      'DEPOSIT_RECEIVED', 'PROCUREMENT', 'DELIVERY_SCHEDULING', 'DELIVERED', 'CLOSED_PAID',
    ],
    BUYER_DIRECT: [
      'LEAD_APPROACH', 'PRESENTATION', 'SPEC_APPROVED', 'QUOTE_BUYER', 'NEGOTIATION',
      'ORDER_RECEIVED', 'DEPOSIT_RECEIVED', 'PROCUREMENT', 'DELIVERY_SCHEDULING', 'DELIVERED',
      'CLOSED_PAID',
    ],
    UNSPECIFIED: [
      'LEAD_APPROACH', 'PRESENTATION', 'SPEC_APPROVED', 'QUOTE_DESIGN_SIDE', 'QUOTE_OWNER',
      'OWNER_SIGNOFF', 'AWAITING_BUYER', 'QUOTE_BUYER', 'NEGOTIATION', 'ORDER_RECEIVED',
      'DEPOSIT_RECEIVED', 'PROCUREMENT', 'DELIVERY_SCHEDULING', 'DELIVERED', 'CLOSED_PAID',
    ],
  },
};
