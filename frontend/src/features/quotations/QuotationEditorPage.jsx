/* Hallmark · genre: modern-minimal · macrostructure: Workbench · design-system: design.md · designed-as-app */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { api } from '../../api/index.js';
import { canAccessPath } from '../../app/permissions.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button, buttonVariants } from '../../components/common/Button.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { FormField } from '../../components/common/FormField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Panel, PageStack } from '../../components/common/Layout.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { OverflowMenu } from '../../components/common/OverflowMenu.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { RouteFallback } from '../../components/common/RouteFallback.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { cn } from '../../utils/cn.js';
import { downloadBlob } from '../../utils/download.js';
import { addDaysIso, bangkokTodayIso, dealStageLabel } from '../../utils/format.js';
import {
  canApproveDealQuotation, canApproveDealQuotationNow, canCancelDealQuotation, canCopyDealQuotation, canCreateDealQuotation,
  canChangeDealCustomer, canCreateDealQuotationStandalone, canDecideDealQuotation, canEditDealQuotation,
  canPromoteDealQuotationToDeal, canReviseDealQuotation, orderConfirmationOf, canSubmitDealQuotation, DEPOSIT_PERCENT_PRESETS,
  CONFIRM_ORDER_FROM_QUOTATION_COPY, REVISION_RECIPIENT_LOCKED_MESSAGE, quotationRecipientOption, recipientForDealStage, recipientForEntryChannel,
  availablePriceModes, PRICE_MODE_OPTIONS, adjustmentDescriptionPreview, buildQuotationChecklist, currencyForLanguage, missingLeadTimeMessage, splitQuotationChecklist, DOCUMENT_LANGUAGE_OPTIONS,
  isEffectiveZeroDeposit,
  estimateAdjustmentAmount, formatQuotationMoney, hasSpecialPricing, isEnglishPerSqm, LINE_TYPE_ADJUSTMENT, LINE_TYPE_PLAIN, LINE_TYPE_TILE,
  lineTypeOf, priceModeForLanguage, rowHasPriceForPreview, rowsWithPricesCleared, validateAdjustment, vatRateForLanguage,
  hasTypedPrice, usesListPriceSqm,
  isMixedPriceMode, isPriceModeAvailable, mixedPriceModeAvailable, PRICE_MODE_MIXED, PRICE_MODE_MIXED_OPTION,
  rowPriceMode, summarizeRowPriceModes,
  dealQuotationStatusLabel, isDealQuotationEditable, isDealQuotationReadOnlyViewer,
  liveQuotationOfParty, quotationTargetParty,
  duplicateLocationLabelGroupIds, emptyLocationGroupIds,
  newLocationGroupId,
  REMAINDER_MODE_OPTIONS, UNLABELLED_LOCATION_TEXT, validateQuotationItem, VALIDITY_DAYS_OPTIONS,
  VALIDITY_MODE_OPTIONS, FULL_PAYMENT_TERM_OPTIONS,
} from './quotationMeta.js';
import { CustomerDetailsFields } from './CustomerDetailsFields.jsx';
import { ChangeCustomerModal } from './ChangeCustomerModal.jsx';
import { DealCustomerCard, ExistingDealNotice, QuotationRecipientField } from './DealCustomerCard.jsx';
import { offRoutePartiesFor, recipientRouteRefusal, useStageCatalog } from '../tickets/stageCatalog.js';
import { useDealHasLivePricingRequest } from './DealPicker.jsx';
import { OrderedByContactFields, ProjectNamePrintToggle, QuotationDealFields } from './QuotationDealFields.jsx';
import { focusQuotationField, QuotationChecklist } from './QuotationChecklist.jsx';
import { QuotationContactPicker } from './QuotationContactPicker.jsx';
import { QuotationDocumentView } from './QuotationDocumentView.jsx';
import { QuotationAttachmentsLink, QuotationAttachmentsPanel } from './QuotationAttachmentsPanel.jsx';
import { copyFromQuotation, editorStateFromQuotation } from './quotationCopy.js';
import {
  adjustmentInputFromRow, ceoOriginalPriceText, emptyAdjustment, emptyPlainItem, emptyQuotationItem,
  itemInputFromRow, newItemClientId, QuotationItemRow,
} from './QuotationItemRow.jsx';
import { QuotationPlainItemRow } from './QuotationPlainItemRow.jsx';
import { QuotationAdjustmentRow } from './QuotationAdjustmentRow.jsx';
import {
  pushRecentCatalogPick, readQuotationDefaults, readRecentCatalogPicks, writeQuotationDefaults,
} from './quotationPrefs.js';
import { ThaiDateInput } from '../../components/common/ThaiDateInput.jsx';

// Bangkok LOCAL calendar date, not `new Date().toISOString().slice(0, 10)`: that reads the UTC day,
// one day BEHIND Bangkok (UTC+7) for any session open 00:00-06:59 Bangkok time. See bangkokTodayIso
// (utils/format.js).
function todayIso() {
  return bangkokTodayIso();
}

// Computed-only fields calculate-line ever returns (DealQuotationDtos.ItemDto's own tail, per
// QUOTATION-V2-PLAN.md). #H2: merging the response into a row must pick ONLY these -- never an
// input field (the response echoes the request's inputs back), and never `id`/`seq`.
const CALCULATED_ITEM_FIELDS = [
  'piecesPerSqm', 'piecesBeforeWastage', 'piecesAfterWastage', 'piecesFinal', 'boxes',
  'netUnitPrice', 'lineAmount', 'descriptionLine', 'sizeLine', 'calculationLine',
  // v3: the ราคาพิเศษ sub-line a SPECIAL_SQM tile prints. NOT `quantity`/`unit`: on a PLAIN row
  // those are rep-typed INPUTS the response merely echoes, so merging them would be #H2's clobber.
  'specialPriceLine',
];

// A stable empty-object reference for an untouched row's `errors` prop -- avoids handing
// QuotationItemRow a fresh `{}` literal every render (new reference each time otherwise, though
// this component doesn't rely on it for memoisation today; cheap to keep stable regardless).
const EMPTY_ITEM_ERRORS = {};

// Autosave debounce: long enough that it never fires mid-word, short enough that a rep who closes
// the tab loses at most one field.
export const AUTOSAVE_DELAY_MS = 2000;

// Like CALCULATED_ITEM_FIELDS above for the calculate-line response, the SAVE response
// (mergeServerItemFields, below) merges ONLY fields no editable control on this page writes, so a
// save response landing while the rep is typing can never clobber the edit. `id` and the two link
// ids are identity, never user input; the six are the CEO-comparison fields, refreshed without a
// reload. Not a superset of CALCULATED_ITEM_FIELDS, which refresh via the calculate-line path.
const SERVER_COMPUTED_ITEM_FIELDS = [
  'id', 'pricingRequestItemId', 'pricingDecisionItemId',
  'priceChangedFromCeo', 'ceoListUnitPrice', 'ceoDiscountPct', 'ceoSpecialPriceSqm',
  'ceoDirectNetPrice', 'ceoNetUnitPrice',
];

function pickCalculatedFields(source) {
  const result = {};
  for (const key of CALCULATED_ITEM_FIELDS) {
    if (key in source) result[key] = source[key];
  }
  return result;
}

// The ONE input-named field a calculate-line response may write onto a row, and only in one
// situation.
//
// `unitPrice` is NOT in CALCULATED_ITEM_FIELDS: it is the box a rep types into on an English tile
// row and on every สินค้า/บริการอื่น row, and a response must not overwrite one of those. But on a
// tile row whose ราคาตั้ง is typed per ตร.ม. (`listPriceSqm`), nothing on the page writes `unitPrice`:
// it is the server's per-แผ่น derivation, and this response is where it comes from. `requested` is
// the row exactly as it was SENT (the caller has already discarded a superseded response), and it
// must be the ROW that is asked, not the payload: a ราคาสุทธิ row with ราคาตั้ง blank sends its net as
// `listPriceSqm` too, and the list price derived for THAT must not appear in a box the rep left empty.
function derivedUnitPrice(requested, documentLanguage, responseItem) {
  if (!usesListPriceSqm(requested, documentLanguage) || !responseItem || !('unitPrice' in responseItem)) return {};
  return { unitPrice: responseItem.unitPrice };
}

/** The money a preview fills in, blanked, for a row with nothing honest to preview or whose preview
 * the server refused. On a row priced per ตร.ม. that includes the per-แผ่น list price, itself a
 * derivation there (see derivedUnitPrice); left in place it would show what the PREVIOUS price came
 * to under a box that now says something else. */
function withoutPreviewedMoney(row) {
  return {
    netUnitPrice: null, lineAmount: null,
    ...(hasTypedPrice(row.listPriceSqm) ? { unitPrice: null } : {}),
  };
}

// `defaults` is readQuotationDefaults()'s own return value (quotationPrefs.js) -- the rep's last
// saved terms, or null. Only ever seeds a BRAND-NEW quotation; an existing one always loads its
// own stored values (see the init effect below), so a remembered default can never overwrite what
// is on the server. วันที่ is deliberately never remembered: it is always today.
function emptyTerms(defaults = null) {
  const depositPercent = defaults?.depositPercent ?? '';
  // noDeposit must NEVER be seeded from a remembered default, unlike depositPercent/validityMode
  // above: ticking it once would make every LATER new quotation start with no deposit, and a rep
  // could send one by accident. A brand-new quotation always starts with the normal deposit
  // controls. (quotationPrefs.js doesn't carry either field, but this stays explicit rather than
  // reading `defaults?.noDeposit`, in case a stale stored blob has the key.)
  const noDeposit = false;
  return {
    unitCode: '', offerDate: todayIso(),
    depositPercent,
    depositPercentCustom: depositPercent !== '' && !DEPOSIT_PERCENT_PRESETS.includes(Number(depositPercent)),
    noDeposit,
    fullPaymentTerm: '',
    remainderMode: defaults?.remainderMode ?? '',
    creditDays: defaults?.creditDays ?? '',
    validityDays: defaults?.validityDays ?? '',
    // V178: the MODE remembers (see quotationPrefs.js); validityUntil never does — a brand-new
    // quotation always starts with no date typed, even when it starts in DATE mode.
    validityMode: defaults?.validityMode ?? 'DAYS',
    validityUntil: '',
    customerNotes: '',
    // ผู้พิมพ์/พนักงานขาย print-name override. Not remembered (unlike validityMode above): the
    // override is deal/document-specific, so a brand-new quotation starts with "(ค่าเริ่มต้น)".
    printedByDisplayId: '',
    salesRepDisplayId: '',
    // A brand-new /quotations/new visit has no ticket yet, so this starts blank; the ?ticket= path
    // below fills it from the deal's own project name.
    projectName: '',
    // "ไม่เติม “คุณ” หน้าชื่อผู้ติดต่อ", the checkbox under the ผู้ติดต่อ picker. Not remembered: it is
    // about this document's contact.
    omitContactHonorific: false,
    // V192.3 — the CUSTOMER's phone and email: printed on the document when filled. Not remembered.
    orderedByPhone: '',
    orderedByEmail: '',
    // "ไม่แสดงชื่อโครงการในใบเสนอราคา". Not remembered: whether THIS document names its project is a
    // fact about this customer and deal, so a new quotation starts unticked (the project prints).
    hideProjectName: false,
  };
}

// The "ลูกค้าและโครงการ" card state for a brand-new /quotations/new visit with no `?ticket=`
// (inline-deal-spec.md): nothing here exists on the server yet, unlike `terms`/`items`. ผู้ติดต่อ is
// NOT here: it is picked per quotation (the `contact` state), never on the deal.
function emptyDealForm() {
  return { customer: null, project: null, entryChannel: 'UNSPECIFIED' };
}

// The two per-QUOTATION choices. Language first, because it constrains the price modes on offer.
// Always sent explicitly on create AND update: DealQuotationService keeps the stored value when
// either is missing on an update, which is safe, but a UI relying on that would be one refactor away
// from silently repricing a document.
function defaultDocSettings() {
  return { priceMode: 'NET', documentLanguage: 'TH' };
}

/**
 * ใบเสนอราคา editor -- Quotation v2 (QUOTATION-V2-PLAN.md). One route (`/quotations/new?ticket=`
 * or `/quotations/:id`) that renders either the editable form (DRAFT + the viewer may edit) or a
 * read-only document summary (QuotationDocumentView) otherwise, with the action set the plan
 * specifies for the current status/role. Route-guarded by PATH_GUARDS' '/quotations' entry;
 * per-action gates below are quotationMeta.js predicates, re-checked server-side regardless.
 *
 * DEVIATION (less typing for sales, per the implementation brief): a brand-new quotation has no
 * id until the first "บันทึกร่าง" -- submit/approve/download are unavailable on /quotations/new
 * until that save creates the row and this page redirects to /quotations/:id. This is a plumbing
 * necessity (submit needs an id to act on), not an extra step: the very first click a sales rep
 * makes ("บันทึกร่าง") is the one that would have been required anyway.
 */
export function QuotationEditorPage({ user, showToast }) {
  const { id } = useParams();
  const [searchParams, setSearchParams] = useSearchParams();
  const ticketIdParam = searchParams.get('ticket');
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  // #M8: an approver's screen can go stale while they're reading it -- another approver (or the
  // same one, in another tab) may decide it first. `isApprover` is the ROLE-only gate
  // (canApproveDealQuotation, sales_manager/ceo) rather than canDecideDealQuotation, which also
  // needs a docStatus this query itself is what supplies -- refetchInterval's function form reads
  // the query's own last-known data instead, so this stays a plain boolean here.
  const isApprover = canApproveDealQuotation(user);
  const quotationQuery = useQuery({
    queryKey: queryKeys.dealQuotationDetail(id),
    queryFn: () => api.dealQuotations.get(id).then((r) => r.quotation),
    enabled: !!id,
    refetchOnWindowFocus: isApprover,
    // Poll every ~30s ONLY while there is a live decision to make (PENDING_APPROVAL) and this
    // user could be the one making it -- not on every quotation, and not once it's decided.
    refetchInterval: (query) => (isApprover && query.state.data?.docStatus === 'PENDING_APPROVAL' ? 30000 : false),
  });
  const quotation = quotationQuery.data ?? null;
  const effectiveTicketId = id ? quotation?.ticketId : (ticketIdParam ? Number(ticketIdParam) : null);

  // GET /api/tickets/{id} responds `TicketDetailResponse` (`{ ticket: TicketDto }`) and TicketDto is
  // itself the ENVELOPE (`{summary, items, events, quotation, quotations}`): TWO levels of wrapping.
  // `r.ticket?.summary` unwraps both (a bare `r.ticket` left every summary field this page needs
  // undefined, so canCreateDealQuotation(user, ticket) was false even for the deal's owner). This
  // page only needs summary fields, so it flattens directly.
  //
  // `enabled` covers an editable existing DRAFT (not just the ?ticket= path): the DealQuotationDto
  // snapshot has no `customerId`, which the customer-record lookup (address/tax id/phone autofill,
  // below) and the editable โครงการ field need, and only the ticket has it. Not fetched for a
  // read-only/non-DRAFT quotation: the document view prints the snapshot the DTO carries.
  //
  // The key carries its own 'summary' suffix: TicketDetailPage caches the WHOLE TicketDto under the
  // bare queryKeys.ticketDetail(id) and renders from it, so caching a bare summary there crashed the
  // deal page ("Cannot read properties of undefined (reading 'status')") when a rep followed เปิดดีล
  // from here. Same device promotionTicketQuery below uses; a prefix invalidation of
  // ticketDetail(id) (or ['tickets']) still reaches it.
  const ticketQuery = useQuery({
    queryKey: [...queryKeys.ticketDetail(effectiveTicketId), 'summary'],
    queryFn: () => api.tickets.get(effectiveTicketId).then((r) => r.ticket?.summary),
    enabled: !!effectiveTicketId && (!id || quotation?.docStatus === 'DRAFT'),
  });
  const ticket = ticketQuery.data ?? null;

  // V204 deal parties (deal-party-model.md). `?party=` names the contractor a NEW quotation is for
  // (the deal page's เสนอราคา link); an existing quotation carries its own partyId. The party's
  // customer, not the deal's, is who the document is addressed to and whose contacts are offered.
  const partyParam = !id ? searchParams.get('party') : null;
  const partiesQuery = useQuery({
    queryKey: queryKeys.ticketParties(effectiveTicketId),
    queryFn: () => api.tickets.parties(effectiveTicketId).then((r) => r?.parties ?? []),
    enabled: Boolean(effectiveTicketId) && Boolean(ticket) && ['sales', 'sales_manager', 'ceo'].includes(user?.role),
    retry: false,
  });
  const dealParties = partiesQuery.data;
  const namedParty = id
    ? (quotation?.partyId != null ? (dealParties ?? []).find((p) => Number(p.id) === Number(quotation.partyId)) ?? null : null)
    : (partyParam ? quotationTargetParty(dealParties, { partyId: partyParam }) : null);
  // Only a party whose customer differs from the deal's changes anything on this page.
  const partyCustomer = namedParty && Number(namedParty.customerId) !== Number(ticket?.customerId)
    ? { id: namedParty.customerId, name: namedParty.customerName }
    : null;
  // The customer record this page reads and edits: the party's, else the deal's.
  const recordCustomerId = partyCustomer?.id ?? ticket?.customerId ?? null;
  const recordCustomerName = partyCustomer ? partyCustomer.name : (ticket?.customerName ?? null);

  // "ยืนยันคำสั่งซื้อ". An APPROVED direct quotation's deal either still takes the order (offer the
  // confirm) or already has it (offer "ไปที่ดีล"). The discriminator is the backend's OWN
  // precondition: the deal's `status === 'draft'` (DealQuotationService#confirmOrderFromDirectQuotation
  // 409s otherwise, and confirming flips it to 'quotation_issued'). NOT `quotationOnly`: provenance
  // only, and false on every deal created on the deal page (flow B), which would hide the confirm on
  // an order never confirmed. Only fetched for a user who could confirm, under its OWN key suffix
  // (see ticketQuery above: the bare ticketDetail(id) key holds whichever shape was cached).
  // retry:false: a 403 (e.g. a quotation-grant holder whose role can't read tickets), or a summary
  // with no status, just means "unknown", and the button falls back to the confirm, which the server
  // answers idempotently.
  const canPromoteToDeal = canPromoteDealQuotationToDeal(user, quotation);
  const promotionTicketQuery = useQuery({
    queryKey: [...queryKeys.ticketDetail(quotation?.ticketId), 'promotionSummary'],
    queryFn: () => api.tickets.get(quotation.ticketId).then((r) => (r.ticket?.summary
      ? { ...r.ticket.summary, orderConfirmation: orderConfirmationOf(r.ticket.events) } : null)),
    enabled: canPromoteToDeal && quotation?.ticketId != null,
    retry: false,
  });
  const promotionDealStatus = promotionTicketQuery.data?.status ?? null;
  const promotedDealId = promotionDealStatus != null && promotionDealStatus !== 'draft' ? quotation?.ticketId ?? null : null;

  // The deal's customer MASTER row: on the ?ticket= and existing-draft paths the rep can edit ที่อยู่ /
  // เลขที่ผู้เสียภาษี / โทร. here, and the checklist needs the LIVE values (the quotation's own
  // columns are a snapshot as of its last save).
  //
  // ⚠️ There is no GET /api/customers/{id}; the only read is the name search (ORDER BY name LIMIT
  // 30, CustomerRepository.search). So this searches the deal's customer name and matches on id. If
  // the row is not in those 30 (a name that is a substring of 30+ others), it falls back to the
  // quotation's snapshot below: degraded, never wrong about which customer.
  const customerRecordQuery = useQuery({
    queryKey: queryKeys.customerRecord(recordCustomerId),
    queryFn: async () => {
      const res = await api.customers.search(recordCustomerName ?? '');
      return (res?.customers ?? []).find((c) => Number(c.id) === Number(recordCustomerId)) ?? null;
    },
    // `ticket` is null on the inline-create path (DealCustomerCard holds the full record there), so
    // this only ever runs on the ?ticket= and editable-draft paths.
    enabled: Boolean(recordCustomerId) && (!id || quotation?.docStatus === 'DRAFT'),
  });
  // What the rep just saved through CustomerDetailsFields, until the refetch catches up.
  const [customerOverride, setCustomerOverride] = useState(null);

  // The ผู้พิมพ์/พนักงานขาย print-name selector options. Gated like who may SET the fields
  // (canCreateDealQuotationStandalone: sales, sales_manager, or the canCreateQuotation grant;
  // role/grant-only, no ticket/quotation needed), matching
  // DealQuotationService#findQuotationDisplayNameOptions. Every render branch of the two selectors
  // is already inside `isEditable`, which implies this set, so no render-time role check.
  const displayNameOptionsQuery = useQuery({
    queryKey: queryKeys.dealQuotationDisplayNameOptions(),
    queryFn: () => api.dealQuotations.displayNameOptions().then((r) => r.items ?? []),
    enabled: canCreateDealQuotationStandalone(user),
  });
  const displayNameOptions = displayNameOptionsQuery.data ?? [];
  // เจ้าของงาน (2026-11-05): who owns the deal this inline create makes. Defaults to the signed-in
  // user; ownerEmployeeId is sent only when it names someone else.
  const [dealOwnerId, setDealOwnerId] = useState('');
  const effectiveDealOwnerId = dealOwnerId || (user?.id != null ? String(user.id) : '');

  // /quotations/new with NEITHER an :id NOR a ?ticket= is a brand-new deal that exists nowhere yet:
  // the rep picks/creates the customer and project right here. Stable per-render (not stateful): it
  // describes the URL the page was loaded with.
  const isInlineCreate = !id && !ticketIdParam;

  // Step 1 is "ดีล". `dealModeChoice` is the rep's pick between เลือกดีลที่มีอยู่ and สร้างดีลใหม่; a
  // `?ticket=` in the URL IS the picked-deal branch (DealPicker only sets that param), so the URL
  // wins over the stored choice. `needsDeal` is the one new-page state with no deal to hang the
  // quotation on. A "คัดลอกเป็นใบใหม่" visit (?copyFrom=) is for ANOTHER customer on a NEW deal, so it
  // opens on สร้างดีลใหม่. Null until the rep chooses. Derived, not initialised: this page instance
  // survives the move from /quotations/:id to /quotations/new?copyFrom=.
  const [dealModeChoice, setDealModeChoice] = useState(null);
  const dealMode = ticketIdParam ? 'pick' : (dealModeChoice ?? (searchParams.get('copyFrom') ? 'create' : 'pick'));
  const needsDeal = isInlineCreate && dealMode === 'pick';
  // One pricing route per deal: only a NEW quotation on a picked deal (?ticket=) asks; an existing
  // quotation is already on its route. Same query as DealPicker's notice.
  const dealHasLivePricingRequest = useDealHasLivePricingRequest(
    ticketIdParam ? Number(ticketIdParam) : null,
    !id && Boolean(ticketIdParam),
  );
  // N6 over EVERY live direct quotation: the summary serves only the newest, the server checks them all. The
  // deal page's own list query (same key); a role that cannot read it (403) falls back to the summary's row.
  const dealRowsQuery = useQuery({
    queryKey: queryKeys.dealQuotationsByTicket(Number(ticketIdParam)),
    queryFn: () => api.dealQuotations.listForTicket(Number(ticketIdParam)).then((r) => r?.items ?? []),
    enabled: !id && Boolean(ticketIdParam),
    retry: false,
  });
  function changeDealMode(next) {
    setDealModeChoice(next);
    if (next === 'create' && ticketIdParam) {
      setSearchParams((prev) => {
        const params = new URLSearchParams(prev);
        params.delete('ticket');
        return params;
      }, { replace: true });
    }
  }

  // ผู้รับใบเสนอราคา. '' = not chosen. Seeded from the stored row on an existing quotation, and on the
  // ?ticket= path preselected from the deal's own stage until the rep touches it (see the effect below).
  const [recipientType, setRecipientType] = useState('');
  const { catalog: stageCatalog, isLoading: stageCatalogLoading } = useStageCatalog();
  const recipientTouchedRef = useRef(false);
  // Once-per-ticket seed guards for the ?ticket= path (โครงการ and ผู้รับ — see their effects below).
  // Declared here, ABOVE the main seeding effect, because that effect clears them when the rep picks
  // a different deal through DealPicker.
  const projectNameSeededForTicket = useRef(null);
  const recipientSeededForTicket = useRef(null);
  function changeRecipient(code) {
    recipientTouchedRef.current = true;
    setRecipientType(code);
    setDirty(true);
  }

  // The rep's own remembered defaults / recent picks (quotationPrefs.js). Read ONCE, lazily, so a
  // browser that throws on `localStorage` (private window, blocked site data) throws inside the
  // module's own try/catch during the initial state computation and never on a later render.
  const [storedDefaults] = useState(() => readQuotationDefaults(user?.id));
  const [recentPicks, setRecentPicks] = useState(() => readRecentCatalogPicks(user?.id));

  // ── "คัดลอกเป็นใบใหม่" — /quotations/new?copyFrom=<quotation id> ──────────────────────────────
  // The same job quoted to another bidder: this is the ordinary new-quotation form, opened already
  // filled in from a saved quotation. Nothing is written here; the first บันทึกร่าง is the ordinary
  // inline create below, so the copy gets its own deal and its own number and the server never
  // learns the two documents are related.
  //
  // Honoured on the inline-create entry only: a copy is FOR another customer, and a `?ticket=`
  // visit already has its customer. The source is read through the same GET (and the same cache
  // entry) as its own page, so the server decides whether this viewer may read it — and it is not
  // even asked for a viewer who may not start a quotation, who gets the refusal further down.
  const copyFromId = isInlineCreate ? searchParams.get('copyFrom') : null;
  const copySourceAsked = Boolean(copyFromId) && canCreateDealQuotationStandalone(user);
  const copySourceQuery = useQuery({
    queryKey: queryKeys.dealQuotationDetail(copyFromId),
    queryFn: () => api.dealQuotations.get(copyFromId).then((r) => r.quotation),
    enabled: copySourceAsked,
  });
  const copySource = copySourceQuery.data ?? null;
  // Asked for and not answered yet. `isPending`, not `isLoading`: a request that is held back
  // (offline, or between retries while the tab is hidden) is not fetching, and it is not an answer
  // either. A form shown then would be typed into and replaced the moment the source arrives.
  const copySourcePending = copySourceAsked && copySourceQuery.isPending;
  // What the form opens with — or null while the source is on its way, or when it may not be
  // copied (canCopyDealQuotation: the SAME question the button on the source's page asks, because
  // a hidden button does not stop a typed, bookmarked or shared link).
  const copyState = useMemo(
    () => (copySource && canCopyDealQuotation(user, copySource)
      ? copyFromQuotation(copySource, emptyTerms(storedDefaults)) : null),
    [copySource, user, storedDefaults],
  );
  // Why nothing was copied, when the link asked for a copy and there is none: the actual reason,
  // never a guess. An unreadable or missing source says what the server said; a source of the wrong
  // kind says which kind. (A viewer who may not create quotations is told by the page's own refusal
  // further down.)
  let copyRefusal = null;
  if (copyFromId && !copyState && !copySourcePending) {
    if (copySourceQuery.isError) {
      copyRefusal = copySourceQuery.error?.message || 'ไม่พบใบเสนอราคาที่จะคัดลอก';
    } else if (copySource?.origin === 'PRICING_REQUEST') {
      copyRefusal = `ใบเสนอราคา ${copySource.number} ออกจากคำขอราคา ราคาในใบเป็นราคาที่ CEO อนุมัติสำหรับคำขอนั้น จึงคัดลอกไปเสนอลูกค้ารายอื่นไม่ได้`;
    } else if (copySource && copySource.origin !== 'DEAL_DIRECT') {
      copyRefusal = `ใบเสนอราคา ${copySource.number} เป็นประเภทที่คัดลอกเป็นใบใหม่ไม่ได้`;
    }
  }

  const [dealForm, setDealForm] = useState(emptyDealForm);
  // ผู้ติดต่อ: a contact OBJECT of the deal's customer, or null (the QuotationContactPicker value
  // contract). Sent as `contactId`; it prints after "เรียน", never in the ผู้สั่งซื้อ slot. The ref is
  // for flushPendingContactSave (submit and download).
  const [contact, setContact] = useState(null);
  const contactPickerRef = useRef(null);
  function updateDealForm(patch) {
    // The ดีล card reports ผู้รับ through the same patch-upward channel; it is quotation state, not
    // deal-form state, so it is split out to its own setter.
    const { recipientType: nextRecipient, ...dealPatch } = patch;
    if (nextRecipient !== undefined) changeRecipient(nextRecipient);
    // On the new-deal card the channel pre-fills ผู้รับ (DESIGNER_LED → ผู้ออกแบบ, OWNER_DIRECT →
    // เจ้าของโครงการ, BUYER_DIRECT → ผู้ซื้อ / ผู้รับเหมา) and a later channel change re-fills it, but
    // ONLY while the rep has not picked a recipient. A pre-fill is not a pick: it leaves
    // recipientTouchedRef alone, so it never outranks a deliberate choice.
    if (dealPatch.entryChannel !== undefined && !recipientTouchedRef.current) {
      setRecipientType(recipientForEntryChannel(dealPatch.entryChannel));
    }
    if (Object.keys(dealPatch).length === 0) return;
    // A contact belongs to one customer (the server 400s another customer's), so a different
    // customer drops it. An edit of the same customer's ที่อยู่ also patches `customer`, so ids.
    if ('customer' in dealPatch && String(dealPatch.customer?.id) !== String(dealForm.customer?.id)) setContact(null);
    setDealForm((prev) => ({ ...prev, ...dealPatch }));
    setDirty(true);
  }
  // The deals the inline flow's own `tickets.create` made on this visit, by the customer, project and
  // channel each was made for. Kept across a later dealQuotations.create failure so a retry reuses that
  // ticket instead of minting a second deal (see handleInlineCreate).
  const [createdTicketIds, setCreatedTicketIds] = useState({});
  const [creatingDeal, setCreatingDeal] = useState(false);

  // ── "โครงการนี้มีดีลของคุณอยู่แล้ว" (deal-party-model.md, prototype screen 4) ─────────────────────
  // On a NEW deal, once customer and project are both chosen: does this rep already have an open,
  // pre-outcome deal on that project? If so the default is to add the customer to it as another
  // contractor (a party) instead of minting a second deal for the same opportunity.
  const openDealsQuery = useQuery({
    queryKey: queryKeys.openDealsForProject(dealForm.project?.name),
    queryFn: () => api.tickets.openForProject(dealForm.project.name).then((r) => r?.deals ?? []),
    enabled: isInlineCreate && dealMode === 'create' && Boolean(dealForm.customer) && Boolean(dealForm.project?.name),
    retry: false,
  });
  const openDeals = isInlineCreate && dealMode === 'create' && dealForm.customer && dealForm.project
    ? (openDealsQuery.data ?? []) : [];
  const existingDeal = openDeals[0] ?? null;
  const [attachChoice, setAttachChoice] = useState('existing');
  const attachingToDeal = Boolean(existingDeal) && attachChoice === 'existing';
  // The party each attach made on this visit (by deal and customer), so a retry after a failed
  // quotation create reuses it.
  const [attachedPartyIds, setAttachedPartyIds] = useState({});

  const [items, setItems] = useState([]);
  // v3: ส่วนลดพิเศษ rows live in their OWN ordered list, not in `items`. They always print LAST
  // (DealQuotationService#buildItems moves every ADJUSTMENT to the end), and they belong to no
  // ตำแหน่งติดตั้ง group — keeping them out of `items` means the group contiguity invariant above
  // never has to reason about a row with no group, and the payload is simply items + adjustments,
  // which is already the server's printed order.
  const [adjustments, setAdjustments] = useState([]);
  // Files picked in the เอกสารแนบ panel before the quotation has ever been saved (File[]).
  const [pendingAttachments, setPendingAttachments] = useState([]);
  const [docSettings, setDocSettings] = useState(defaultDocSettings);
  // A mode/currency change the rep did not directly choose (e.g. SPECIAL_SQM moved off when the
  // document became English) is SAID, persistently, until the next settings change.
  const [settingsNotice, setSettingsNotice] = useState(null);
  // V192.5 — leaving "ผสมหลายวิธี" for one mode re-prices every row that is in another, so the rep
  // is asked first. `{ priceMode, count }` while the question is up (count = rows it would
  // re-price), null otherwise. See #changePriceMode.
  const [leaveMixedConfirm, setLeaveMixedConfirm] = useState(null);
  // The last save's refusal, kept on screen (a toast vanishes). A 400 like "ยอดรวมหลังหักส่วนลด
  // พิเศษติดลบ" is exactly the kind of error that must not be swallowed or scroll away.
  const [saveError, setSaveError] = useState(null);
  // The live direct quotation a refused create named, if any.
  const [saveErrorLiveQuotationId, setSaveErrorLiveQuotationId] = useState(null);
  // ตำแหน่งติดตั้ง groups, ordered. `items` stays the single flat, ordered list; see
  // locationGroupsFromItems for the contiguity invariant every mutation below maintains, which lets
  // buildUpsertPayload save with no re-sorting.
  const [groups, setGroups] = useState(() => [{ groupId: newLocationGroupId(), label: '' }]);
  const [terms, setTerms] = useState(() => emptyTerms(storedDefaults));
  const [dirty, setDirtyState] = useState(false);
  // A monotonically increasing counter of "the form changed" events, bumped on every setDirty(true)
  // (once per genuine edit, never per render). A save's onSuccess captures it when it BUILT its
  // request (see buildSaveRequest); if it moved by the time the response lands, an edit happened
  // WHILE the request was in flight and setDirty(false) must not run, or that edit would never get
  // its own save (autosave only fires while dirty, and submit's pre-save only `if (dirty)`). A ref,
  // not state: only compared for equality, never rendered.
  const editSeqRef = useRef(0);
  const setDirty = useCallback((next) => {
    if (next) editSeqRef.current += 1;
    setDirtyState(next);
  }, []);
  // A "latest value" mirror of `dirty`, kept current by an effect (react-hooks/refs forbids writing a
  // ref's `.current` during render). handleDownload is async and awaits several times; reading the
  // `dirty` closure there would read what it was when the button was CLICKED, stale the moment an
  // edit lands during an await. `dirtyRef.current` always sees the live value.
  const dirtyRef = useRef(dirty);
  useEffect(() => { dirtyRef.current = dirty; }, [dirty]);
  // ระบุวันที่ is offered only on a document with special pricing; mirrors
  // DealQuotationRenderAdapter#hasSpecialPricing (quotationMeta.js).
  const docHasSpecialPricing = useMemo(
    () => hasSpecialPricing(docSettings.priceMode, [...items, ...adjustments]),
    [docSettings.priceMode, items, adjustments],
  );
  // If a discount is cleared, an adjustment row removed, or the price mode changed so the
  // document no longer has special pricing WHILE ระบุวันที่ is selected, switch back to
  // จำนวนวัน automatically — so the next save sends validityMode DAYS rather than 400ing on a
  // mode the document no longer qualifies for.
  useEffect(() => {
    if (!docHasSpecialPricing && terms.validityMode === 'DATE') {
      setTerms((t) => ({ ...t, validityMode: 'DAYS' }));
      setDirty(true);
    }
  }, [docHasSpecialPricing, terms.validityMode, setDirty]);
  // 0% is not a typeable percentage: the custom "อื่นๆ" input points the rep at the "ไม่รับมัดจำ"
  // checkbox. This message alone is only VISUAL: `onChange` writes whatever is typed, and
  // DealQuotationService#submit's isZeroDeposit() gate refuses depositPercent === 0 identically
  // whether it came via this field or the checkbox. #hasUnresolvedZeroDeposit a few lines down is the
  // ACTUAL guard that blocks ส่งขออนุมัติ; this `depositZeroError` is only an inline hint.
  const depositZeroError = !terms.noDeposit && terms.depositPercentCustom
    && terms.depositPercent !== '' && Number(terms.depositPercent) === 0
    ? 'ถ้าไม่รับมัดจำ ให้ติ๊ก “ไม่รับมัดจำ” แทนการพิมพ์ 0'
    : undefined;
  const [initializedFor, setInitializedFor] = useState(null);
  // Which rows show their per-field inline red hints yet. A row seeded from the server (an existing
  // DRAFT the rep reopened) is touched immediately: they need to see what is missing right away. A
  // row freshly created by "เพิ่มรายการ" starts UNtouched, so a blank row doesn't show eight red
  // messages before anything is typed; updateItem adds its clientId on the first edit. The top-of-form
  // per-row "รายการที่ N: ขาด ..." summary is NOT gated on this (see validationErrors below): it always
  // reflects every row, since it explains the disabled บันทึกร่าง/ส่งขออนุมัติ buttons.
  const [touchedRowIds, setTouchedRowIds] = useState(() => new Set());
  const calcTimers = useRef({});
  // #H2: a per-row request sequence. Bumped once per `updateItem` call (i.e. once per debounce
  // restart, not once per fired request); a response is applied only if it still matches the
  // LATEST sequence issued for that row at the time it settles, so an older, slower response
  // arriving after a newer one can never clobber it -- see the response handler below.
  const calcSeq = useRef({});

  // Seeds local editable state from the server exactly once per quotation/ticket; after that the
  // user's own edits are the source of truth until the next save round-trips a fresh copy.
  useEffect(() => {
    const key = id ? `q-${id}` : copyFromId ? `c-${copyFromId}` : `t-${effectiveTicketId ?? ''}`;
    if (initializedFor === key) return;
    // The document being edited is five pieces of state, the shape
    // quotationCopy#editorStateFromQuotation returns. Every branch below seeds all five through
    // this one function, then sets what is its own: which rows count as touched, whether the form
    // starts dirty, and the settings notice.
    const applyEditorState = (state) => {
      setGroups(state.groups);
      setItems(state.items);
      setAdjustments(state.adjustments);
      setDocSettings(state.docSettings);
      setTerms(state.terms);
      setContact(state.contact ?? null);
      setPendingAttachments([]);
    };
    if (id) {
      if (!quotation) return;
      // The DTO as editor state — see quotationCopy#editorStateFromQuotation, which the copy path
      // shares, for how each part is read. ผู้ติดต่อ and its "คุณ" box are added here, not there: a
      // copy is for another customer, so it carries neither. The contact is seeded from the frozen
      // snapshot, the picker's `{ id, firstName }` stand-in shape (see its header).
      const state = editorStateFromQuotation(quotation);
      applyEditorState({
        ...state,
        terms: { ...state.terms, omitContactHonorific: quotation.omitContactHonorific === true },
        contact: quotation.contactId
          ? {
            id: quotation.contactId, firstName: quotation.contactName ?? '', lastName: '',
            phone: quotation.contactPhone, email: quotation.contactEmail,
          }
          : null,
      });
      setSettingsNotice(null);
      setTouchedRowIds(new Set(quotation.items.map((it) => it.id)));
      // The stored recipient. UNSPECIFIED (every legacy direct row) reads as "not chosen yet": the
      // choice is never invented for the rep.
      setRecipientType(quotationRecipientOption(quotation.recipientType) ? quotation.recipientType : '');
      recipientTouchedRef.current = false;
      setDirty(false);
      setInitializedFor(key);
    } else if (copyFromId) {
      // "คัดลอกเป็นใบใหม่". Nothing is seeded until the source has arrived AND may be copied; a
      // refused copy leaves the blank new-quotation form exactly as it is.
      if (!copyState) return;
      // One batch, so the "ระบุวันที่ needs special pricing" effect above sees the copied rows and
      // the copied validity mode together and doesn't flip a dated validity back to days.
      applyEditorState(copyState);
      setSettingsNotice(null);
      // Touched, like a duplicated row: a copy of an incomplete row shows what it still lacks.
      setTouchedRowIds(new Set([...copyState.items, ...copyState.adjustments].map((row) => row.clientId)));
      // This page instance survives /quotations/:id -> /quotations/new?copyFrom=, so what the earlier
      // visit left in its state is cleared: a copy is for another customer on a NEW deal, so no
      // recipient, no deal choice and no customer/project are carried over. (The copy's own project
      // name is offered by DealCustomerCard once the new customer is chosen, so it is unaffected.)
      setRecipientType('');
      recipientTouchedRef.current = false;
      setDealModeChoice(null);
      setDealForm(emptyDealForm());
      // Unsaved work from the first moment — which is also what puts the checklist (customer,
      // project) on screen at once rather than after the first keystroke.
      setDirty(true);
      setInitializedFor(key);
    } else if (effectiveTicketId) {
      // Picking a deal (DealPicker) or switching to another only swaps the URL's ?ticket=: the rep's
      // half-built items and terms stay put, and only the deal-derived bits (โครงการ, and ผู้รับ unless
      // the rep chose it) re-seed from the newly picked deal via the two effects below. A first
      // visit has nothing to keep, so it does the full reset below (as does anything arriving from an
      // existing quotation).
      if (initializedFor === null || initializedFor.startsWith('t-') || initializedFor.startsWith('c-')) {
        setTerms((t) => ({ ...t, projectName: '' }));
        setContact(null); // picked for the previous deal's customer
        projectNameSeededForTicket.current = null;
        recipientSeededForTicket.current = null;
        setInitializedFor(key);
        return;
      }
      applyEditorState({
        items: [],
        adjustments: [],
        docSettings: defaultDocSettings(),
        groups: [{ groupId: newLocationGroupId(), label: '' }],
        terms: emptyTerms(storedDefaults),
      });
      setTouchedRowIds(new Set());
      setDirty(false);
      setInitializedFor(key);
    }
    // setDirty is a useCallback with an empty dep list (stable identity), so listing it here can't
    // cause an extra run; it only satisfies exhaustive-deps.
  }, [id, quotation, effectiveTicketId, initializedFor, storedDefaults, setDirty, copyFromId, copyState]);

  // The main seeding effect marks `initializedFor` on the FIRST render of a `?ticket=` visit, before
  // `ticketQuery` resolves, so seeding `projectName` from `ticket.projectName` there would seed from
  // `undefined` and the guard would make the ticket dependency inert (a deal that already HAS a
  // project name rendered โครงการ empty). A separate ref, firing once per ticket id once `ticket`
  // resolves, functionally-updates `terms` instead of racing the main effect's own `setTerms`.
  //
  // โครงการ is a plain, always-enabled `<input>`, so a rep who starts typing during the query window
  // could be clobbered when this fires. Guard by checking the CURRENT field value inside the updater,
  // not just the ref: only seed when the rep hasn't put something there. (projectNameSeededForTicket
  // is declared with the other refs above the main seeding effect, which resets it when the rep picks
  // a different deal.)
  useEffect(() => {
    if (id || !ticket?.id || projectNameSeededForTicket.current === ticket.id) return;
    projectNameSeededForTicket.current = ticket.id;
    if (ticket.projectName) {
      setTerms((t) => (t.projectName ? t : { ...t, projectName: ticket.projectName }));
    }
  }, [id, ticket]);

  // Flow B's preselect: a deal already at a quote stage (S4 / S5 / S8) names its own recipient; any
  // other stage leaves the choice empty so the rep is asked, never guessed for. Same once-per-ticket
  // shape as the โครงการ seed above, and never over a choice the rep already made. When the stage is
  // not a quote stage, the deal's own entry channel names the recipient (UNSPECIFIED names none, so
  // the rep is still asked).
  // Never an off-route party (owner ruling 2026-11-07: the server 409s it): each hint that names one falls through
  // to the next, and the channel's own party is on its route by construction. Waits for the served route while it
  // loads; if it never loads there is no route to filter by, and the hints stand.
  useEffect(() => {
    if (id || !ticket?.id || recipientSeededForTicket.current === ticket.id || stageCatalogLoading) return;
    recipientSeededForTicket.current = ticket.id;
    if (recipientTouchedRef.current) return;
    const requested = searchParams.get('recipient');
    const offRoute = offRoutePartiesFor(stageCatalog, ticket.entryChannel);
    setRecipientType([
      quotationRecipientOption(requested) && requested,
      recipientForDealStage(ticket.salesStage),
      recipientForEntryChannel(ticket.entryChannel),
    ].find((code) => code && !offRoute?.has(code)) || '');
  }, [id, ticket, searchParams, stageCatalog, stageCatalogLoading]);

  function updateItem(clientId, patch) {
    setDirty(true);
    // First real edit of this row -- flip on its inline red hints (see touchedRowIds' own comment
    // above). A no-op setState once already touched (Set membership check first) rather than a
    // fresh Set on every keystroke.
    setTouchedRowIds((prev) => (prev.has(clientId) ? prev : new Set(prev).add(clientId)));
    // The setItems updater is a PURE `prev.map(...)`: React StrictMode double-invokes updaters to
    // catch impurity, and scheduling the debounced calculate-line call from inside it could schedule
    // it twice.
    setItems((prev) => prev.map((it) => (it.clientId === clientId ? { ...it, ...patch, calcPending: true } : it)));

    // Read the row's current (already-patched) input fields from the `items` closure -- by the
    // time this synchronous call returns, the setItems above has been applied (React 18 does not
    // defer a synchronous updater's application past this line in an event handler), so `items`
    // here is stale-by-one-render at worst and the merge below reconstructs the exact patched
    // shape anyway.
    const currentItem = items.find((it) => it.clientId === clientId);
    if (!currentItem) return; // row was removed (e.g. by a fast double-click on ลบรายการ)
    const target = { ...currentItem, ...patch };
    // V192.5: previewed in the mode THIS row is in — the document's, or on a mixed document its own.
    scheduleCalc(clientId, target, rowPriceMode(docSettings.priceMode, target), docSettings.documentLanguage,
      isMixedPriceMode(docSettings.priceMode));
  }

  /** The debounced calculate-line call for one row, in the price mode THAT ROW is in (which
   * decides which mode-specific price itemInputFromRow sends — see its ⚠️ note): the quotation's,
   * or on a mixed document the row's own, in which case `mixed` makes the request STATE it. Split
   * out of updateItem so a price-mode switch can re-preview every tile row through the same path. */
  function scheduleCalc(clientId, target, priceMode, documentLanguage, mixed = false) {
    clearTimeout(calcTimers.current[clientId]);
    const seq = (calcSeq.current[clientId] = (calcSeq.current[clientId] ?? 0) + 1);
    // A row missing the price its mode needs has nothing honest to preview (quotationMeta
    // #rowHasPriceForPreview: calculate-line infers the mode FROM THE ROW, so it would come back
    // priced under another mode). Show no money until the rep types the price, which also keeps rows
    // cleared by a language switch from reappearing with a stale figure in the row or the totals.
    if (!rowHasPriceForPreview(target, priceMode, documentLanguage)) {
      setItems((cur) => cur.map((it) => (
        it.clientId === clientId ? { ...it, ...withoutPreviewedMoney(it), calcPending: false } : it
      )));
      return;
    }
    calcTimers.current[clientId] = setTimeout(() => {
      // The preview is computed in the DOCUMENT's language, so its lines (and, on English per-sqm, its
      // quantity) are what the saved document will print.
      api.dealQuotations.calculateLine(itemInputFromRow(target, priceMode, documentLanguage, { mixed }), documentLanguage)
        .then((res) => {
          // A newer edit superseded this request while it was on the wire: discard rather than let an
          // older, slower response overwrite what the user has typed since (the response echoes the
          // request's own input fields back).
          if (calcSeq.current[clientId] !== seq) return;
          setItems((cur) => cur.map((it) => (
            it.clientId === clientId
              ? {
                ...it, ...pickCalculatedFields(res.item ?? {}),
                ...derivedUnitPrice(target, documentLanguage, res.item), calcPending: false,
              }
              : it
          )));
        })
        .catch(() => {
          if (calcSeq.current[clientId] !== seq) return;
          // A refused preview CLEARS the money rather than leaving the last good figure up. A row the
          // server can't price as typed (a ราคาพิเศษ with no ตร.ม./แผ่น, a zero price) must not keep
          // showing an amount, least of all a net per piece from a previous price mode, which the
          // ราคาพิเศษ field would present as its own derivation.
          setItems((cur) => cur.map((it) => (
            it.clientId === clientId ? { ...it, ...withoutPreviewedMoney(it), calcPending: false } : it
          )));
        });
    }, 300);
  }

  // ── Document settings (v3/v3b) ────────────────────────────────────────────────────────────

  /** Switches the QUOTATION's tile price mode and re-previews every tile row in it. `prefill`
   * carries the net already on screen into ราคาสุทธิ when moving INTO DIRECT_NET — the rep typed
   * nothing and the figure does not change, which is the least-typing reading of "I want to state
   * the net directly". Never done across a currency change (see changeLanguage).
   *
   * The carried net is the per-แผ่น figure, kept per แผ่น (V192.2: the Thai box then SHOWS it per
   * ตร.ม., and says which figure it came from) — carrying it as a per-ตร.ม. number would send it
   * through the conversion and could move it by a satang. A row that already has a net typed per
   * ตร.ม. keeps that one instead. */
  function applyPriceMode(priceMode, {
    prefill = true, documentLanguage = docSettings.documentLanguage, clearPrices = false,
  } = {}) {
    // English per-sqm WITH a box area can't express a loose-piece quantity: the checkbox is disabled
    // AND renders unchecked for that combination (roundToFullBoxDisabledReason, QuotationItemRow), so
    // a row ticked under NET/TH before the switch would have no on-screen control to un-tick it, and
    // validateQuotationItem's checklist branch would block บันทึกร่าง/ส่งขออนุมัติ forever. Reset the
    // STORED flag to `true` the moment the document reaches this mode AND the row has a box area
    // (changePriceMode and changeLanguage both route through here), matching itemInputFromRow's
    // wire-level force. A row with NO box area keeps its roundToFullBox: that combination is legal,
    // and resetting would discard a rep's choice.
    const perSqm = isEnglishPerSqm(priceMode, documentLanguage);
    const next = (clearPrices ? rowsWithPricesCleared(items) : items).map((it) => {
      if (lineTypeOf(it) !== LINE_TYPE_TILE) return it;
      return {
        ...tileRowMovedToPriceMode(it, priceMode, prefill),
        // ONE mode for the whole document, so no row keeps a mode of its own (the only way out of a
        // mix; a no-op on a document that was never one).
        priceMode: null,
        ...(perSqm && Number(it.sqmPerBox) > 0 ? { roundToFullBox: true } : {}),
      };
    });
    setItems(next);
    setDirty(true);
    next.filter((it) => lineTypeOf(it) === LINE_TYPE_TILE).forEach((it) => scheduleCalc(it.clientId, it, priceMode, documentLanguage));
  }

  /** One tile row as it stands the moment it moves to `priceMode`: shared by a whole-document switch
   * (#applyPriceMode) and a single row's own switch (#changeRowPriceMode) so they can't treat a row
   * differently. Moving INTO ราคาสุทธิ carries the net already on screen (see #applyPriceMode's
   * Javadoc); the previous mode's derived money is dropped until the new preview lands, since a stale
   * net must never show under the new mode. */
  function tileRowMovedToPriceMode(row, priceMode, prefill = true) {
    const carried = prefill && priceMode === 'DIRECT_NET'
      && !hasTypedPrice(row.directNetPrice) && !hasTypedPrice(row.directNetPriceSqm)
      && row.netUnitPrice != null
      ? { directNetPrice: row.netUnitPrice } : {};
    return { ...row, ...carried, netUnitPrice: null, lineAmount: null, specialPriceLine: null, calcPending: true };
  }

  /** The "วิธีกรอกราคากระเบื้อง" picker. Three different moves, told apart:
   *
   *  - one mode → another: every tile row is re-asked and re-previewed in it;
   *  - any one mode → ผสมหลายวิธี: NOTHING is re-priced. Each row is given the mode the document was
   *    in as its own, and gains its picker, so trying the mix out costs nothing;
   *  - ผสมหลายวิธี → one mode: rows in another mode would be re-priced, so the rep is asked first
   *    (#leaveMixedConfirm), unless every row is already in that mode (nothing to lose). */
  function changePriceMode(priceMode) {
    if (priceMode === docSettings.priceMode) return;
    if (isMixedPriceMode(priceMode)) {
      const current = docSettings.priceMode;
      setItems((prev) => prev.map((it) => (lineTypeOf(it) === LINE_TYPE_TILE ? { ...it, priceMode: current } : it)));
      setDocSettings((prev) => ({ ...prev, priceMode: PRICE_MODE_MIXED }));
      setSettingsNotice(null);
      setDirty(true);
      return;
    }
    if (isMixedPriceMode(docSettings.priceMode)) {
      const inAnotherMode = items.filter((it) => (
        lineTypeOf(it) === LINE_TYPE_TILE && rowPriceMode(PRICE_MODE_MIXED, it) !== priceMode
      )).length;
      if (inAnotherMode > 0) {
        setLeaveMixedConfirm({ priceMode, count: inAnotherMode });
        return;
      }
    }
    applyOnePriceModeToTheDocument(priceMode);
  }

  function applyOnePriceModeToTheDocument(priceMode) {
    setLeaveMixedConfirm(null);
    setDocSettings((prev) => ({ ...prev, priceMode }));
    setSettingsNotice(null);
    applyPriceMode(priceMode);
  }

  /** One row's own "วิธีคิดราคา" on a mixed document: that row, and only that row, moves to
   * `priceMode` exactly as every row does on a whole-document switch, and is re-previewed stating
   * its mode. Marked touched so the new mode's still-missing price shows its hint at once. */
  function changeRowPriceMode(clientId, priceMode) {
    const current = items.find((it) => it.clientId === clientId);
    if (!current || rowPriceMode(PRICE_MODE_MIXED, current) === priceMode) return;
    const moved = { ...tileRowMovedToPriceMode(current, priceMode), priceMode };
    setItems((prev) => prev.map((it) => (it.clientId === clientId ? moved : it)));
    setTouchedRowIds((prev) => (prev.has(clientId) ? prev : new Set(prev).add(clientId)));
    setDirty(true);
    scheduleCalc(clientId, moved, priceMode, docSettings.documentLanguage, true);
  }

  /** The ONE choice that carries others with it: currency (TH→THB, EN→USD) and, on English, the loss
   * of SPECIAL_SQM. Every consequence the rep didn't pick is announced in `settingsNotice` rather
   * than applied silently. Typed prices are NOT converted: the server has no exchange rate for this
   * and neither should the editor, so the rep is told the same numbers will now print in the other
   * currency. */
  function changeLanguage(documentLanguage) {
    if (documentLanguage === docSettings.documentLanguage) return;
    const { priceMode, moved } = priceModeForLanguage(docSettings.priceMode, documentLanguage);
    // The editor has no exchange rate, so no typed amount is carried into the new currency as the
    // same number: every currency amount, in EVERY price mode, is cleared
    // (quotationMeta#rowsWithPricesCleared) and the rep re-enters it. Percentages, quantities and box
    // figures are kept. An EDITOR rule: the server can't tell a re-typed price from a stale one.
    const notices = [
      documentLanguage === 'EN'
        ? 'เปลี่ยนภาษาเอกสาร: ล้างราคาทั้งหมดแล้ว — กรุณากรอกราคาใหม่เป็น USD (ไม่มี VAT)'
        : 'เปลี่ยนภาษาเอกสาร: ล้างราคาทั้งหมดแล้ว — กรุณากรอกราคาใหม่เป็นบาท',
    ];
    if (moved && isMixedPriceMode(docSettings.priceMode)) {
      // V192.5: a mix is a Thai-document choice. Announced, never applied silently — every row is
      // about to be asked the same way, which the rep did not pick.
      notices.push('เอกสารภาษาอังกฤษกรอกราคาแบบ “ผสมหลายวิธี” ไม่ได้ — เปลี่ยนเป็น “ราคาตั้ง − ส่วนลด %” ทั้งใบให้แล้ว');
    } else if (moved) {
      notices.push('เอกสารภาษาอังกฤษใช้ "ราคาพิเศษ บาท/ตร.ม." ไม่ได้ (ราคานั้นรวม VAT แต่เอกสารภาษาอังกฤษไม่มี VAT) — เปลี่ยนวิธีกรอกราคาเป็น "ราคาสุทธิต่อแผ่น" ให้แล้ว กรุณากรอกราคาสุทธิของทุกรายการ');
    }
    setDocSettings({ priceMode, documentLanguage });
    setSettingsNotice(notices.length ? notices.join(' · ') : null);
    setDirty(true);
    // Every price is cleared (above), and every tile row is re-previewed in the NEW language, which
    // with its price blank shows no money until re-entered.
    setAdjustments((cur) => rowsWithPricesCleared(cur));
    applyPriceMode(priceMode, { prefill: false, documentLanguage, clearPrices: true });
  }

  // ── ส่วนลดพิเศษ rows (v3 S3) ──────────────────────────────────────────────────────────────

  function addAdjustment() {
    setDirty(true);
    // Prefilled with the ยืนราคา end date, so the normal case — "3% if ordered within the offer
    // period" — is one typed number. Blank when no ยืนราคา is chosen yet; the date is optional.
    const from = quotation?.quotationDate || todayIso();
    // V178: in DATE mode the ยืนราคา end date IS terms.validityUntil already — no arithmetic
    // needed (and none would be right, since a rep-typed date has no relation to `from`).
    const deadline = terms.validityMode === 'DATE'
      ? (terms.validityUntil || '')
      : (terms.validityDays ? addDaysIso(from, Number(terms.validityDays)) : '');
    const row = emptyAdjustment(deadline);
    setAdjustments((prev) => [...prev, row]);
    setTouchedRowIds((prev) => new Set(prev).add(row.clientId));
  }

  function updateAdjustment(clientId, patch) {
    setDirty(true);
    setAdjustments((prev) => prev.map((a) => (a.clientId === clientId ? { ...a, ...patch } : a)));
  }

  function removeAdjustment(clientId) {
    setDirty(true);
    setAdjustments((prev) => prev.filter((a) => a.clientId !== clientId));
  }

  // ── "Take me to what I just added" ──────────────────────────────────────────────────────────
  //
  // The "เพิ่ม…" actions sit at the BOTTOM of the list they append to. A new tile row is a tall
  // card, so without this the card pushes the button further down and the rep scrolls back up to find
  // the field they meant to type into.
  //
  // Each add records WHAT it added here; the effect resolves that to the DOM id the row rendered with
  // (ids carry the FLAT index, only known once insertIntoGroup has placed the row: a row added to
  // ตำแหน่ง 1 of 3 lands mid-list) and hands it to the same focusQuotationField the checklist uses. A
  // ref, not state: consumed exactly once, never causing a render. Only an explicit add sets it, so
  // loading a draft, a save round-trip, or a calculate-line response can never take the viewport.
  const pendingFocusRef = useRef(null);
  useEffect(() => {
    const pending = pendingFocusRef.current;
    if (!pending) return;
    let targetId = null;
    if (pending.groupId) {
      const groupIndex = groups.findIndex((g) => g.groupId === pending.groupId);
      if (groupIndex >= 0) targetId = `group-label-${groupIndex}`;
    } else {
      const index = items.findIndex((it) => it.clientId === pending.rowId);
      if (index >= 0) targetId = lineTypeOf(items[index]) === LINE_TYPE_PLAIN ? `plain-desc-${index}` : `model-${index}`;
    }
    // Not in this render yet — leave it pending for the render that does carry the new row.
    if (!targetId) return;
    pendingFocusRef.current = null;
    focusQuotationField(targetId);
  }, [items, groups]);

  function addPlainItem(targetGroupId) {
    setDirty(true);
    const groupId = targetGroupId ?? groups[groups.length - 1]?.groupId ?? null;
    // Built OUTSIDE the updater so its clientId is known here.
    const row = emptyPlainItem(groupId);
    pendingFocusRef.current = { rowId: row.clientId };
    setItems((prev) => insertIntoGroup(prev, row, groupId));
  }

  // ── ตำแหน่งติดตั้ง group operations ─────────────────────────────────────────────────────────
  //
  // Every one keeps the contiguity invariant: items of the same group are adjacent in `items`, in
  // group order. `insertIntoGroup` is the single place that decides WHERE a row lands, so add / move
  // / duplicate can't drift apart on that.

  /** Splices `row` in immediately after the LAST item already in `targetGroupId` (or, for a group
   * with no items yet, after the last item of the nearest preceding non-empty group) so the array
   * stays grouped in `groups` order without ever sorting it. */
  function insertIntoGroup(list, row, targetGroupId, afterClientId = null) {
    const next = [...list];
    if (afterClientId) {
      const at = next.findIndex((it) => it.clientId === afterClientId);
      if (at >= 0) { next.splice(at + 1, 0, row); return next; }
    }
    let lastIndex = -1;
    for (let i = 0; i < next.length; i += 1) if (next[i].groupId === targetGroupId) lastIndex = i;
    if (lastIndex >= 0) { next.splice(lastIndex + 1, 0, row); return next; }
    // Empty target group: land after everything belonging to groups ordered BEFORE it.
    const order = groups.map((g) => g.groupId);
    const targetOrder = order.indexOf(targetGroupId);
    let boundary = 0;
    for (let i = 0; i < next.length; i += 1) {
      if (order.indexOf(next[i].groupId) < targetOrder) boundary = i + 1;
    }
    next.splice(boundary, 0, row);
    return next;
  }

  function addItem(targetGroupId) {
    setDirty(true);
    const groupId = targetGroupId ?? groups[groups.length - 1]?.groupId ?? null;
    const row = {
      ...emptyQuotationItem(groupId, storedDefaults),
      // V192.5: on a mixed document a new row starts in the mode of the tile row before it — rows
      // are usually entered in runs of the same method — and in ราคาตั้ง − ส่วนลด % if it is the
      // first. On a single-mode document a row has no mode of its own.
      ...(isMixedPriceMode(docSettings.priceMode)
        ? { priceMode: rowPriceMode(PRICE_MODE_MIXED, items.filter((it) => lineTypeOf(it) === LINE_TYPE_TILE).at(-1)) }
        : {}),
    };
    pendingFocusRef.current = { rowId: row.clientId };
    setItems((prev) => insertIntoGroup(prev, row, groupId));
  }

  /** A new group is born BLANK, so adding one while an existing group is also blank would create two
   * groups the document can't tell apart (the renderer groups by runs of equal label, and both runs
   * are ''), which merge on the next reload. Refused with the reason: naming the existing one first
   * is the whole fix. */
  function addGroup() {
    const unnamedIndex = groups.findIndex((g) => !(g.label ?? '').trim());
    if (unnamedIndex >= 0) {
      showToast('error', 'กรุณาตั้งชื่อตำแหน่งติดตั้งที่ยังว่างก่อน มิฉะนั้นสองตำแหน่งจะรวมเป็นตำแหน่งเดียวกันในเอกสาร');
      // "เพิ่มตำแหน่ง" sits at the bottom of the list, so the unnamed ตำแหน่ง this refusal is about can
      // be a full screen above. Take the rep to the one field that fixes it.
      focusQuotationField(`group-label-${unnamedIndex}`);
      return;
    }
    setDirty(true);
    const groupId = newLocationGroupId();
    pendingFocusRef.current = { groupId };
    setGroups((prev) => [...prev, { groupId, label: '' }]);
  }

  function updateGroupLabel(groupId, label) {
    setDirty(true);
    setGroups((prev) => prev.map((g) => (g.groupId === groupId ? { ...g, label } : g)));
  }

  /** Only ever offered for an EMPTY group (see the render below): removing a group that still
   * holds items would silently delete them, and "ลบตำแหน่ง" does not read like "ลบรายการ". */
  function removeGroup(groupId) {
    setDirty(true);
    setGroups((prev) => (prev.length <= 1 ? prev : prev.filter((g) => g.groupId !== groupId)));
  }

  function moveItemToGroup(clientId, targetGroupId) {
    setDirty(true);
    setItems((prev) => {
      const row = prev.find((it) => it.clientId === clientId);
      if (!row) return prev;
      return insertIntoGroup(prev.filter((it) => it.clientId !== clientId), { ...row, groupId: targetGroupId }, targetGroupId);
    });
  }

  /** "ทำซ้ำรายการ" — within this group (lands directly under the original) or into another one
   * (lands at that group's end). The copy is a genuinely new row: fresh clientId, no server `id`,
   * and already TOUCHED, because a duplicate of an incomplete row should show what it is still
   * missing immediately rather than pretending to be a pristine blank. */
  function duplicateItem(clientId, targetGroupId) {
    setDirty(true);
    const copyId = newItemClientId();
    setItems((prev) => {
      const row = prev.find((it) => it.clientId === clientId);
      if (!row) return prev;
      const groupId = targetGroupId ?? row.groupId;
      const copy = { ...row, id: undefined, clientId: copyId, groupId };
      return insertIntoGroup(prev, copy, groupId, groupId === row.groupId ? clientId : null);
    });
    setTouchedRowIds((prev) => new Set(prev).add(copyId));
  }

  /** One click on a "ใช้ล่าสุด" chip or a typeahead result records the row for next time. Purely
   * best-effort: quotationPrefs swallows a storage throw and simply returns a list this component
   * still renders, so the chips degrade rather than the editor breaking. */
  function handleCatalogPicked(cat) {
    setRecentPicks(pushRecentCatalogPick(user?.id, cat));
  }

  function removeItem(clientId) {
    setDirty(true);
    setItems((prev) => prev.filter((it) => it.clientId !== clientId));
    clearTimeout(calcTimers.current[clientId]);
    // #H2 cleanup: a removed row's timer/sequence entries are otherwise never freed for the
    // life of the page -- a long editing session that adds and removes many rows leaks one
    // object-map entry per removed clientId in each ref.
    delete calcTimers.current[clientId];
    delete calcSeq.current[clientId];
    setTouchedRowIds((prev) => {
      if (!prev.has(clientId)) return prev;
      const next = new Set(prev);
      next.delete(clientId);
      return next;
    });
  }

  // Every pending debounce belongs to this component instance -- clear them all on unmount so a
  // calculate-line response can never land (and call setItems) after the page has navigated away.
  useEffect(() => () => {
    Object.values(calcTimers.current).forEach((timer) => clearTimeout(timer));
  }, []);

  const labelByGroupId = useMemo(
    () => new Map(groups.map((g) => [g.groupId, g.label])),
    [groups],
  );

  // The two states the flat `locationLabel` wire format can't round-trip (see quotationMeta.js for why
  // neither is fixable without a schema AND a renderer change). A duplicate label BLOCKS the save
  // (below); an empty group only warns, because it is a normal transient state between
  // "เพิ่มตำแหน่ง" and the first item in it.
  const duplicateGroupIds = useMemo(() => duplicateLocationLabelGroupIds(groups), [groups]);
  const emptyGroupIds = useMemo(() => emptyLocationGroupIds(groups, items), [groups, items]);

  // Live sums off `items`: what the SERVER would compute once saved (WastageCalculator lives only in
  // the backend, so this is an estimate, labelled as one below), shown only while there is nothing
  // authoritative yet (no saved quotation, or unsaved edits on top of one). `lineAmount` is null
  // until a row's first calculate-line response lands, which sums to 0: the "nothing to show yet"
  // state.
  //
  // The ส่วนลดพิเศษ rows come off that sum as ESTIMATES (quotationMeta#estimateAdjustmentAmount), each
  // a percentage of the same NON-adjustment base because the server doesn't compound them. An English
  // document has no VAT at all.
  const productBase = useMemo(
    () => items.reduce((sum, it) => sum + (Number(it.lineAmount) || 0), 0),
    [items],
  );
  const adjustmentEstimates = useMemo(
    () => adjustments.map((a) => estimateAdjustmentAmount(a, productBase)),
    [adjustments, productBase],
  );
  const provisionalSubtotal = Math.round(
    (productBase - adjustmentEstimates.reduce((sum, amount) => sum + (amount ?? 0), 0)) * 100,
  ) / 100;
  const currency = currencyForLanguage(docSettings.documentLanguage);
  const isEnglish = docSettings.documentLanguage === 'EN';
  const provisionalVat = Math.round(provisionalSubtotal * vatRateForLanguage(docSettings.documentLanguage) * 100) / 100;
  const provisionalGrand = provisionalSubtotal + provisionalVat;
  // The server's own adjustment rows, in order, once the editor is clean — what each
  // QuotationAdjustmentRow shows instead of its estimate.
  const serverAdjustments = useMemo(
    () => (quotation?.items ?? []).filter((it) => lineTypeOf(it) === LINE_TYPE_ADJUSTMENT),
    [quotation],
  );

  // Every item must be COMPLETE before บันทึกร่าง / ส่งขออนุมัติ. validateQuotationItem
  // (quotationMeta.js) is the single source of truth for "complete"; this array parallels `items` 1:1
  // so the per-row summary below AND each row's inline hints (QuotationItemRow's `errors` prop) read
  // off the same computation, never two checks that could disagree about a row.
  const itemErrorsByRow = useMemo(
    () => items.map((it) => validateQuotationItem(it, rowPriceMode(docSettings.priceMode, it), docSettings.documentLanguage)),
    [items, docSettings.priceMode, docSettings.documentLanguage],
  );
  const adjustmentErrorsByRow = useMemo(() => adjustments.map((a) => validateAdjustment(a)), [adjustments]);

  // Mirrors DealQuotationService#requireEveryTileItemHasALeadTime. SUBMIT only, and a SEPARATE array
  // from `itemErrorsByRow` above rather than the same one with `requireLeadTime` always on:
  // `itemErrorsByRow` feeds `buildQuotationChecklist`, whose blocking set gates บันทึกร่าง too, and a
  // draft must still save with no lead time. `index` is the printed "รายการที่" number as
  // buildUpsertPayload sends `items`: tiles in document order, adjustments last.
  const submitItemErrorsByRow = useMemo(
    () => items.map((it) => validateQuotationItem(it, rowPriceMode(docSettings.priceMode, it), docSettings.documentLanguage,
      { requireLeadTime: true })),
    [items, docSettings.priceMode, docSettings.documentLanguage],
  );
  const missingLeadTimeSeqs = useMemo(
    () => submitItemErrorsByRow
      .map((errors, index) => (errors.leadTimeMinDays ? index + 1 : null))
      .filter((seq) => seq != null),
    [submitItemErrorsByRow],
  );
  const hasMissingLeadTimes = missingLeadTimeSeqs.length > 0;
  // The EXACT wording DealQuotationService#requireEveryTileItemHasALeadTime's 400 uses, so a rep
  // sees the same sentence whether the client or the server catches it.
  const leadTimeBlockMessage = hasMissingLeadTimes
    ? missingLeadTimeMessage(missingLeadTimeSeqs) : null;

  // Mirrors DealQuotationService#submit's own gate (isZeroDeposit(depositPercent) &&
  // isBlank(fullPaymentTerm)). SUBMIT only, like hasMissingLeadTimes above: a draft with this shape
  // still saves (buildQuotationChecklist's FULL_PAYMENT_TERM check stays non-blocking because
  // DealQuotationService#create/#update accept it); only #submit refuses it. depositPercent = 0 is
  // reached by the "ไม่รับมัดจำ" checkbox (`terms.noDeposit`) AND by typing "0" into the custom
  // "อื่นๆ" percent input while UNticked (buildUpsertPayload's ternary), so
  // #isEffectiveZeroDeposit (quotationMeta.js) recognises both and is shared with
  // buildQuotationChecklist's check so the two can't disagree.
  const hasUnresolvedZeroDeposit = isEffectiveZeroDeposit({
    noDeposit: terms.noDeposit, depositPercentCustom: terms.depositPercentCustom, depositPercent: terms.depositPercent,
  }) && (!terms.fullPaymentTerm || !terms.fullPaymentTerm.trim());
  const zeroDepositBlockMessage = hasUnresolvedZeroDeposit
    ? 'มัดจำ 0% ต้องเลือกเงื่อนไขการชำระเงินก่อนส่งขออนุมัติ' : null;

  // Mirrors DealQuotationService#submit's own gate (remainderMode === 'CREDIT' && creditDays == null).
  // SUBMIT only, like hasUnresolvedZeroDeposit above: a draft with a CREDIT remainder and no creditDays
  // still saves (buildQuotationChecklist's CREDIT_DAYS check stays non-blocking); only #submit refuses
  // it. An explicit INVALID (<=0) value is the already-blocking QUOTATION_CHECK.CREDIT_DAYS_INVALID
  // (in hasValidationErrors) and is not repeated here.
  const hasMissingCreditDays = terms.remainderMode === 'CREDIT'
    && !isEffectiveZeroDeposit({
      noDeposit: terms.noDeposit, depositPercentCustom: terms.depositPercentCustom, depositPercent: terms.depositPercent,
    })
    && (terms.creditDays === '' || terms.creditDays == null);
  const creditDaysBlockMessage = hasMissingCreditDays
    ? 'กรุณาระบุจำนวนวันเครดิต อย่างน้อย 1 วัน ก่อนส่งขออนุมัติ' : null;

  // ── "ข้อมูลที่ยังไม่ครบ" ───────────────────────────────────────────────────────────────────
  // ONE derivation (quotationMeta#buildQuotationChecklist) feeds the checklist panel, the disabled
  // บันทึกร่าง/ส่งขออนุมัติ buttons (blocking entries only: QUOTATION_BLOCKING_CHECKS, exactly what the
  // backend already refuses), and the ส่งขออนุมัติ confirm dialog's list of optional gaps. Advisory
  // only; the server re-validates regardless.
  const snapshotCustomer = recordCustomerId
    ? {
      id: recordCustomerId,
      name: quotation?.customerName ?? recordCustomerName,
      // Raw from the DTO: a real one always carries these (null when empty); `undefined` on
      // anything else reads as "unknown" and raises no warning.
      ...(quotation ? { address: quotation.customerAddress, taxId: quotation.customerTaxId, phone: quotation.customerPhone } : {}),
    }
    : null;
  const summaryCustomer = (customerOverride && Number(customerOverride.id) === Number(recordCustomerId) ? customerOverride : null)
    ?? customerRecordQuery.data ?? snapshotCustomer;
  const checklistCustomer = isInlineCreate ? dealForm.customer : summaryCustomer;
  // The one-live-direct-quotation block is per PARTY since V204: a deal whose OTHER contractor holds
  // a live quotation must not stop this party's. With the parties read, it is this quotation's party
  // (?party=, else the deal customer's party for the chosen recipient); before that, the deal-wide
  // summary field, which the server's own 409 backs up either way.
  const targetParty = namedParty ?? quotationTargetParty(dealParties, { customerId: ticket?.customerId, recipientType });
  const pickedDealLiveQuotation = dealParties !== undefined && (targetParty || partyParam)
    ? liveQuotationOfParty(targetParty)
    : (ticket?.liveDirectQuotation ?? null);
  // V204: with the parties read, only THIS party's live quotation can block (the server's lock is per party);
  // before that, every live row on the deal (the server checks all of them) as the route rule needs.
  const dealLiveRows = [ticket?.liveDirectQuotation, ...(dealRowsQuery.data ?? []).filter((q) => q.origin === 'DEAL_DIRECT')].filter(Boolean);
  const checklistLiveRows = dealParties !== undefined && (targetParty || partyParam)
    ? [pickedDealLiveQuotation].filter(Boolean)
    : dealLiveRows;
  const checklistDealStatus = ticket?.status ?? 'draft';
  const checklistProjectName = isInlineCreate ? null : (quotation ? quotation.projectName : (ticket ? ticket.projectName : undefined));
  const duplicateGroupIndex = duplicateGroupIds.size
    ? groups.findIndex((g) => duplicateGroupIds.has(g.groupId)) : null;
  const checklist = useMemo(() => buildQuotationChecklist({
    isInlineCreate,
    customer: checklistCustomer,
    hasProject: Boolean(dealForm.project),
    // Attaching to an existing deal makes no deal, so it needs no ช่องทางรับงาน.
    hasEntryChannel: attachingToDeal || (Boolean(dealForm.entryChannel) && dealForm.entryChannel !== 'UNSPECIFIED'),
    projectName: checklistProjectName,
    items,
    itemErrorsByRow,
    adjustments,
    adjustmentErrorsByRow,
    duplicateGroupIndex,
    // Wrong-way-round: a mode the language doesn't offer (none today) stays unsaveable if some path
    // reaches it.
    priceModeLanguageConflict: !isPriceModeAvailable(docSettings.priceMode, docSettings.documentLanguage),
    noDeposit: terms.noDeposit,
    // The checklist entry recognises BOTH routes to depositPercent = 0 (the checkbox and a
    // custom-typed "0"); see #isEffectiveZeroDeposit in quotationMeta.js.
    depositPercentCustom: terms.depositPercentCustom,
    depositPercent: terms.depositPercent,
    fullPaymentTerm: terms.fullPaymentTerm,
    // The credit-days rule; see QUOTATION_CHECK.CREDIT_DAYS/CREDIT_DAYS_INVALID in quotationMeta.js.
    remainderMode: terms.remainderMode,
    creditDays: terms.creditDays,
    // A new quotation needs a deal, and a new DIRECT quotation needs a recipient (400) on a deal with
    // no live direct quotation (the N6 409): each blocks บันทึกร่าง with its reason, before the
    // server has to refuse it.
    needsDeal,
    recipientRequired: !quotation,
    recipientType,
    // Only a NEW or CHANGED recipient: the server accepts a stored off-route one re-sent unchanged on a DRAFT.
    offRouteRecipientMessage: !quotation || recipientType !== quotation.recipientType
      ? recipientRouteRefusal(stageCatalog, isInlineCreate ? dealForm.entryChannel : ticket?.entryChannel, recipientType)
      : null,
    liveDirectQuotation: !quotation && !isInlineCreate ? checklistLiveRows : null,
    dealStatus: checklistDealStatus,
    // One pricing route per deal: a NEW quotation on a picked deal that holds a live pricing request
    // (DealQuotationService#create 409s it).
    livePricingRequest: !quotation && !isInlineCreate && dealHasLivePricingRequest,
    // V192.3 — a typed-but-malformed address is what DealQuotationService refuses on every save.
    orderedByEmail: terms.orderedByEmail,
    missingLeadTimeSeqs,
  }), [isInlineCreate, checklistCustomer, dealForm.project, dealForm.entryChannel, attachingToDeal, checklistProjectName, items, itemErrorsByRow,
    adjustments, adjustmentErrorsByRow, duplicateGroupIndex, docSettings, terms.noDeposit,
    terms.depositPercentCustom, terms.depositPercent, terms.fullPaymentTerm, terms.remainderMode, terms.creditDays,
    needsDeal, quotation, recipientType, checklistLiveRows, checklistDealStatus, dealHasLivePricingRequest, terms.orderedByEmail, missingLeadTimeSeqs, stageCatalog, ticket?.entryChannel]);
  const validationErrors = useMemo(() => checklist.filter((e) => e.blocking).map((e) => e.message), [checklist]);
  const checklistWarnings = useMemo(() => splitQuotationChecklist(checklist).warnings, [checklist]);
  const hasValidationErrors = validationErrors.length > 0;
  // Same "latest value" mirror as `dirtyRef` above, for the same reason: handleDownload reads this
  // across several `await`s and must never act on a value that went stale while suspended.
  const hasValidationErrorsRef = useRef(hasValidationErrors);
  useEffect(() => { hasValidationErrorsRef.current = hasValidationErrors; }, [hasValidationErrors]);
  /**
   * Whether to SHOW that checklist — separate from whether it exists, which still gates the save
   * buttons exactly as before.
   *
   * A pristine /quotations/new greeted the rep with four red-flagged failures ("ต้องเลือกลูกค้า",
   * "ต้องเลือกโครงการ", "กรุณาระบุผู้สั่งซื้อ", "ต้องมีรายการสินค้าอย่างน้อย 1 รายการ") before a
   * single keystroke — telling someone off for not yet having done what they just arrived to do.
   * The item rows already got this right via `touchedRowIds`; this is the page-level equivalent.
   *
   * `dirty || Boolean(quotation)` deliberately, not `dirty` alone: an EXISTING draft — the
   * sent-back-for-correction case, where the checklist is the whole point — must still show its
   * gaps the moment it loads, before the rep touches anything. Only the brand-new, untouched
   * editor is silenced.
   */
  const showValidationSummary = hasValidationErrors && (dirty || Boolean(quotation));

  // useCallback so the autosave effect's backoff check can list it as a dependency with a STABLE
  // identity: it changes only when a value it reads changes, matching that effect's [items,
  // adjustments, docSettings, terms, groups] dependency list (labelByGroupId is a groups-keyed
  // useMemo).
  const buildUpsertPayload = useCallback(() => ({
    // ผู้ติดต่อ, picked or null. DealQuotationService#resolveContact snapshots it (and stores its name
    // in ordered_by_name, never printed), so no `orderedByName` is sent. A null contactId on an update
    // KEEPS the draft's contact, so ล้าง on a draft that had one also says `clearContact: true`;
    // otherwise the key is left out.
    contactId: contact?.id ?? null,
    ...(quotation?.contactId != null && !contact ? { clearContact: true } : {}),
    // ฝ่าย is removed from the quotation. Sent as an explicit null on EVERY save rather than left out:
    // the server stores what it is sent, so this clears a ฝ่าย an older draft still carries, and a full
    // save states every field.
    deptCode: null,
    unitCode: terms.unitCode || null,
    offerDate: terms.offerDate || null,
    // noDeposit always wins over whatever terms.depositPercent still holds from before the checkbox
    // was ticked: depositPercent = 0 is the ONE signal the server reads as "no deposit" (mirrors
    // DealQuotationService's isZeroDeposit), so remainderMode/creditDays go null and fullPaymentTerm
    // carries the chosen term. The server enforces the same rule independently; this avoids showing
    // the rep stale data before the next fetch.
    depositPercent: terms.noDeposit ? 0 : (terms.depositPercent === '' ? null : Number(terms.depositPercent)),
    remainderMode: terms.noDeposit ? null : (terms.remainderMode || null),
    creditDays: terms.noDeposit ? null : (terms.creditDays === '' ? null : Number(terms.creditDays)),
    fullPaymentTerm: terms.noDeposit ? (terms.fullPaymentTerm || null) : null,
    validityDays: terms.validityDays === '' ? null : Number(terms.validityDays),
    // V178: validityDays is ALWAYS sent, in both modes (the days select keeps its last value even
    // while ระบุวันที่ is showing — see the toggle below) so DAYS mode has a real number to fall
    // back to the moment the rep switches back, or the moment special pricing is lost and this
    // editor auto-switches them back (see the effect near the toggle).
    validityMode: terms.validityMode || null,
    validityUntil: terms.validityMode === 'DATE' ? (terms.validityUntil || null) : null,
    customerNotes: terms.customerNotes || null,
    // v3/v3b: ALWAYS explicit — see defaultDocSettings. currency is derived from the language
    // (the server refuses any other pairing), sent so the request states what the rep saw.
    priceMode: docSettings.priceMode,
    documentLanguage: docSettings.documentLanguage,
    currency: currencyForLanguage(docSettings.documentLanguage),
    // Print-only ผู้พิมพ์/พนักงานขาย name override. Always sent explicitly (like
    // priceMode/documentLanguage above): a full PUT carries the payload's own value, null included,
    // so "omitted" and "cleared" are never ambiguous.
    printedByDisplayId: terms.printedByDisplayId === '' ? null : Number(terms.printedByDisplayId),
    salesRepDisplayId: terms.salesRepDisplayId === '' ? null : Number(terms.salesRepDisplayId),
    // Genuinely editable: no "missing keeps stored"; always sent explicitly, blank included, like
    // customerNotes above.
    projectName: terms.projectName || null,
    // Item 2 ("ไม่เติม “คุณ”", V180) — the checkbox under the ผู้ติดต่อ picker. Always an explicit
    // boolean, so a full PUT never leaves the server guessing what "missing" means.
    omitContactHonorific: terms.omitContactHonorific === true,
    // The customer's phone and email, printed on the document when filled. Always sent: a blank one is
    // an explicit null, which clears it. Trimmed because the Email is CHECKED trimmed
    // (orderedByEmailError), so what is sent is what was checked.
    orderedByPhone: terms.orderedByPhone.trim() || null,
    orderedByEmail: terms.orderedByEmail.trim() || null,
    // "ไม่แสดงชื่อโครงการในใบเสนอราคา". ALWAYS an explicit boolean. The server reads a MISSING value on
    // update as "keep what is stored" (UpsertDealQuotationRequest#hideProjectName), so only an explicit
    // false can untick it (and a stale browser tab can't un-hide a project). projectName above is
    // still sent in full: hiding is not clearing.
    hideProjectName: terms.hideProjectName === true,
    // The FLAT items array the API takes, in group order: `items` is stored that way (see
    // insertIntoGroup), so this is a plain map with no sort. Each row's `locationLabel` is stamped
    // from ITS GROUP, the only place that text lives; a blank group label sends null, which prints no
    // heading row. Each row goes in the quotation's price mode, then the ส่วนลดพิเศษ rows LAST (where
    // the server would put them), so the order sent is the order printed.
    items: [
      ...items.map((item) => ({
        // V192.5: each row in the mode IT is in; on a mixed document the row also states it.
        ...itemInputFromRow(item, rowPriceMode(docSettings.priceMode, item), docSettings.documentLanguage,
          { mixed: isMixedPriceMode(docSettings.priceMode) }),
        locationLabel: (labelByGroupId.get(item.groupId) || '').trim() || null,
      })),
      ...adjustments.map(adjustmentInputFromRow),
    ],
    // ผู้รับใบเสนอราคา, persisted on the quotation (it does NOT move the deal's stage; that rule is
    // owned elsewhere). OMITTED, never null, when there is nothing to say: a PRICING_REQUEST row's
    // recipient belongs to its คำขอราคา (the server 409s the field on that origin), and a legacy direct
    // draft with no recipient yet keeps whatever is stored.
    ...(quotation?.origin !== 'PRICING_REQUEST' && recipientType ? { recipientType } : {}),
  }), [contact, terms, docSettings, items, adjustments, labelByGroupId, quotation?.origin, quotation?.contactId, recipientType]);

  /** The ONE place that builds a save request: autosave, manual บันทึกร่าง, AND submit's pre-save call
   * this instead of pairing buildUpsertPayload() with editSeqRef.current separately, so the payload
   * sent and the edit-sequence snapshot describing it can't disagree. `sentClientIds` walks `items`
   * then `adjustments`, the same two arrays in the same order buildUpsertPayload's `items:` array
   * concatenates, so `sentClientIds[i]` names the row `payload.items[i]` describes. Called from
   * inside a mutationFn (never onMutate) so the snapshot is taken when the request is actually
   * issued. */
  function buildSaveRequest() {
    return {
      payload: buildUpsertPayload(),
      sentSeq: editSeqRef.current,
      sentClientIds: [...items.map((it) => it.clientId), ...adjustments.map((a) => a.clientId)],
    };
  }

  /** After ANY successful save, the server's response carries each item's (possibly newly-minted)
   * `id`, the CEO-comparison fields (`priceChangedFromCeo`/`ceoListUnitPrice`/`ceoDiscountPct`/
   * `ceoSpecialPriceSqm`/`ceoDirectNetPrice`/`ceoNetUnitPrice`) and the link ids
   * (`pricingRequestItemId`/`pricingDecisionItemId`), in `seq` order, which is the order SENT
   * (buildUpsertPayload sends non-adjustment rows then adjustments, and DealQuotationService
   * prints/returns them the same way). `sentClientIds[i]` is the clientId of whatever row became
   * `resItems[i]`. A length mismatch means the round trip isn't what this assumes (a concurrent edit
   * that changed row COUNT, a malformed response): skip merging rather than apply the wrong row's
   * fields to the wrong row.
   *
   * Without this merge only `id` was adopted: every other server-computed field (most visibly the
   * per-line CEO marker QuotationItemRow reads) stayed at its INITIAL-load value
   * (`rowFromServerItem`'s `...item` spread runs once per quotation; see the seeding effect). A rep
   * who edited a linked line's price, saved, reverted it to the CEO's figure and saved AGAIN would
   * still see the "changed" marker until a reload.
   *
   * Merging the ENTIRE matched server item (`{ ...it, ...server }`) clobbers an edit typed WHILE the
   * save was in flight: type 900, autosave fires, type 1234 before the response lands, resolve with
   * the server's echo of 900 -> the field snaps back to 900 and 1234 is lost, even though `dirty`
   * stays true. This affects DEAL_DIRECT too, since every quotation saves through here.
   *
   * So the merge is a WHITELIST (`SERVER_COMPUTED_ITEM_FIELDS` below), not a `sentSeq` gate: gating
   * on "no edit since this save was sent" would also suppress the marker refresh for a row the rep
   * never touched, just because they were mid-edit elsewhere. Restricting the merge to fields no
   * editable control writes removes the race by construction, regardless of timing: identity fields
   * (`id`, the two link ids) and CEO-comparison fields are safe to take from the server
   * unconditionally.
   *
   * Raw `setItems`/`setAdjustments`, NOT `updateItem`/`updateAdjustment`: merging a save response is
   * bookkeeping, not an edit, so it must never bump editSeqRef (a save that merged fields would look
   * like it landed mid-edit and refuse to clear `dirty`) and never touch `clientId` or
   * `touchedRowIds`. Rows added after the request was sent (not in `sentClientIds`) are untouched;
   * rows removed meanwhile are absent from `prev` and skipped. */
  function mergeServerItemFields(sentClientIds, resItems) {
    if (!Array.isArray(resItems) || resItems.length !== sentClientIds.length) return;
    const serverByClientId = new Map(sentClientIds.map((clientId, i) => [clientId, resItems[i]]));
    const pickServerComputedFields = (server) => {
      const patch = {};
      for (const field of SERVER_COMPUTED_ITEM_FIELDS) {
        if (field in server) patch[field] = server[field];
      }
      return patch;
    };
    setItems((prev) => prev.map((it) => {
      const server = serverByClientId.get(it.clientId);
      return server ? { ...it, ...pickServerComputedFields(server) } : it;
    }));
    setAdjustments((prev) => prev.map((a) => {
      const server = serverByClientId.get(a.clientId);
      return server ? { ...a, ...pickServerComputedFields(server) } : a;
    }));
  }

  /** Where autosave, manual บันทึกร่าง, and submit's pre-save all land after a successful PUT: merge
   * whatever the response minted/computed, then clear `dirty` ONLY if no edit happened while the
   * request was in flight (`editSeqRef.current === sentSeq`, captured by buildSaveRequest at send
   * time). If the rep kept typing, `dirty` stays true so the NEXT autosave tick (or submit attempt)
   * picks up what this request couldn't have known about. */
  function applySavedQuotation(quotation, sentSeq, sentClientIds) {
    mergeServerItemFields(sentClientIds, quotation.items);
    if (editSeqRef.current === sentSeq) setDirty(false);
  }

  // "บันทึกอัตโนมัติแล้ว HH:mm" — set by an autosave, cleared by any later manual save so the two
  // indicators never contradict each other.
  const [autoSavedAt, setAutoSavedAt] = useState(null);

  /** The rep's own terms become the starting point for their NEXT new quotation. Best-effort by
   * construction (see quotationPrefs.js). */
  function rememberDefaults() {
    writeQuotationDefaults(user?.id, {
      depositPercent: terms.depositPercent,
      // noDeposit/fullPaymentTerm are deliberately NOT sent: see emptyTerms's comment above and
      // quotationPrefs.js's DEFAULT_TERM_FIELDS. Remembering noDeposit would make every later new
      // quotation start with no deposit.
      remainderMode: terms.remainderMode,
      creditDays: terms.creditDays,
      validityDays: terms.validityDays,
      // V178: the MODE is remembered (a rep who dates their validity keeps starting there); the
      // DATE itself never is — see quotationPrefs.js's own comment on DEFAULT_TERM_FIELDS.
      validityMode: terms.validityMode,
      originCountry: items[items.length - 1]?.originCountry ?? '',
    });
  }

  // Files picked before the first บันทึกร่าง. Sent one at a time once the quotation exists; a refused
  // file is named in a toast and the rest still go up. The page then moves to the new quotation, whose
  // panel lists what was stored.
  async function attachPending(quotationId) {
    for (const file of pendingAttachments) {
      try {
        await api.dealQuotations.uploadAttachment(quotationId, file);
      } catch (error) {
        showToast('error', `แนบ ${file.name} ไม่สำเร็จ: ${error.message || 'แนบเอกสารไม่สำเร็จ'}`);
      }
    }
    // This page is not re-mounted by the navigate that follows, so what was sent must not be held.
    setPendingAttachments([]);
  }

  const createMutation = useMutation({
    mutationFn: () => api.dealQuotations.create(effectiveTicketId, {
      ...buildUpsertPayload(),
      // ?party= (the deal page's เสนอราคา for one contractor): the document belongs to that party.
      ...(partyParam ? { partyId: Number(partyParam) } : {}),
    }),
    onSuccess: async (res) => {
      // NOT applySavedQuotation: a brand-new quotation has no prior editSeq to compare, and this
      // navigates to /quotations/:id, which re-mounts the page and re-hydrates from the server. KNOWN,
      // UNFIXED edge case: an edit typed while this very create request is in flight is lost.
      setDirty(false);
      setSaveError(null);
      setSaveErrorLiveQuotationId(null);
      rememberDefaults();
      queryClient.invalidateQueries({ queryKey: ['dealQuotations'] });
      // The create gave the deal a live direct quotation: every cached deal list/summary (the CTA
      // cascade and the DealPicker read it) is stale now.
      queryClient.invalidateQueries({ queryKey: ['tickets'] });
      showToast('success', 'บันทึกร่างแล้ว');
      await attachPending(res.quotation.id);
      navigate(`/quotations/${res.quotation.id}`, { replace: true });
    },
    onError: (error) => {
      setSaveError(error.message || 'บันทึกไม่สำเร็จ');
      // The 409 body names the live quotation: offer the way to it.
      setSaveErrorLiveQuotationId(error.details?.liveQuotationId ?? null);
      showToast('error', error.message || 'บันทึกไม่สำเร็จ');
    },
  });

  // The JSON of the payload of the last UPDATE (autosave or manual) that failed, e.g. "ยอดรวมหลังหัก
  // ส่วนลดพิเศษติดลบ" while a flat ส่วนลดพิเศษ outweighs the rows above it. The autosave effect below
  // refuses to schedule while the CURRENT payload still serializes to this exact string, so a refusal
  // no edit has changed doesn't re-fire (and re-toast) every AUTOSAVE_DELAY_MS forever. Any edit
  // changes the payload and lifts the backoff. Cleared on the next successful save. A MANUAL
  // บันทึกร่าง never consults this ref: it always attempts and always toasts.
  const lastFailedAutoPayloadRef = useRef(null);

  // `variables.auto` marks an AUTOSAVE (see the debounce effect below): same request, same
  // invalidation, but no toast — a toast every two seconds while typing would be noise, and the
  // "บันทึกอัตโนมัติแล้ว HH:mm" line in the header is the quieter signal that replaces it.
  const updateMutation = useMutation({
    // The request and its edit-seq/clientId snapshot are built TOGETHER inside mutationFn (never
    // onMutate), read off the same instant (see buildSaveRequest).
    mutationFn: async () => {
      const { payload, sentSeq, sentClientIds } = buildSaveRequest();
      try {
        const res = await api.dealQuotations.update(id, payload);
        return { res, sentSeq, sentClientIds, sentRecipientType: payload.recipientType ?? null };
      } catch (error) {
        lastFailedAutoPayloadRef.current = JSON.stringify(payload);
        throw error;
      }
    },
    onSuccess: ({ res, sentSeq, sentClientIds, sentRecipientType }, variables) => {
      setSaveError(null);
      // A CHANGED recipient changes the deal summary's liveDirectQuotation.recipientType: refetch the
      // cached deal reads so they agree with the quotation.
      if (sentRecipientType && sentRecipientType !== quotation?.recipientType) {
        queryClient.invalidateQueries({ queryKey: ['tickets'] });
      }
      rememberDefaults();
      queryClient.setQueryData(queryKeys.dealQuotationDetail(id), res.quotation);
      queryClient.invalidateQueries({ queryKey: ['dealQuotations'] });
      // Adopt any ids the response minted, and clear `dirty` only if nothing changed while this
      // request was in flight (see applySavedQuotation).
      applySavedQuotation(res.quotation, sentSeq, sentClientIds);
      lastFailedAutoPayloadRef.current = null;
      if (variables?.auto) {
        setAutoSavedAt(new Date());
      } else {
        setAutoSavedAt(null);
        showToast('success', 'บันทึกร่างแล้ว');
      }
    },
    // An autosave failure is reported exactly like a manual one. Staying silent would be worse:
    // the rep would keep typing believing their work is safe. It does NOT clear `dirty`, so the
    // next edit simply schedules another attempt (unless that edit reproduces the SAME payload —
    // see the backoff ref above).
    onError: (error) => {
      setSaveError(error.message || 'บันทึกไม่สำเร็จ');
      showToast('error', error.message || 'บันทึกไม่สำเร็จ');
    },
  });

  // The download handler needs to know whether an update is ALREADY on the wire (from an autosave OR a
  // manual บันทึกร่าง), so it can await the SAME request instead of firing a second, overlapping one.
  // `runSave` is where autosave and บันทึกร่าง trigger `updateMutation`, and both track this ref.
  // submitMutation's own mutationFn issues its own pre-save PUT directly via
  // `api.dealQuotations.update`, outside `runSave`; it is tracked in `pendingSaveRef` there too (the
  // same self-clearing shape) so a download flush racing a pending submit awaits it instead of firing
  // a second PUT. It is NOT routed through `runSave`/`updateMutation`: that would fire
  // `updateMutation`'s onSuccess/onError (an extra "บันทึกร่างแล้ว" toast, or a SECOND error toast
  // beside submitMutation's own onError), which submit's silent, atomic save-then-submit UX doesn't
  // want. So `pendingSaveRef` covers every PUT this page issues, but not exclusively `runSave`'s.
  const pendingSaveRef = useRef(null);
  // The autosave debounce's own pending timer, if any — cleared by the download flush so a stale
  // debounce firing mid-download can never race it (a second concurrent PUT while the flush's own
  // save is already in flight).
  const autosaveTimerRef = useRef(null);
  const { mutateAsync: runUpdateAsync } = updateMutation;
  const runSave = useCallback((auto) => {
    const promise = runUpdateAsync(auto ? { auto: true } : undefined);
    pendingSaveRef.current = promise;
    // A separate handled chain off the SAME promise so an autosave/บันทึกร่าง caller that never awaits
    // the return value doesn't surface an unhandled rejection (updateMutation's onError already
    // toasts). A caller that DOES await `runSave(...)` (the download flush) still sees the rejection.
    promise.catch(() => {}).finally(() => {
      if (pendingSaveRef.current === promise) pendingSaveRef.current = null;
    });
    return promise;
  }, [runUpdateAsync]);

  const [submitConfirmOpen, setSubmitConfirmOpen] = useState(false);
  // The deal page's sticky CTA and the sales worklist key on the deal summary's liveDirectQuotation,
  // so every docStatus change of a direct quotation (submit / approve / reject / revise / reorder /
  // cancel) changes the DEAL's data too. Invalidating only ['dealQuotations'] left the deal page
  // offering "ส่งขออนุมัติใบเสนอราคา" after submit. ['tickets'] is every deal query: list rows, the
  // deal page's TicketDto, and this page's own deal summary.
  function invalidateDealViews() {
    queryClient.invalidateQueries({ queryKey: ['tickets'] });
  }
  const [changeCustomerOpen, setChangeCustomerOpen] = useState(false);

  const submitMutation = useMutation({
    // Unsaved edits are SAVED first. Submit acts on the STORED quotation, and autosave is a 2-second
    // debounce, so a rep who corrects the ที่อยู่ (which only reaches the document when the draft is
    // re-saved and re-snapshots the customer) and clicks ส่งขออนุมัติ at once would otherwise send the
    // document without it.
    //
    // This is the ONLY other place besides updateMutation that PUTs the draft, and autoSaveEligible
    // below is suppressed while `submitConfirmOpen` is true or this mutation is pending, so the two
    // can never both be in flight. Uses buildSaveRequest/applySavedQuotation like updateMutation, so
    // a submit's pre-save adopts server ids and clears `dirty` the same way an autosave would.
    mutationFn: async () => {
      // The picker's in-flight เบอร์/อีเมล save first: the pre-save below makes the server re-read
      // the live contact, and must not freeze the old value (see flushPendingContactSave).
      await contactPickerRef.current?.flushPendingContactSave();
      if (dirty) {
        const { payload, sentSeq, sentClientIds } = buildSaveRequest();
        // D3 fix (Opus review 2026-09-16): tracked in `pendingSaveRef` by hand, the same
        // self-clearing shape `runSave` uses — purely so a download flush racing this pre-save
        // sees it via `pendingSaveRef.current` and awaits it instead of firing a second, overlapping
        // PUT (DealQuotationService#resolveContact reads live state, so two concurrent writes can
        // otherwise race each other into Postgres in either order). Deliberately NOT routed
        // through `runSave`/`updateMutation` itself — see `pendingSaveRef`'s own comment above for
        // why that would change submit's toasts.
        const savePromise = api.dealQuotations.update(id, payload);
        pendingSaveRef.current = savePromise;
        savePromise.catch(() => {}).finally(() => {
          if (pendingSaveRef.current === savePromise) pendingSaveRef.current = null;
        });
        const saved = await savePromise;
        queryClient.setQueryData(queryKeys.dealQuotationDetail(id), saved.quotation);
        applySavedQuotation(saved.quotation, sentSeq, sentClientIds);
        lastFailedAutoPayloadRef.current = null;
      }
      return api.dealQuotations.submit(id);
    },
    onSuccess: (res) => {
      // Owner clarification (2026-09-15): submit() on a ตีกลับ'd draft now mints a NEW revision
      // instead of resubmitting the SAME row (DealQuotationService#submit's own status-machine
      // comment) -- res.quotation.id can differ from this page's OWN `id` now, where it never
      // could before. Mirrors reviseMutation's own onSuccess just below (the createRevision
      // button already had to solve this exact "server minted a different id" case): navigate
      // there instead of caching the NEW quotation's data under the OLD id's query key, which
      // would leave the rep looking at this page's stale route while the cache silently disagreed
      // with it (and a refresh would then re-fetch the OLD, now-superseded-in-waiting row).
      if (String(res.quotation.id) !== id) {
        queryClient.invalidateQueries({ queryKey: ['dealQuotations'] });
        invalidateDealViews();
        showToast('success', 'ส่งขออนุมัติแล้ว (ฉบับแก้ไขใหม่ ' + res.quotation.number + ')');
        setSubmitConfirmOpen(false);
        navigate(`/quotations/${res.quotation.id}`);
        return;
      }
      queryClient.setQueryData(queryKeys.dealQuotationDetail(id), res.quotation);
      queryClient.invalidateQueries({ queryKey: ['dealQuotations'] });
      invalidateDealViews();
      showToast('success', 'ส่งขออนุมัติแล้ว');
      setSubmitConfirmOpen(false);
    },
    onError: (error) => showToast('error', error.message || 'ส่งขออนุมัติไม่สำเร็จ'),
  });

  // ── Autosave ──
  // Fires AUTOSAVE_DELAY_MS after the last edit, and only once the quotation already has an id:
  // minting a deal + quotation off a half-typed form is never a debounce's decision (the first
  // บันทึกร่าง stays an explicit click).
  // Also refused while the form is INCOMPLETE: บันทึกร่าง is disabled by the same rule and the
  // server rejects an incomplete upsert, so autosave would only toast an error every two seconds.
  // Also refused while the ส่งขออนุมัติ confirm dialog is open or submit is pending: submit's own
  // pre-save must be the only PUT right before submit fires, or the two race into a 409. Those
  // flags flipping is a dependency change, so the effect cleanup clears an already-scheduled timer.
  // The eligibility flag is computed outside the effect so it, not a re-render, is what the
  // dependency array keys on; `items`/`terms` are the edits that restart the debounce.
  const autoSaveEligible = Boolean(id) && dirty && !hasValidationErrors && !!quotation
    && isDealQuotationEditable(quotation) && canEditDealQuotation(user, quotation)
    && !submitConfirmOpen && !submitMutation.isPending;
  const { isPending: updatePending } = updateMutation;
  useEffect(() => {
    if (!autoSaveEligible || updatePending) return undefined;
    // Refuse the payload that just failed until the user edits again (see lastFailedAutoPayloadRef).
    if (lastFailedAutoPayloadRef.current != null
      && JSON.stringify(buildUpsertPayload()) === lastFailedAutoPayloadRef.current) {
      return undefined;
    }
    // Also kept in `autosaveTimerRef` so the download flush can clearTimeout it and not race its own save.
    const timer = setTimeout(() => {
      autosaveTimerRef.current = null;
      runSave(true);
    }, AUTOSAVE_DELAY_MS);
    autosaveTimerRef.current = timer;
    return () => {
      clearTimeout(timer);
      if (autosaveTimerRef.current === timer) autosaveTimerRef.current = null;
    };
    // `groups` feeds `labelByGroupId`, which buildUpsertPayload reads for every row's locationLabel,
    // so a group rename must restart the debounce too. `buildUpsertPayload` is listed for the
    // failed-payload comparison above; it is keyed on the same values, so no extra restarts.
  }, [autoSaveEligible, updatePending, runSave, items, adjustments, docSettings, terms, groups, buildUpsertPayload]);

  // First บันทึกร่าง on an inline-create visit must mint the ticket before there is anything to
  // hang a quotation on: api.tickets.create, then api.dealQuotations.create(ticketId, ...), then
  // navigate. A plain useMutation can't remember "step 1 already succeeded" across a retried
  // mutate(), so this is a hand-rolled handler like handleDownload above.
  // If tickets.create fails, the form stays as the user left it (nothing resets on error) and the
  // backend's Thai message is toasted. If dealQuotations.create then fails, the ticket id stays in
  // createdTicketIds so the next click retries only the quotation instead of minting a second ticket.
  // The success tail every first save of a NEW quotation shares.
  async function finishFirstSave(quotationRes) {
    setDirty(false);
    setSaveError(null);
    setSaveErrorLiveQuotationId(null);
    rememberDefaults();
    queryClient.invalidateQueries({ queryKey: ['dealQuotations'] });
    queryClient.invalidateQueries({ queryKey: ['tickets'] });
    showToast('success', 'บันทึกร่างแล้ว');
    await attachPending(quotationRes.quotation.id);
    navigate(`/quotations/${quotationRes.quotation.id}`, { replace: true });
  }

  // "เพิ่ม {customer} เป็นผู้รับเหมาในดีลเดิม": the customer joins the existing deal as a BUYER party
  // (reusing its party when it already has one: the add then answers 409), and the quotation is
  // created on that deal for that party.
  async function handleAttachCreate() {
    setCreatingDeal(true);
    try {
      const deal = existingDeal;
      const madeFor = `${deal.id}/${dealForm.customer.id}`;
      let partyId = attachedPartyIds[madeFor];
      if (!partyId) {
        try {
          const res = await api.tickets.addParty(deal.id, {
            customerId: dealForm.customer.id, role: 'BUYER', ...(contact?.id ? { contactId: contact.id } : {}),
          });
          partyId = res?.party?.id;
        } catch (error) {
          if (error?.status !== 409) throw error;
          const res = await api.tickets.parties(deal.id);
          partyId = (res?.parties ?? []).find((p) => Number(p.customerId) === Number(dealForm.customer.id) && p.role === 'BUYER')?.id;
          if (!partyId) throw error;
        }
        if (!partyId) throw new Error('เพิ่มผู้รับเหมาในดีลไม่สำเร็จ');
        setAttachedPartyIds((made) => ({ ...made, [madeFor]: partyId }));
      }
      const quotationRes = await api.dealQuotations.create(deal.id, { ...buildUpsertPayload(), partyId });
      await finishFirstSave(quotationRes);
    } catch (error) {
      setSaveError(error.message || 'บันทึกไม่สำเร็จ');
      setSaveErrorLiveQuotationId(error.details?.liveQuotationId ?? null);
      showToast('error', error.message || 'บันทึกไม่สำเร็จ');
    } finally {
      setCreatingDeal(false);
    }
  }

  async function handleInlineCreate() {
    if (attachingToDeal) {
      await handleAttachCreate();
      return;
    }
    setCreatingDeal(true);
    try {
      // A deal is remembered under the customer, project and ช่องทางรับงาน it was made for, and reused for
      // those only: after a refusal the rep can choose another customer, project or channel before saving
      // again, and that quotation must not land on the earlier choice's deal (a different channel is a
      // different route: the server refuses a recipient off it, and a deal's channel is not corrected here).
      const madeFor = `${dealForm.customer.id}/${dealForm.project.id}/${dealForm.entryChannel}`;
      let ticketId = createdTicketIds[madeFor];
      if (!ticketId) {
        const ticketRes = await api.tickets.create({
          title: dealForm.customer.name,
          customerName: dealForm.customer.name,
          customerId: dealForm.customer.id,
          projectId: dealForm.project.id,
          // The deal never carries a ผู้ติดต่อ; the quotation does, below.
          contactId: null,
          entryChannel: dealForm.entryChannel,
          priority: 'NORMAL',
          items: [],
          nextFollowUpAt: addDaysIso(todayIso(), 7),
          // Marks "created from a quotation" provenance only; it hides and gates nothing. The deal is
          // a normal pipeline deal and its order is confirmed with ยืนยันคำสั่งซื้อ while status is
          // 'draft'. See CreateTicketRequest#quotationOnly.
          quotationOnly: true,
          ...(effectiveDealOwnerId && effectiveDealOwnerId !== String(user?.id) ? { ownerEmployeeId: Number(effectiveDealOwnerId) } : {}),
        });
        // TicketDto envelope: the new ticket's id is at `.summary.id`, never `.ticket.id`/`.id`.
        ticketId = ticketRes.ticket?.summary?.id;
        if (!ticketId) throw new Error('สร้างดีลไม่สำเร็จ');
        setCreatedTicketIds((made) => ({ ...made, [madeFor]: ticketId }));
      }
      const quotationRes = await api.dealQuotations.create(ticketId, buildUpsertPayload());
      await finishFirstSave(quotationRes);
    } catch (error) {
      setSaveError(error.message || 'บันทึกไม่สำเร็จ');
      setSaveErrorLiveQuotationId(error.details?.liveQuotationId ?? null);
      showToast('error', error.message || 'บันทึกไม่สำเร็จ');
    } finally {
      setCreatingDeal(false);
    }
  }

  function handleSaveDraft() {
    if (id) runSave(false);
    else if (isInlineCreate) handleInlineCreate();
    else createMutation.mutate();
  }

  const [approveConfirmOpen, setApproveConfirmOpen] = useState(false);
  const [rejectOpen, setRejectOpen] = useState(false);
  const [rejectReason, setRejectReason] = useState('');
  const [cancelConfirmOpen, setCancelConfirmOpen] = useState(false);
  // "ยืนยันคำสั่งซื้อ" confirm dialog.
  const [promoteConfirmOpen, setPromoteConfirmOpen] = useState(false);
  const approveMutation = useMutation({
    mutationFn: (payload) => api.dealQuotations.approve(id, payload || {}),
    onSuccess: (res) => {
      queryClient.setQueryData(queryKeys.dealQuotationDetail(id), res.quotation);
      queryClient.invalidateQueries({ queryKey: ['dealQuotations'] });
      invalidateDealViews();
      // One approval always issues a PRICING_REQUEST-origin quotation (same as DEAL_DIRECT's
      // APPROVED outcome), so one message serves both.
      showToast('success', res.quotation.docStatus === 'ISSUED' ? 'อนุมัติและออกใบเสนอราคาแล้ว' : 'อนุมัติแล้ว');
      setApproveConfirmOpen(false);
    },
    // A 409 means another approver already decided this quotation while the screen was open:
    // refetch so the page shows the real status, and toast the backend's Thai message (it names
    // the actual status).
    onError: (error) => {
      if (error.status === 409) quotationQuery.refetch();
      showToast('error', error.message || 'อนุมัติไม่สำเร็จ');
    },
  });

  const rejectMutation = useMutation({
    mutationFn: (reason) => api.dealQuotations.reject(id, { reason }),
    onSuccess: (res) => {
      queryClient.setQueryData(queryKeys.dealQuotationDetail(id), res.quotation);
      queryClient.invalidateQueries({ queryKey: ['dealQuotations'] });
      invalidateDealViews();
      showToast('success', 'บันทึกการไม่อนุมัติแล้ว');
      setRejectOpen(false);
      setRejectReason('');
    },
    // Same staleness guard as approve's onError.
    onError: (error) => {
      if (error.status === 409) quotationQuery.refetch();
      showToast('error', error.message || 'บันทึกไม่สำเร็จ');
    },
  });

  const reviseMutation = useMutation({
    mutationFn: () => api.dealQuotations.createRevision(id, {}),
    onSuccess: (res) => {
      queryClient.invalidateQueries({ queryKey: ['dealQuotations'] });
      invalidateDealViews();
      showToast('success', 'สร้างฉบับแก้ไขแล้ว');
      navigate(`/quotations/${res.quotation.id}`);
    },
    onError: (error) => showToast('error', error.message || 'สร้างฉบับแก้ไขไม่สำเร็จ'),
  });

  // "ยืนยันคำสั่งซื้อ": record the customer's order on this APPROVED direct quotation's deal
  // (DealQuotationService#confirmOrderFromDirectQuotation), then open the deal.
  // The ticket's deal-page cache is dropped (not just invalidated) before navigating: this page may
  // have cached a bare summary under the ticketDetail key that TicketDetailPage reads as a whole
  // TicketDto, and that shape must never render there.
  // Every failure, 403 and 409 included, toasts the server's Thai message; a 409 also refetches
  // because the quotation or deal changed under this screen.
  const promoteMutation = useMutation({
    mutationFn: () => api.dealQuotations.promoteToDeal(id),
    onSuccess: (res) => {
      const dealId = res?.result?.ticketId ?? quotation?.ticketId;
      queryClient.removeQueries({ queryKey: queryKeys.ticketDetail(dealId) });
      queryClient.invalidateQueries({ queryKey: ['tickets'] });
      queryClient.invalidateQueries({ queryKey: ['dealQuotations'] });
      setPromoteConfirmOpen(false);
      showToast('success', 'ยืนยันคำสั่งซื้อแล้ว');
      navigate(`/tickets/${dealId}`);
    },
    onError: (error) => {
      if (error.status === 409) {
        quotationQuery.refetch();
        promotionTicketQuery.refetch();
      }
      setPromoteConfirmOpen(false);
      showToast('error', error.message || 'ยืนยันคำสั่งซื้อไม่สำเร็จ');
    },
  });

  /** "คืนรายการ": re-adds a CEO-linked line a prior save dropped. Not a save of the rep's own edits,
   * so on success it re-seeds `groups`/`items` from the response (same derivation as the
   * initial-load effect) instead of merging by clientId: the restored row is brand new with no
   * local clientId. `adjustments`/`terms`/`docSettings` are untouched. */
  const restoreItemMutation = useMutation({
    mutationFn: (pricingDecisionItemId) => api.dealQuotations.restoreRemovedItem(id, pricingDecisionItemId),
    onSuccess: (res) => {
      queryClient.setQueryData(queryKeys.dealQuotationDetail(id), res.quotation);
      queryClient.invalidateQueries({ queryKey: ['dealQuotations'] });
      const restored = editorStateFromQuotation(res.quotation);
      setGroups(restored.groups);
      setItems(restored.items);
      setTouchedRowIds(new Set(res.quotation.items.map((it) => it.id)));
      showToast('success', 'คืนรายการแล้ว');
    },
    onError: (error) => showToast('error', error.message || 'คืนรายการไม่สำเร็จ'),
  });

  const cancelMutation = useMutation({
    mutationFn: () => api.dealQuotations.cancel(id, {}),
    onSuccess: (res) => {
      queryClient.setQueryData(queryKeys.dealQuotationDetail(id), res.quotation);
      queryClient.invalidateQueries({ queryKey: ['dealQuotations'] });
      invalidateDealViews();
      showToast('success', 'ยกเลิกร่างแล้ว');
      setCancelConfirmOpen(false);
    },
    onError: (error) => showToast('error', error.message || 'ยกเลิกไม่สำเร็จ'),
  });

  const [downloading, setDownloading] = useState(null);
  // Shared by both refusal points in handleDownload below.
  function refuseDownloadForValidation() {
    showToast('error', 'ยังดาวน์โหลดไม่ได้ — กรุณากรอกข้อมูลให้ครบก่อน');
    const firstBlockingField = checklist.find((entry) => entry.blocking && entry.targetId);
    if (firstBlockingField) {
      focusQuotationField(firstBlockingField.targetId);
    } else if (typeof document !== 'undefined') {
      // No single field to focus (e.g. a duplicate ตำแหน่งติดตั้ง group): scroll the whole checklist
      // into view. `scrollIntoView` is optional-chained because jsdom lacks it.
      document.querySelector('[data-testid="quotation-checklist"]')
        ?.scrollIntoView?.({ block: 'center', behavior: 'smooth' });
    }
  }
  /**
   * Download flushes unsaved edits first. PDF/XLSX are rendered from the DB on every request, so
   * they are only as fresh as the last successful save; an edit still in editor state (autosave
   * not fired, or a save on the wire) would otherwise be missing from the download. On an editable
   * (DRAFT, editable-by-this-user) quotation:
   *   1. Await the ผู้ติดต่อ picker's in-flight เบอร์/อีเมล save, if any (same race submit's
   *      pre-save guards against).
   *   2. Await any save already in flight (autosave, manual บันทึกร่าง, submit's pre-save; see
   *      pendingSaveRef) so the flush never overlaps it.
   *   3. Cancel any pending autosave debounce. Done AFTER the awaits so a timer scheduled during
   *      them is the one cancelled.
   *   4. Refuse (never download a stale PDF) only if there is unsaved work this flush cannot save:
   *      blocking validation errors AND (form dirty OR a save was in flight). An untouched
   *      incomplete DRAFT (sent back for correction, or a row missing ความหนา, ~41% of the
   *      catalogue) has nothing to save and still downloads the stored document.
   *   5. If still dirty, save once more through `runSave`, the same update path บันทึกร่าง/autosave
   *      use. Re-checked against live validation state in case an edit during step 2 made the form
   *      newly invalid.
   * A non-editable (submitted/approved/read-only) quotation is frozen server-side: nothing to flush.
   *
   * Steps 4/5 read `dirtyRef.current`/`hasValidationErrorsRef.current`, never the `dirty`/
   * `hasValidationErrors` closure values: this function stays suspended at each `await` across
   * renders, so its closure would still say `dirty: false` after an edit landed mid-flush. That
   * would skip the save, and step 3 would already have cancelled the autosave that edit scheduled.
   * Reading refs means the edit whose timer step 3 cancelled is the one step 5 saves.
   */
  async function handleDownload(format) {
    if (downloading) return; // one flush+download in flight at a time — no double-fire
    setDownloading(format);
    try {
      if (isEditable) {
        await contactPickerRef.current?.flushPendingContactSave();
        if (pendingSaveRef.current) {
          // Already toasted by updateMutation's onError if it fails; fall through to the live checks.
          await pendingSaveRef.current.catch(() => {});
        }
        // Cancel any pending autosave timer now, after both awaits (see header for why).
        if (autosaveTimerRef.current) {
          clearTimeout(autosaveTimerRef.current);
          autosaveTimerRef.current = null;
        }
        // Only refuse when there is unsaved work this flush cannot save.
        if ((dirtyRef.current || pendingSaveRef.current) && hasValidationErrorsRef.current) {
          refuseDownloadForValidation();
          return;
        }
        if (dirtyRef.current) {
          // Re-checked in case an edit during the pendingSaveRef await made the form newly invalid.
          if (hasValidationErrorsRef.current) {
            refuseDownloadForValidation();
            return;
          }
          try {
            await runSave(false);
          } catch {
            // updateMutation's onError already toasted this; returning (not rethrowing) skips the
            // download without a duplicate toast from the outer catch.
            return;
          }
        }
      }
      const blob = format === 'pdf' ? await api.dealQuotations.downloadPdf(id) : await api.dealQuotations.downloadXlsx(id);
      downloadBlob(blob, quotation?.number ?? 'quotation', format);
    } catch (error) {
      showToast('error', error.message || 'ดาวน์โหลดไม่สำเร็จ');
    } finally {
      setDownloading(null);
    }
  }

  if (id && quotationQuery.isLoading) return <RouteFallback />;
  // A blank form shown while the source is on its way would be typed into, and then replaced.
  if (copySourcePending) return <RouteFallback />;
  if (id && quotationQuery.isError) {
    return (
      <PageStack>
        <PageHeader title="ใบเสนอราคา" />
        <Panel><p className="text-danger">{quotationQuery.error?.message || 'ไม่พบใบเสนอราคานี้'}</p></Panel>
      </PageStack>
    );
  }
  // isInlineCreate bypasses this gate: it is the one case with neither :id nor a resolvable ticket
  // where that is expected (no deal yet). A `?ticket=` that fails to resolve still hits it.
  if (!id && !isInlineCreate && !ticketQuery.isLoading && !ticket) {
    return (
      <PageStack>
        <PageHeader title="สร้างใบเสนอราคา" />
        <Panel><p className="text-danger">ไม่พบดีลนี้ — เปิดหน้านี้จากรายละเอียดดีลเท่านั้น</p></Panel>
      </PageStack>
    );
  }

  // A brand-new quotation must not default to `true` for everyone: an import/account read-only
  // viewer who reached /quotations/new via a stale link would get the full editor. Use
  // canCreateDealQuotation(user, ticket), the predicate DealDirectQuotationPanel already gates the
  // "สร้างใบเสนอราคา" link with. isInlineCreate has no ticket to check ownership against yet, so
  // the role-only canCreateDealQuotationStandalone stands in for that moment.
  const isEditable = quotation
    ? (isDealQuotationEditable(quotation) && canEditDealQuotation(user, quotation))
    : isInlineCreate
      ? canCreateDealQuotationStandalone(user)
      : canCreateDealQuotation(user, ticket);
  const readOnlyViewer = isDealQuotationReadOnlyViewer(user);
  // canChangeDealCustomer mirrors TicketService.changeCustomer; the page adds only "is saved".
  const canChangeCustomer = Boolean(quotation) && canChangeDealCustomer(user, ticket, quotation);
  const status = quotation ? dealQuotationStatusLabel(quotation.docStatus) : null;
  // Kept `null` on a pristine /quotations/new so the subtitle can say what to do instead of a bare
  // "-" (which reads as a failed load); the `'-'` fallback still applies where a dash is a real
  // value (salesRepName in the document context strip).
  // The live customer record wins only while the document is a draft: react-query keeps serving a
  // cached record after submit, and a renamed customer must not rewrite a frozen quotation's header.
  const customerName = (isEditable ? summaryCustomer?.name : null) ?? quotation?.customerName ?? ticket?.customerName ?? (isInlineCreate ? dealForm.customer?.name : null) ?? null;
  // ผู้ติดต่อ picker over the deal customer's contacts, shown in the one place each entry path
  // renders it. Its customer is the loaded deal's, or on a new deal the one chosen in the ดีล card.
  // With neither (a deal this user cannot read, e.g. a quotation-grant holder) it shows its own
  // "เลือกลูกค้าก่อน" state. Optional, never a checklist blocker.
  const contactPicker = (
    <div className="col-span-full">
      <QuotationContactPicker
        ref={contactPickerRef}
        customerId={recordCustomerId ?? (isInlineCreate ? dealForm.customer?.id : null) ?? null}
        customerName={customerName}
        value={contact}
        onChange={(next) => { setContact(next); setDirty(true); }}
        onResolve={setContact}
        showToast={showToast}
        idPrefix="quotation-contact"
        omitContactHonorific={terms.omitContactHonorific}
        onChangeOmitContactHonorific={(next) => { setTerms((t) => ({ ...t, omitContactHonorific: next })); setDirty(true); }}
      />
    </div>
  );
  const projectName = ((isEditable && quotation ? terms.projectName : null) || quotation?.projectName) ?? ticket?.projectName ?? (isInlineCreate ? dealForm.project?.name : null) ?? null;
  // The REAL rep's name: what "(ค่าเริ่มต้น)" (no override) means on both salesRepDisplayId
  // selects. Options read straight off `quotation.*`/`displayNameOptions`, not an override-resolved
  // name, or the empty option would show the override's own name next to the choice that turns it off.
  const realSalesRepName = quotation?.salesRepName ?? ticket?.createdByName ?? (isInlineCreate ? user.name : null) ?? '-';
  const wasRejected = quotation?.docStatus === 'DRAFT' && Boolean(quotation?.approvalNote);
  // The one branch point for PRICING_REQUEST-origin documents. Everything else keys off
  // `quotation.origin` directly, so a DEAL_DIRECT document's rendering is unaffected.
  const isPricingRequestOrigin = quotation?.origin === 'PRICING_REQUEST';
  // Local mirror of DealQuotationRepository#ceoPriceModeChanged
  // (`ceoPriceMode != null && !ceoPriceMode.equals(currentPriceMode)`), compared against
  // `docSettings.priceMode` (the current, possibly unsaved mode) not the last-saved one. Otherwise
  // a mode switch on a row that fails validation never autosaves and the header stays silent about
  // it. ORed with the server flag, which stays authoritative once a save lands.
  const priceModeChangedFromCeoLocally = isPricingRequestOrigin && quotation?.ceoPriceMode != null
    && docSettings.priceMode !== quotation.ceoPriceMode;
  const saving = createMutation.isPending || updateMutation.isPending || creatingDeal;

  // The deal behind this quotation, for the header strip. The DTO does not carry
  // `ticketCode`/`dealStage`, so read the deal summary this page already fetches (the ?ticket= /
  // editable-DRAFT `ticket`, or the promoter's summary on an APPROVED direct quotation). No extra
  // request: a quotation with no summary here (e.g. a non-DRAFT viewed by an approver) shows no
  // strip. Hidden while inline-creating: there is no deal yet.
  const stripDeal = quotation?.ticketCode
    ? {
      id: quotation.ticketId, code: quotation.ticketCode,
      customerName: quotation.customerName, salesStage: quotation.dealStage ?? null,
    }
    : (ticket ?? promotionTicketQuery.data ?? null);
  const stripDealId = stripDeal?.id ?? effectiveTicketId;
  const canOpenStripDeal = stripDealId != null && canAccessPath(`/tickets/${stripDealId}`, user);
  const stripStage = stripDeal?.salesStage ? dealStageLabel(stripDeal.salesStage) : null;

  return (
    <PageStack>
      <PageHeader
        title={quotation ? `ใบเสนอราคา ${quotation.number}` : 'สร้างใบเสนอราคา'}
        // Offered in every status: it only navigates, nothing is created until the copy is saved.
        // Disabled while dirty/saving because the copy is made from what is SAVED.
        titleAside={quotation && canCopyDealQuotation(user, quotation) ? (
          <Button
            variant="icon"
            size="sm"
            // shrink-0: beside a title that fills the row the button would otherwise give up
            // width; mobile:w-11 lifts the sm icon button to the 44px touch floor there.
            className="shrink-0 mobile:w-11"
            aria-label="คัดลอกเป็นใบใหม่"
            disabled={dirty || saving}
            title={dirty || saving
              ? 'บันทึกร่างก่อน จึงจะคัดลอกได้ (คัดลอกจากฉบับที่บันทึกแล้ว)'
              : 'เปิดใบเสนอราคาใบใหม่ที่มีรายการและเงื่อนไขเหมือนใบนี้ สำหรับลูกค้ารายอื่น'}
            onClick={() => navigate(`/quotations/new?copyFrom=${quotation.id}`)}
          >
            <Icon name="copy" size={16} />
          </Button>
        ) : null}
        subtitle={customerName
          ? `${customerName}${projectName ? ` · ${projectName}` : ''}`
          : 'เริ่มจากเลือกดีลด้านล่าง หรือสร้างดีลใหม่'}
        context={(
          <>
            {status ? <StatusBadge tone={status.tone}>{status.label}</StatusBadge> : null}
            {/* "เอกสารแนบ {n} ไฟล์", beside the status of the read-only details (see
                QuotationAttachmentsLink). Not on the edit form, whose reader attached them. */}
            {quotation && !isEditable ? <QuotationAttachmentsLink user={user} quotation={quotation} /> : null}
            {/* Provenance link to the pricing request this quotation was created from. Falls back to
                the numeric id when the PR join is null, so the link degrades instead of vanishing. */}
            {isPricingRequestOrigin && quotation.pricingRequestId ? (
              <Link
                to={`/pricing-requests/${quotation.pricingRequestId}`}
                className="text-2xs font-bold text-link underline"
              >
                สร้างจากคำขอราคา {quotation.pricingRequestCode ?? `#${quotation.pricingRequestId}`}
              </Link>
            ) : null}
            {/* Autosave receipt. `role="status"` so it is announced once, not as a live alert;
                Bangkok local time HH:mm, the clock the rep sees. */}
            {autoSavedAt ? (
              <span role="status" className="text-2xs font-bold text-text-muted">
                บันทึกอัตโนมัติแล้ว {autoSavedAt.toLocaleTimeString('th-TH', { hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Asia/Bangkok' })}
              </span>
            ) : null}
          </>
        )}
        actions={(
          <>
            {quotation ? (
              // align="start": on phones the action bar is left-aligned and this is its first
              // control, so an end-aligned panel (12rem minimum) would open off the left edge.
              // The trigger's text stays its name; while a download runs the items say so.
              <OverflowMenu
                label="ดาวน์โหลด"
                triggerLabel="ดาวน์โหลด"
                triggerIcon="download"
                align="start"
                items={[
                  { key: 'pdf', label: 'PDF', icon: 'fileText', onSelect: () => handleDownload('pdf'), disabled: downloading != null, disabledReason: 'กำลังดาวน์โหลด…' },
                  { key: 'xlsx', label: 'Excel', icon: 'fileText', onSelect: () => handleDownload('xlsx'), disabled: downloading != null, disabledReason: 'กำลังดาวน์โหลด…' },
                ]}
              />
            ) : null}
            {quotation && canSubmitDealQuotation(user, quotation) ? (
              // Submit is live for PRICING_REQUEST-origin too: canSubmitDealQuotation is
              // origin-agnostic (edit access + DRAFT->PENDING_APPROVAL), so one button serves both.
              <Button
                variant="primary"
                // Disabled while ANY save is in flight (create/update/handleInlineCreate, all covered
                // by `saving`) so the confirm dialog can't open over an autosave still on the wire.
                disabled={hasValidationErrors || hasMissingLeadTimes || hasUnresolvedZeroDeposit || hasMissingCreditDays || saving}
                title={hasValidationErrors ? validationErrors.join(' ') : (leadTimeBlockMessage ?? zeroDepositBlockMessage ?? creditDaysBlockMessage ?? undefined)}
                onClick={() => setSubmitConfirmOpen(true)}
              >
                ส่งขออนุมัติ
              </Button>
            ) : null}
            {/* One approve/reject pair for both origins. ไม่อนุมัติ is canDecideDealQuotation-gated
                for either origin. อนุมัติ is hidden (not just disabled; mirrors the 403) for a
                sales_manager once the quotation no longer matches the CEO's own decision;
                canApproveDealQuotationNow holds that check, and an inline note explains the
                missing button. */}
            {quotation && canDecideDealQuotation(user, quotation) ? (
              <>
                <Button variant="danger" onClick={() => setRejectOpen(true)}>ไม่อนุมัติ</Button>
                {canApproveDealQuotationNow(user, quotation) ? (
                  <Button variant="success" onClick={() => setApproveConfirmOpen(true)}>อนุมัติ</Button>
                ) : null}
              </>
            ) : null}
            {quotation && canDecideDealQuotation(user, quotation)
                && !canApproveDealQuotationNow(user, quotation) ? (
              <p className="m-0 basis-full text-xs text-text-muted">
                มีรายการที่เปลี่ยนจากราคา CEO — ต้องให้ CEO อนุมัติ
              </p>
            ) : null}
            {quotation && canReviseDealQuotation(user, quotation, promotionTicketQuery.isSuccess ? (promotionTicketQuery.data?.orderConfirmation ?? null) : null) ? (
              <Button variant="secondary" loading={reviseMutation.isPending} onClick={() => reviseMutation.mutate()}>สร้างฉบับแก้ไข</Button>
            ) : null}
            {/* ยืนยันคำสั่งซื้อ, same words as the deal page's sticky CTA. Primary: on an APPROVED
                direct quotation it is the rep's next step once the customer orders. Once the deal
                has left 'draft' the slot becomes a plain link to the deal, so it is never offered twice. */}
            {canPromoteToDeal ? (
              promotedDealId != null ? (
                <Link
                  to={`/tickets/${promotedDealId}`}
                  className={cn(buttonVariants({ variant: 'secondary' }))}
                  data-testid="quotation-go-to-deal"
                >
                  <Icon name="chevronRight" size={14} />
                  ไปที่ดีล
                </Link>
              ) : (
                <Button
                  variant="primary"
                  loading={promoteMutation.isPending}
                  onClick={() => setPromoteConfirmOpen(true)}
                  data-testid="quotation-promote-to-deal"
                >
                  ยืนยันคำสั่งซื้อ
                </Button>
              )
            ) : null}
            {quotation && canCancelDealQuotation(user, quotation) ? (
              <Button variant="danger" onClick={() => setCancelConfirmOpen(true)}>ยกเลิกร่าง</Button>
            ) : null}
          </>
        )}
      />

      {/* "ดีล <code> · <customer> · <stage> · เปิดดีล": one flat row directly under the header so the
          deal's stage is visible where the rep works (display only; nothing here moves it).
          เปิดดีล is the one deal link on this page, hidden for a role that cannot open the deal. */}
      {!isInlineCreate && stripDeal ? (
        <div
          data-testid="quotation-deal-strip"
          className="flex min-w-0 flex-wrap items-center gap-x-2.5 gap-y-1.5 text-sm"
        >
          <span className="font-bold text-text-muted">ดีล</span>
          <code className="font-mono text-sm text-text">{stripDeal.code ?? `#${stripDealId}`}</code>
          <span aria-hidden="true" className="text-text-muted">·</span>
          <span className="min-w-0 max-w-full truncate font-bold text-text" title={stripDeal.customerName ?? undefined}>
            {stripDeal.customerName ?? '-'}
          </span>
          {stripStage ? <StatusBadge tone={stripStage.tone}>{stripStage.label}</StatusBadge> : null}
          {canOpenStripDeal ? (
            <Link
              to={`/tickets/${stripDealId}`}
              className="inline-flex min-h-[38px] items-center gap-1 rounded-md font-bold text-primary no-underline hover:text-primary-hover mobile:min-h-[44px]"
            >
              เปิดดีล
              <Icon name="chevronRight" size={14} />
            </Link>
          ) : null}
        </div>
      ) : null}

      {wasRejected ? (
        <div className="rounded-md border border-danger-border bg-danger/10 p-4 text-sm text-danger">
          <strong className="block">ไม่อนุมัติ — ส่งกลับเป็นร่าง</strong>
          <p className="m-0 mt-1">{quotation.approvalNote}</p>
        </div>
      ) : null}


      {/* Makes the family readable: a clone shares its source's base number but has no
          parentQuotationId, so nothing else says "this is an independent copy of {source}".
          The wording depends on BOTH this clone's status and the source's CURRENT (live-read) status:
          - Clone APPROVED: by the one-approved-per-deal invariant the source must be SUPERSEDED, so
            say it replaced the original.
          - Clone not approved, source still APPROVED: "stays approved until [any quotation in the
            deal] is approved"; a sibling approval replaces the source just as surely.
          - Clone not approved, source already SUPERSEDED: something else replaced it, so neither
            claim is true; say only the neutral "เป็นเอกสารอิสระ" line. */}
      {quotation?.derivedFromQuotationId ? (
        <div className="rounded-md border border-border bg-surface p-4 text-sm text-text-muted">
          สั่งเหมือนเดิมจากใบเสนอราคา{' '}
          <Link to={`/quotations/${quotation.derivedFromQuotationId}`} className="font-bold text-link underline">
            {quotation.derivedFromQuotationNumber ?? `#${quotation.derivedFromQuotationId}`}
          </Link>
          {' '}— เป็นเอกสารอิสระ
          {quotation.docStatus === 'APPROVED' ? (
            <> ใบนี้เข้ามาแทนที่ใบเดิมแล้ว (ใบเดิมถูกเปลี่ยนสถานะเป็นถูกแทนที่)</>
          ) : quotation.derivedFromQuotationStatus === 'APPROVED' ? (
            <>
              {' '}ใบเดิมยังคงสถานะอนุมัติแล้ว จนกว่าใบเสนอราคาใบใดใบหนึ่งในดีลนี้ (รวมถึงใบนี้) จะได้รับอนุมัติ
              ซึ่งจะเข้ามาแทนที่ใบที่อนุมัติอยู่ในปัจจุบันทันที
            </>
          ) : null}
        </div>
      ) : null}

      {/* "คัดลอกเป็นใบใหม่": says where this unsaved draft came from and what is still the rep's to
          fill in. A quiet provenance note, not a warning. The source's number is plain text, not a
          link: following a link from an UNSAVED draft would throw the draft away. */}
      {copyState && isEditable ? (
        <div role="status" data-testid="copy-notice" className="rounded-md border border-border bg-surface p-4 text-sm text-text-muted">
          <strong className="block text-text">คัดลอกจากใบเสนอราคา {copySource.number}</strong>
          <p className="m-0 mt-1">
            รายการสินค้าและเงื่อนไขมาจากใบเดิมแล้ว ยังต้องเลือกลูกค้าและโครงการ และเลือกผู้ติดต่อ และกรอกโทรศัพท์/อีเมลลูกค้า
            ของใบนี้ใหม่ ใบนี้จะได้เลขที่ของตัวเองเมื่อบันทึกร่าง
          </p>
          {/* Item pictures are stored against the source's rows and this editor has no way to
              attach one, so they cannot come along. Said only when there was one to lose. */}
          {(copySource.items ?? []).some((item) => item.hasPicture) ? (
            <p className="m-0 mt-1">รูปภาพประกอบรายการของใบเดิมไม่ได้คัดลอกมาด้วย</p>
          ) : null}
          {/* A ยืนราคา date that has gone by is not carried either (quotationCopy#VALIDITY),
              so ยืนราคา below is a new quotation's. Said only when that is what happened. */}
          {copyState.validityDatePassed ? (
            <p className="m-0 mt-1">วันที่ยืนราคาของใบเดิมผ่านไปแล้ว จึงไม่ได้คัดลอกมา กรุณากำหนดยืนราคาของใบนี้ใหม่</p>
          ) : null}
        </div>
      ) : null}
      {/* The link asked for a copy and there is none. Amber, not red: nothing is broken and nothing
          was lost — the form below is an ordinary blank quotation, and the last line says so. */}
      {copyRefusal && isEditable ? (
        <div role="alert" data-testid="copy-refused" className="rounded-md border border-warning-border bg-warning/10 p-4 text-sm text-warning">
          <strong className="block">คัดลอกเป็นใบใหม่ไม่ได้</strong>
          <p className="m-0 mt-1">{copyRefusal}</p>
          <p className="m-0 mt-1 text-text-muted">แบบฟอร์มด้านล่างเป็นใบเสนอราคาเปล่า กรอกใหม่ได้ตามปกติ</p>
        </div>
      ) : null}

      {!isEditable && quotation ? (
        <QuotationDocumentView quotation={quotation} />
      ) : !isEditable && !quotation ? (
        // A new-quotation visit from someone canCreateDealQuotation refuses (a read-only import/account
        // viewer on a stale link, or any role that reached /quotations/new otherwise) gets this
        // instead of the editor form.
        <Panel>
          <p className="text-danger">
            {readOnlyViewer
              ? 'บัญชีนี้มีสิทธิ์ดูใบเสนอราคาเท่านั้น ไม่สามารถสร้างใบเสนอราคาได้'
              : 'ไม่มีสิทธิ์สร้างใบเสนอราคา — สร้างได้เฉพาะพนักงานขายเจ้าของดีลหรือผู้จัดการฝ่ายขายเท่านั้น'}
          </p>
        </Panel>
      ) : (
        <>
          {/* "ข้อมูลที่ยังไม่ครบ": first in the form so it is read before ส่งขออนุมัติ (in the header).
              Hidden on a pristine /quotations/new (see showValidationSummary); shown at once on an
              existing draft. */}
          {dirty || quotation ? <QuotationChecklist entries={checklist} /> : null}

          {/* Step 1 "ดีล" on every NEW quotation: pick an existing deal (turns this page into the
              ?ticket= path) or create one inline, then ผู้รับใบเสนอราคา*. An existing quotation
              already has its deal; its recipient lives in ข้อมูลลูกค้าและผู้ขาย below. */}
          {!id ? (
            <DealCustomerCard
              user={user}
              mode={dealMode}
              onModeChange={changeDealMode}
              selectedDeal={ticketIdParam && ticket ? (partyCustomer ? { ...ticket, customerName: partyCustomer.name } : ticket) : null}
              selectedDealLive={ticketIdParam && dealParties !== undefined ? pickedDealLiveQuotation : undefined}
              attachingToDeal={attachingToDeal}
              existingDealNotice={existingDeal ? (
                <ExistingDealNotice
                  deal={existingDeal}
                  customerName={dealForm.customer?.name}
                  repName={user?.name}
                  choice={attachChoice}
                  onChoice={(next) => { setAttachChoice(next); setDirty(true); }}
                  moreCount={openDeals.length - 1}
                />
              ) : null}
              value={{ ...dealForm, recipientType }}
              onChange={updateDealForm}
              // "คัดลอกเป็นใบใหม่": same job, different bidder. The source's project name is offered
              // in the โครงการ box once the new customer is chosen, never written silently.
              suggestedProjectName={copyState?.suggestedProjectName}
              // On a new deal this is the card's own โครงการ box.
              belowProject={(
                <ProjectNamePrintToggle
                  id="hideProjectNameInline"
                  checked={terms.hideProjectName}
                  onChange={(hideProjectName) => { setTerms((t) => ({ ...t, hideProjectName })); setDirty(true); }}
                />
              )}
              errors={{
                deal: showValidationSummary && needsDeal ? 'กรุณาเลือกดีล' : undefined,
                recipient: showValidationSummary && !recipientType ? 'กรุณาเลือกผู้รับใบเสนอราคา' : undefined,
                customer: showValidationSummary && !dealForm.customer ? 'กรุณาเลือกลูกค้า' : undefined,
                project: showValidationSummary && dealForm.customer && !dealForm.project ? 'กรุณาเลือกโครงการ' : undefined,
                    // A real ช่องทางรับงาน is required before a NEW deal is created.
                entryChannel: showValidationSummary && (!dealForm.entryChannel || dealForm.entryChannel === 'UNSPECIFIED') ? 'ต้องเลือกช่องทางรับงานก่อนบันทึกร่าง' : undefined,
              }}
              showToast={showToast}
            />
          ) : null}

          {isInlineCreate ? (
            // No ticket yet: ลูกค้า/โครงการ/ช่องทาง are picked (or created) in the ดีล card above, since
            // there is nothing to summarize until the first บันทึกร่าง mints the ticket.
            <>
              <Panel title="ข้อมูลลูกค้าและผู้ขาย">
                {/* ผู้ติดต่อ, then the customer's phone/email, then the designer: one group spaced
                    like the rest of the page (gap-3). */}
                <div className="grid gap-3">
                  {dealMode === 'create' && !attachingToDeal ? (
                    <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
                      เจ้าของงาน
                      <select
                        value={effectiveDealOwnerId}
                        onChange={(e) => { setDealOwnerId(e.target.value); setDirty(true); }}
                        disabled={displayNameOptionsQuery.isLoading}
                      >
                        {user?.id != null && !displayNameOptions.some((o) => String(o.id) === String(user.id)) ? (
                          <option value={String(user.id)}>{user.name}</option>
                        ) : null}
                        {displayNameOptions.map((o) => <option key={o.id} value={String(o.id)}>{o.name}</option>)}
                      </select>
                    </label>
                  ) : null}
                  {contactPicker}
                  <OrderedByContactFields
                    terms={terms}
                    onChange={(patch) => { setTerms((current) => ({ ...current, ...patch })); setDirty(true); }}
                    idSuffix="Inline"
                  />
                  <QuotationDealFields
                    terms={terms}
                    onChange={(patch) => { setTerms((current) => ({ ...current, ...patch })); setDirty(true); }}
                    idPrefix="inline-quotation"
                    showToast={showToast}
                  />
                </div>
              </Panel>
            </>
          ) : (
            <Panel title="ข้อมูลลูกค้าและผู้ขาย">
              <div className="grid grid-cols-2 gap-3 mobile:grid-cols-1 text-sm">
                <div>
                  <span className="block text-2xs font-bold uppercase text-text-muted">ลูกค้า</span>
                  <strong>{customerName ?? '-'}</strong>
                  {canChangeCustomer ? (
                    <Button variant="secondary" size="sm" className="ml-2" onClick={() => setChangeCustomerOpen(true)}>เปลี่ยนลูกค้า</Button>
                  ) : null}
                </div>
                {/* A real field on `terms` like every other เงื่อนไข input, saved on the next draft
                    save, so a typo can be corrected. */}
                <FormField label="โครงการ" htmlFor="projectNameCard">
                  <input
                    id="projectNameCard"
                    value={terms.projectName}
                    maxLength={200}
                    onChange={(e) => { setTerms((t) => ({ ...t, projectName: e.target.value })); setDirty(true); }}
                  />
                  {/* Directly under the field it qualifies, inside the same FormField so the two stay
                      together when the grid reflows to one column on a phone. */}
                  <ProjectNamePrintToggle
                    id="hideProjectNameCard"
                    checked={terms.hideProjectName}
                    onChange={(hideProjectName) => { setTerms((t) => ({ ...t, hideProjectName })); setDirty(true); }}
                  />
                </FormField>
                {/* The same salesRepDisplayId select as the เงื่อนไข panel below, with a distinct DOM
                    id ("...Card") because ids can't repeat; both bind to `terms.salesRepDisplayId`.
                    The EMPTY option means "use the real name": its label shows the REAL rep
                    (realSalesRepName + quotation.salesRepPhone), never the currently saved override,
                    or it would show the override's name next to the choice that turns it off. The
                    phone for a selected override comes from the last SAVED value
                    (quotation.salesRepDisplayPhone); the options list carries no phone, so an
                    unsaved selection previews none until the next save. */}
                <FormField label="พนักงานขาย" htmlFor="salesRepDisplayIdCard">
                  <select
                    id="salesRepDisplayIdCard"
                    value={terms.salesRepDisplayId}
                    disabled={displayNameOptionsQuery.isLoading}
                    onChange={(e) => { setTerms((t) => ({ ...t, salesRepDisplayId: e.target.value })); setDirty(true); }}
                  >
                    <option value="">{realSalesRepName}{quotation?.salesRepPhone ? ` · T.${quotation.salesRepPhone}` : ''}</option>
                    {displayNameOptions.map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}
                  </select>
                </FormField>
                {/* ผู้รับใบเสนอราคา on an EXISTING draft (a new quotation asks in the ดีล card above).
                    DEAL_DIRECT: pill radios saved through update (DRAFT-only; this panel renders only
                    on an editable draft). PRICING_REQUEST: read-only, since the recipient belongs to
                    its คำขอราคา and update never sends one. */}
                {id && quotation ? (
                  isPricingRequestOrigin ? (
                    <div className="col-span-full" data-testid="quotation-recipient-readonly">
                      <span className="block text-sm font-bold text-text-secondary">ผู้รับใบเสนอราคา</span>
                      <span className="flex flex-wrap items-baseline gap-x-2">
                        <strong className="text-text">
                          {quotationRecipientOption(quotation.recipientType)?.label ?? 'ไม่ระบุ'}
                        </strong>
                        {/* The คำขอราคา's own free-text recipient (recipient_label), when it says more
                            than the role. */}
                        {quotation.recipientLabel
                          && quotation.recipientLabel !== quotationRecipientOption(quotation.recipientType)?.label ? (
                            <span className="text-sm text-text-secondary">{quotation.recipientLabel}</span>
                          ) : null}
                        <span className="text-xs text-text-muted">จากคำขอราคา</span>
                      </span>
                    </div>
                  ) : quotation.parentQuotationId != null ? (
                    // A revision keeps its parent's recipient; the picker would only lead to
                    // DealQuotationService#update's 409.
                    <div className="col-span-full" data-testid="quotation-recipient-revision-locked">
                      <span className="block text-sm font-bold text-text-secondary">ผู้รับใบเสนอราคา</span>
                      <strong className="text-text">
                        {quotationRecipientOption(quotation.recipientType)?.label ?? 'ไม่ระบุ'}
                      </strong>
                      <p className="m-0 mt-1 text-xs text-text-muted">{REVISION_RECIPIENT_LOCKED_MESSAGE}</p>
                    </div>
                  ) : (
                    <div className="col-span-full">
                      <QuotationRecipientField
                        value={recipientType}
                        onChange={changeRecipient}
                        idPrefix="quotation-recipient-edit"
                        // The off-route pills read this draft's deal's channel (the deal summary is
                        // fetched for a DRAFT; see ticketQuery).
                        entryChannel={ticket?.entryChannel ?? null}
                      />
                    </div>
                  )
                ) : null}
                {/* The customer's ที่อยู่ / เลขที่ผู้เสียภาษี / โทร., prefilled from the customer record and
                    editable here too (same component as the inline-create card). A save marks the
                    quotation dirty, because only the next draft save re-snapshots the customer onto
                    the document. */}
                {summaryCustomer?.id ? (
                  <div className="col-span-full">
                    <CustomerDetailsFields
                      customer={summaryCustomer}
                      onChange={(next) => { setCustomerOverride(next); setDirty(true); }}
                      showToast={showToast}
                    />
                  </div>
                ) : null}
                {contactPicker}
                {/* Directly under ผู้ติดต่อ: the customer's phone and email, side by side. */}
                <OrderedByContactFields
                  terms={terms}
                  onChange={(patch) => { setTerms((current) => ({ ...current, ...patch })); setDirty(true); }}
                  idSuffix="Card"
                />
                <QuotationDealFields
                  terms={terms}
                  onChange={(patch) => { setTerms((current) => ({ ...current, ...patch })); setDirty(true); }}
                  idPrefix="quotation"
                  showToast={showToast}
                />
              </div>
            </Panel>
          )}

          {/* ── รูปแบบเอกสาร ──
              Chosen once per quotation, above the rows it reshapes. Language comes first because it
              constrains the price modes (no ราคาพิเศษ on the English form) and carries the currency. */}
          <Panel title="รูปแบบเอกสาร">
            <div className="grid grid-cols-2 gap-3 mobile:grid-cols-1">
              <FormField
                label="ภาษาเอกสาร"
                hint={DOCUMENT_LANGUAGE_OPTIONS.find((opt) => opt.code === docSettings.documentLanguage)?.hint}
              >
                <div className="flex flex-wrap gap-2" role="group" aria-label="ภาษาเอกสาร">
                  {DOCUMENT_LANGUAGE_OPTIONS.map((opt) => (
                    <button
                      key={opt.code}
                      type="button"
                      aria-pressed={docSettings.documentLanguage === opt.code}
                      className={`min-h-[38px] mobile:min-h-[44px] rounded-md border px-3 text-xs font-bold ${docSettings.documentLanguage === opt.code ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface'}`}
                      onClick={() => changeLanguage(opt.code)}
                    >
                      {opt.label} · {opt.currency}
                    </button>
                  ))}
                </div>
              </FormField>
              <FormField
                label="วิธีกรอกราคากระเบื้อง"
                hint={isMixedPriceMode(docSettings.priceMode)
                  ? PRICE_MODE_MIXED_OPTION.hint
                  : availablePriceModes(docSettings.documentLanguage).find((opt) => opt.code === docSettings.priceMode)?.hint}
              >
                {/* One control, two kinds of answer. The three modes are the whole-document choice;
                    "ผสมหลายวิธี" lets each row decide, so it sits on its own line rather than as a
                    fourth button that reads like a fourth way to price a tile. Both lines stay in
                    one role="group" so the picker is still a single thing to a screen reader and to
                    anything that finds it by this name. */}
                <div className="flex flex-col gap-2" role="group" aria-label="วิธีกรอกราคากระเบื้อง">
                  {mixedPriceModeAvailable(docSettings.documentLanguage, quotation?.origin) ? (
                    <span className="text-2xs font-bold text-text-muted">ใช้วิธีเดียวทั้งใบ</span>
                  ) : null}
                  <div className="flex flex-wrap gap-2">
                    {/* SPECIAL_SQM is not rendered on an English document (not merely disabled): a
                        greyed-out option still invites the question and can never be accepted. */}
                    {availablePriceModes(docSettings.documentLanguage).map((opt) => (
                      <button
                        key={opt.code}
                        type="button"
                        aria-pressed={docSettings.priceMode === opt.code}
                        className={`min-h-[38px] mobile:min-h-[44px] rounded-md border px-3 text-xs font-bold ${docSettings.priceMode === opt.code ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface'}`}
                        onClick={() => changePriceMode(opt.code)}
                      >
                        {opt.label}
                      </button>
                    ))}
                  </div>
                  {/* Not rendered where it cannot be chosen (an English document, a quotation from
                      a คำขอราคา) — the same "not merely disabled" rule as SPECIAL_SQM above. */}
                  {mixedPriceModeAvailable(docSettings.documentLanguage, quotation?.origin) ? (
                    <>
                      <span className="text-2xs font-bold text-text-muted">หรือให้แต่ละรายการเลือกเอง</span>
                      <div className="flex flex-wrap items-center gap-2">
                        <button
                          type="button"
                          aria-pressed={isMixedPriceMode(docSettings.priceMode)}
                          className={`min-h-[38px] mobile:min-h-[44px] rounded-md border px-3 text-xs font-bold ${isMixedPriceMode(docSettings.priceMode) ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface'}`}
                          onClick={() => changePriceMode(PRICE_MODE_MIXED)}
                        >
                          {PRICE_MODE_MIXED_OPTION.label}
                        </button>
                        {isMixedPriceMode(docSettings.priceMode) ? (
                          <span className="text-2xs text-text-muted" data-testid="mixed-price-mode-summary">
                            ตอนนี้: {summarizeRowPriceModes(items, docSettings.documentLanguage)}
                          </span>
                        ) : null}
                      </div>
                    </>
                  ) : null}
                </div>
              </FormField>
            </div>
            {settingsNotice ? (
              <p role="status" className="m-0 mt-3 rounded-md border border-warning-border bg-warning/10 p-3 text-xs text-warning">
                {settingsNotice}
              </p>
            ) : null}
            {/* Header marker when the rep switched the whole document's price mode away from the
                CEO's decision (DealQuotationDto#priceModeChangedFromCeo). Every linked line shows its
                own marker (QuotationItemRow); this is the document-wide summary. */}
            {isPricingRequestOrigin && (quotation?.priceModeChangedFromCeo || priceModeChangedFromCeoLocally) ? (
              <p role="status" className="m-0 mt-3 rounded-md border border-warning-border bg-warning/10 p-3 text-xs font-bold text-warning">
                เปลี่ยนวิธีกรอกราคาจาก CEO — ต้องให้ CEO อนุมัติ
                <span className="ml-1 font-normal text-text-muted">
                  (CEO เลือก: {PRICE_MODE_OPTIONS.find((opt) => opt.code === quotation.ceoPriceMode)?.label ?? quotation.ceoPriceMode})
                </span>
              </p>
            ) : null}
            {/* Header marker + "คืนรายการ" when CEO-linked lines were dropped from this document
                (DealQuotationDto#itemsRemovedFromCeoCount/#removedCeoItems). Each listed line
                restores independently, since dropping was per-line. */}
            {isPricingRequestOrigin && quotation?.itemsRemovedFromCeoCount > 0 ? (
              <div role="status" className="m-0 mt-3 flex flex-col gap-2 rounded-md border border-warning-border bg-warning/10 p-3 text-xs">
                <p className="m-0 font-bold text-warning">
                  ลบรายการที่ CEO อนุมัติ {quotation.itemsRemovedFromCeoCount} รายการ
                </p>
                {(quotation.removedCeoItems ?? []).map((removed) => (
                  <div key={removed.pricingDecisionItemId} className="flex flex-wrap items-center justify-between gap-2">
                    <span className="text-text-muted">
                      {[removed.brand, removed.model, removed.color, removed.texture, removed.sizeText]
                        .filter(Boolean).join(' ')}
                    </span>
                    <Button
                      type="button"
                      variant="secondary"
                      size="sm"
                      loading={restoreItemMutation.isPending && restoreItemMutation.variables === removed.pricingDecisionItemId}
                      disabled={restoreItemMutation.isPending}
                      onClick={() => restoreItemMutation.mutate(removed.pricingDecisionItemId)}
                    >
                      คืนรายการ
                    </Button>
                  </div>
                ))}
              </div>
            ) : null}
          </Panel>

          {/* ── รายการสินค้า, grouped by ตำแหน่งติดตั้ง ──
              One ตำแหน่งติดตั้ง can hold several รายการ. Each group is a bordered block with its
              label typed once in its header (a floor with six tiles is one label, not six). Blocks
              read top-to-bottom in the order the printed document groups them (see
              locationGroupsFromItems), so the editor previews the page structure. */}
          <Panel
            title="รายการสินค้า"
            actions={isPricingRequestOrigin ? (
              // Every item on a PRICING_REQUEST quotation comes from createFromPricingRequest; adding
              // one (new ตำแหน่ง, tile, or สินค้า/บริการอื่น) is refused server-side for this origin
              // (DealQuotationService#update). Extra items are priced on the pricing request itself,
              // so every "เพิ่ม…" action below is hidden rather than shown-then-refused.
              <span className="text-2xs font-bold text-text-muted">เพิ่มรายการอื่นได้ที่คำขอราคา</span>
            ) : null}
          >
            <div className="grid gap-4">
              {groups.map((group, groupIndex) => {
                const groupItems = items.filter((it) => it.groupId === group.groupId);
                return (
                  <section key={group.groupId} className="grid gap-3 rounded-md border border-border-muted bg-surface-subtle/40 p-3">
                    <div className="flex min-w-0 flex-wrap items-end justify-between gap-2">
                      <div className="min-w-0 flex-1 basis-[18rem]">
                        <FormField
                          label={`ตำแหน่งติดตั้งที่ ${groupIndex + 1} (ไม่บังคับ)`}
                          htmlFor={`group-label-${groupIndex}`}
                          hint="เช่น ชั้น 1 - โซน A · เว้นว่างได้ (จะไม่พิมพ์หัวข้อในเอกสาร)"
                          error={duplicateGroupIds.has(group.groupId)
                            ? 'ชื่อตำแหน่งซ้ำกับตำแหน่งก่อนหน้า — เอกสารจะรวมสองตำแหน่งนี้เป็นตำแหน่งเดียว'
                            : undefined}
                        >
                          <input
                            id={`group-label-${groupIndex}`}
                            value={group.label}
                            placeholder={UNLABELLED_LOCATION_TEXT}
                            onChange={(e) => updateGroupLabel(group.groupId, e.target.value)}
                          />
                        </FormField>
                      </div>
                      {/* The group's summary stays up here with its name: row count, and the one
                          action on the group itself (remove it, when empty). */}
                      <div className="flex min-w-0 flex-wrap items-center gap-2">
                        <span className="text-2xs font-bold text-text-muted">{groupItems.length} รายการ</span>
                        {/* Only for an EMPTY group, and never for the last one standing — see
                            removeGroup. Removing a group that still holds items would delete
                            them, which this label does not say. */}
                        {groups.length > 1 && groupItems.length === 0 ? (
                          <Button variant="icon" size="sm" className="mobile:min-h-[44px] mobile:w-11" onClick={() => removeGroup(group.groupId)} title="ลบตำแหน่งนี้" aria-label={`ลบตำแหน่งติดตั้งที่ ${groupIndex + 1}`}>
                            <Icon name="close" size={16} />
                          </Button>
                        ) : null}
                      </div>
                    </div>

                    {emptyGroupIds.has(group.groupId) ? (
                      // An empty group has no item rows to carry its label, so it does not survive a
                      // save and prints nothing. Said out loud instead of silently disappearing on reload.
                      <p className="m-0 text-xs text-warning">
                        ยังไม่มีรายการในตำแหน่งนี้ — ตำแหน่งที่ไม่มีรายการจะไม่ถูกบันทึกและไม่ปรากฏในเอกสาร
                      </p>
                    ) : (
                      <ul className="m-0 grid list-none gap-3 pl-0">
                        {groupItems.map((item) => {
                          // The FLAT index — what "รายการที่ N" in the validation summary counts,
                          // and the order the document prints — never the index within the group.
                          const index = items.indexOf(item);
                          if (lineTypeOf(item) === LINE_TYPE_PLAIN) {
                            return (
                              <QuotationPlainItemRow
                                key={item.clientId}
                                item={item}
                                index={index}
                                groupId={group.groupId}
                                locationGroups={groups}
                                documentLanguage={docSettings.documentLanguage}
                                currency={currency}
                                errors={touchedRowIds.has(item.clientId) ? itemErrorsByRow[index] : EMPTY_ITEM_ERRORS}
                                onChange={(patch) => updateItem(item.clientId, patch)}
                                onRemove={() => removeItem(item.clientId)}
                                onMove={(targetGroupId) => moveItemToGroup(item.clientId, targetGroupId)}
                              />
                            );
                          }
                          return (
                            <QuotationItemRow
                              // The mode THIS row is in; on a single-mode document that is the document's.
                              priceMode={rowPriceMode(docSettings.priceMode, item)}
                              mixedPriceModes={isMixedPriceMode(docSettings.priceMode)}
                              onChangePriceMode={(mode) => changeRowPriceMode(item.clientId, mode)}
                              ceoPriceMode={quotation?.ceoPriceMode}
                              documentLanguage={docSettings.documentLanguage}
                              currency={currency}
                              key={item.clientId}
                              item={item}
                              index={index}
                              groupId={group.groupId}
                              locationGroups={groups}
                              recentPicks={recentPicks}
                              // `submitItemErrorsByRow` is `itemErrorsByRow` plus the lead-time check
                              // (a strict superset), so a row's inline hints highlight a missing lead
                              // time too without feeding the blocking checklist itemErrorsByRow drives.
                              errors={touchedRowIds.has(item.clientId) ? submitItemErrorsByRow[index] : EMPTY_ITEM_ERRORS}
                              onChange={(patch) => updateItem(item.clientId, patch)}
                              onRemove={() => removeItem(item.clientId)}
                              onMove={(targetGroupId) => moveItemToGroup(item.clientId, targetGroupId)}
                              onDuplicate={(targetGroupId) => duplicateItem(item.clientId, targetGroupId)}
                              hideDuplicate={isPricingRequestOrigin}
                              showStockOption={!isPricingRequestOrigin}
                              onCatalogPicked={handleCatalogPicked}
                            />
                          );
                        })}
                      </ul>
                    )}

                    {/* ── Add actions, at the END of the rows they append to ──
                        On a long quotation the rep finishes a row at the bottom, so the add buttons
                        sit right under it (insertIntoGroup appends to the group); addItem/
                        addPlainItem also move focus to the new row.
                        Inside the group's box on purpose: containment says "into THIS ตำแหน่ง",
                        which matters because "เพิ่มตำแหน่ง" sits just below the box.
                        `min-w-0` so the cluster can shrink: "เพิ่มรายการในตำแหน่งนี้" is a 179 px
                        unbreakable-ish Thai run and a flex item's automatic minimum size is its
                        min-content (the same mechanism that let item rows' selects force the page
                        wide; see QuotationItemRow).
                        The three kinds of "add" are told apart by shape, not position: these two are
                        the footer of their box, under a rule; "เพิ่มตำแหน่ง" is a dashed empty box
                        where the next one goes; ส่วนลดพิเศษ is its own section under a rule.
                        Hidden for a PRICING_REQUEST origin (see the Panel's "เพิ่มรายการอื่นได้ที่คำขอราคา"
                        note above). */}
                    {!isPricingRequestOrigin ? (
                      <div className="flex min-w-0 flex-wrap items-center gap-2 border-t border-border-subtle pt-3">
                        <Button variant="secondary" size="sm" onClick={() => addItem(group.groupId)}>
                          <Icon name="plus" size={13} />
                          เพิ่มรายการในตำแหน่งนี้
                        </Button>
                        {/* Freight, consumables, a cut service, sanitary ware: a row with none of the
                            tile fields. Deliberately NOT worded "เพิ่มรายการ…" (the tile button keeps
                            that name); this one says what it is for. */}
                        <Button
                          variant="secondary"
                          size="sm"
                          aria-label="เพิ่มสินค้าหรือบริการอื่นในตำแหน่งนี้"
                          onClick={() => addPlainItem(group.groupId)}
                        >
                          <Icon name="plus" size={13} />
                          สินค้า/บริการอื่น
                        </Button>
                      </div>
                    ) : null}
                  </section>
                );
              })}

              {/* "เพิ่มตำแหน่ง": under the LAST ตำแหน่ง, where the new one appears. Outside every
                  group's box because it adds a sibling, not a row inside one. The hint matches
                  เพิ่มส่วนลดพิเศษ's below and tells a ตำแหน่ง apart from a รายการ. */}
              {!isPricingRequestOrigin ? (
                <div className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-2 rounded-md border border-dashed border-border-muted p-3">
                  <Button variant="secondary" size="sm" onClick={addGroup}>
                    <Icon name="plus" size={14} />
                    เพิ่มตำแหน่ง
                  </Button>
                  <span className="text-2xs text-text-muted">ตำแหน่งติดตั้งใหม่ เช่น อีกชั้นหรืออีกโซน</span>
                </div>
              ) : null}

              {/* ── ส่วนลดพิเศษ ── always AFTER every group because it prints last (a percentage of
                  the rows above). Offered only once there is a product row to take it from; an
                  adjustment-only quotation is a server 400. */}
              <section aria-label="ส่วนลดพิเศษ" className="grid gap-3 border-t border-border pt-4">
                {adjustments.length ? (
                  <ul className="m-0 grid list-none gap-3 pl-0">
                    {adjustments.map((adjustment, adjIndex) => {
                      const clean = Boolean(quotation) && !dirty;
                      const saved = clean ? serverAdjustments[adjIndex] : null;
                      return (
                        <QuotationAdjustmentRow
                          key={adjustment.clientId}
                          adjustment={adjustment}
                          index={items.length + adjIndex}
                          currency={currency}
                          errors={touchedRowIds.has(adjustment.clientId) ? adjustmentErrorsByRow[adjIndex] : EMPTY_ITEM_ERRORS}
                          amount={saved ? Math.abs(Number(saved.lineAmount)) : adjustmentEstimates[adjIndex]}
                          description={saved ? saved.descriptionLine : adjustmentDescriptionPreview(adjustment, docSettings.documentLanguage)}
                          estimated={!saved}
                          onChange={(patch) => {
                            setTouchedRowIds((prev) => (prev.has(adjustment.clientId) ? prev : new Set(prev).add(adjustment.clientId)));
                            updateAdjustment(adjustment.clientId, patch);
                          }}
                          onRemove={() => removeAdjustment(adjustment.clientId)}
                        />
                      );
                    })}
                  </ul>
                ) : null}
                {/* Hidden for PRICING_REQUEST: ADJUSTMENT rows are an "extra item" too, refused
                    server-side like TILE/PLAIN rows (DealQuotationService#update). */}
                {!isPricingRequestOrigin ? (
                  <div className="flex min-w-0 flex-wrap items-center gap-2">
                    <Button variant="secondary" size="sm" disabled={items.length === 0} onClick={addAdjustment}>
                      <Icon name="plus" size={13} />
                      เพิ่มส่วนลดพิเศษ
                    </Button>
                    <span className="text-2xs text-text-muted">
                      {items.length === 0
                        ? 'เพิ่มรายการสินค้าก่อน จึงจะใส่ส่วนลดพิเศษได้'
                        : 'คิดจากยอดรวมรายการข้างบนทั้งหมด และพิมพ์เป็นรายการสุดท้ายเสมอ'}
                    </span>
                  </div>
                ) : null}
              </section>
            </div>
          </Panel>

          <Panel title="เงื่อนไข">
            <div className="grid grid-cols-2 gap-3 mobile:grid-cols-1">
              {/* This is the date the CUSTOMER supplied the quantities being quoted, not the
                  quotation's own date. The document says so verbatim in remark 1
                  (DealQuotationRenderAdapter: "1.จำนวนที่เสนอข้างต้นเป็นจำนวนที่ได้รับมาเมื่อวันที่ …"), so
                  label and hint match that sentence. The quotation's own date is `createdAt` and is
                  not editable here. */}
              <FormField
                label="วันที่ที่ได้รับจำนวนจากลูกค้า"
                htmlFor="offerDate"
                hint="พิมพ์ในหมายเหตุข้อ 1: จำนวนที่เสนอข้างต้นเป็นจำนวนที่ได้รับมาเมื่อวันที่ …"
              >
                <ThaiDateInput id="offerDate" value={terms.offerDate} onChange={(e) => { setTerms((t) => ({ ...t, offerDate: e.target.value })); setDirty(true); }} />
              </FormField>
              {/* "ไม่รับมัดจำ": 0% is not a percentage a rep types (the custom input rejects it; see
                  depositZeroError), so a document with no deposit ticks this box. Ticking swaps out
                  BOTH the % chips/custom input AND the ส่วนที่เหลือ controls (a remainder is
                  meaningless with no deposit) for the fixed เงื่อนไขการชำระเงิน select. Label/htmlFor
                  swap with it, so the one FormField names whichever control it currently shows. */}
              <FormField
                label={terms.noDeposit ? 'เงื่อนไขการชำระเงิน' : 'มัดจำ %'}
                htmlFor={terms.noDeposit ? 'fullPaymentTerm' : 'depositPercent'}
                error={terms.noDeposit ? undefined : depositZeroError}
              >
                <label
                  htmlFor="noDeposit"
                  className="mb-2 flex min-h-[38px] w-fit cursor-pointer items-center gap-2 rounded-md px-1 py-1 mobile:min-h-[44px] mobile:py-2"
                >
                  <input
                    id="noDeposit"
                    type="checkbox"
                    className="h-4 w-4 shrink-0"
                    checked={terms.noDeposit}
                    onChange={(e) => {
                      const noDeposit = e.target.checked;
                      // Unticking clears fullPaymentTerm (meaningless again, and it would resurface
                      // stale on re-tick); ticking leaves depositPercent/remainderMode/creditDays on
                      // screen, since #buildUpsertPayload is what forces them to 0/null on save and
                      // re-unticking mid-edit should show the last typed values, not blanks.
                      setTerms((t) => ({ ...t, noDeposit, fullPaymentTerm: noDeposit ? t.fullPaymentTerm : '' }));
                      setDirty(true);
                    }}
                  />
                  <span className="text-xs font-bold">ไม่รับมัดจำ</span>
                </label>
                {terms.noDeposit ? (
                  <select
                    id="fullPaymentTerm"
                    className="w-full"
                    value={terms.fullPaymentTerm}
                    onChange={(e) => { setTerms((t) => ({ ...t, fullPaymentTerm: e.target.value })); setDirty(true); }}
                  >
                    <option value="">- เลือกเงื่อนไข -</option>
                    {FULL_PAYMENT_TERM_OPTIONS.map((opt) => (
                      <option key={opt.code} value={opt.code}>{opt.label}</option>
                    ))}
                  </select>
                ) : (
                  <div className="flex flex-wrap gap-2">
                    {DEPOSIT_PERCENT_PRESETS.map((pct) => (
                      <button
                        key={pct}
                        type="button"
                        aria-pressed={!terms.depositPercentCustom && Number(terms.depositPercent) === pct}
                        className={`min-h-[38px] mobile:min-h-[44px] rounded-md border px-3 text-xs font-bold ${!terms.depositPercentCustom && Number(terms.depositPercent) === pct ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface'}`}
                        onClick={() => { setTerms((t) => ({ ...t, depositPercent: pct, depositPercentCustom: false })); setDirty(true); }}
                      >
                        {pct}%
                      </button>
                    ))}
                    <button
                      type="button"
                      aria-pressed={terms.depositPercentCustom}
                      className={`min-h-[38px] mobile:min-h-[44px] rounded-md border px-3 text-xs font-bold ${terms.depositPercentCustom ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface'}`}
                      onClick={() => { setTerms((t) => ({ ...t, depositPercentCustom: true })); setDirty(true); }}
                    >
                      อื่นๆ
                    </button>
                    {terms.depositPercentCustom ? (
                      <input
                        id="depositPercent"
                        type="number"
                        className="w-20"
                        value={terms.depositPercent}
                        onChange={(e) => { setTerms((t) => ({ ...t, depositPercent: e.target.value })); setDirty(true); }}
                      />
                    ) : null}
                  </div>
                )}
              </FormField>
              {/* No `htmlFor`: ส่วนที่เหลือ is a SET of toggle buttons, not one control (an id on it
                  would belong to the เครดิต day-count input, which exists only while CREDIT is
                  selected). `role="group"` + aria-label carries the one name; the day input carries
                  its own. Hidden under "ไม่รับมัดจำ": no deposit, no remainder. */}
              {terms.noDeposit ? null : (
              <FormField label="ส่วนที่เหลือ">
                <div className="flex flex-wrap items-center gap-2" role="group" aria-label="ส่วนที่เหลือ">
                  {REMAINDER_MODE_OPTIONS.map((opt) => (
                    <button
                      key={opt.code}
                      type="button"
                      aria-pressed={terms.remainderMode === opt.code}
                      className={`min-h-[38px] mobile:min-h-[44px] rounded-md border px-3 text-xs font-bold ${terms.remainderMode === opt.code ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface'}`}
                      onClick={() => { setTerms((t) => ({ ...t, remainderMode: opt.code })); setDirty(true); }}
                    >
                      {opt.label}
                    </button>
                  ))}
                  {terms.remainderMode === 'CREDIT' ? (
                    <span className="inline-flex items-center gap-1.5 text-xs text-text-muted">
                      เครดิต
                      <input
                        id="creditDays"
                        /* NOT "จำนวนวันเครดิต": a name STARTING with "จำนวน" collides with the item
                           row's own จำนวน field under the prefix queries the tests use, and this input
                           renders only in CREDIT mode, so a test's outcome would depend on which test
                           ran before it. Leading with เครดิต keeps the name unambiguous. */
                        aria-label="เครดิต (จำนวนวัน)"
                        type="number"
                        className="w-16"
                        value={terms.creditDays}
                        onChange={(e) => { setTerms((t) => ({ ...t, creditDays: e.target.value })); setDirty(true); }}
                      />
                      วัน
                    </span>
                  ) : null}
                </div>
              </FormField>
              )}
              {/* ระบุวันที่ ("กำหนดวันที่ได้") is a second way to say กำหนดยืนยันราคา, for when a
                  promotion or factory allocation needs an exact deadline. It only makes sense on a
                  document that HAS special pricing to protect, so the option is hidden otherwise
                  (docHasSpecialPricing, mirrored from the server's gate). No `htmlFor`, same
                  reasoning as ส่วนที่เหลือ: a group of controls, not one. */}
              <FormField label="ยืนราคา">
                <div className="flex flex-wrap items-center gap-2" role="group" aria-label="ยืนราคา">
                  {(docHasSpecialPricing ? VALIDITY_MODE_OPTIONS
                    : VALIDITY_MODE_OPTIONS.filter((opt) => opt.code === 'DAYS')).map((opt) => (
                    <button
                      key={opt.code}
                      type="button"
                      aria-pressed={terms.validityMode === opt.code}
                      className={`min-h-[38px] mobile:min-h-[44px] rounded-md border px-3 text-xs font-bold ${terms.validityMode === opt.code ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface'}`}
                      onClick={() => { setTerms((t) => ({ ...t, validityMode: opt.code })); setDirty(true); }}
                    >
                      {opt.label}
                    </button>
                  ))}
                  {terms.validityMode === 'DATE' ? (
                    <ThaiDateInput
                      id="validityUntil"
                      aria-label="ยืนราคาถึงวันที่"
                      value={terms.validityUntil}
                      onChange={(e) => { setTerms((t) => ({ ...t, validityUntil: e.target.value })); setDirty(true); }}
                    />
                  ) : (
                    // จำนวนวัน keeps its own select while ระบุวันที่ shows: the value is never cleared on
                    // a mode switch (see #buildUpsertPayload), so switching back always has a real
                    // number. FormField's <label> is a sibling of its children, so it labels nothing
                    // without htmlFor; same collision as the ส่วนที่เหลือ block above: the accessible
                    // name must NOT start with "จำนวน".
                    <select id="validityDays" aria-label="ยืนราคา (จำนวนวัน)" value={terms.validityDays} onChange={(e) => { setTerms((t) => ({ ...t, validityDays: e.target.value })); setDirty(true); }}>
                      <option value="">-</option>
                      {VALIDITY_DAYS_OPTIONS.map((d) => <option key={d} value={d}>{d} วัน</option>)}
                    </select>
                  )}
                </div>
              </FormField>
              {/* PRINT-ONLY name selection (admin helps build a quotation: print the admin as the
                  typist and the rep as the sales name). Neither field changes who created/owns the
                  deal, who may edit it or who earns commission (createdById/salesRepId are
                  untouched); only the two printed names. "(ค่าเริ่มต้น)" (empty, value="") sends null
                  and prints the real creator/sales rep. Both selects share the same eligible-employee
                  list (ฝ่ายขาย members plus can_create_quotation grant holders), fetched once above. */}
              <FormField
                label="แสดงชื่อผู้พิมพ์เป็น"
                htmlFor="printedByDisplayId"
                hint="ค่าเริ่มต้น = ชื่อผู้สร้างใบเสนอราคาจริง (ใช้เมื่อแอดมินช่วยทำใบเสนอราคาแทนเซลล์)"
              >
                <select
                  id="printedByDisplayId"
                  value={terms.printedByDisplayId}
                  disabled={displayNameOptionsQuery.isLoading}
                  onChange={(e) => { setTerms((t) => ({ ...t, printedByDisplayId: e.target.value })); setDirty(true); }}
                >
                  <option value="">(ค่าเริ่มต้น)</option>
                  {displayNameOptions.map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}
                </select>
              </FormField>
              <FormField
                label="แสดงชื่อพนักงานขายเป็น"
                htmlFor="salesRepDisplayId"
                hint="ค่าเริ่มต้น = ชื่อพนักงานขายจริง — มีผลถึงเบอร์โทรบนหัวเอกสารด้วย"
              >
                <select
                  id="salesRepDisplayId"
                  value={terms.salesRepDisplayId}
                  disabled={displayNameOptionsQuery.isLoading}
                  onChange={(e) => { setTerms((t) => ({ ...t, salesRepDisplayId: e.target.value })); setDirty(true); }}
                >
                  <option value="">(ค่าเริ่มต้น)</option>
                  {displayNameOptions.map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}
                </select>
              </FormField>
            </div>
            {/* One FormGrid gap below the grid above; a bare FormField has no margin of its own. */}
            <div className="mt-3.5">
              <FormField label="หมายเหตุเพิ่มเติม" htmlFor="customerNotes">
                <textarea
                  id="customerNotes"
                  className="min-h-24"
                  value={terms.customerNotes}
                  onChange={(e) => { setTerms((t) => ({ ...t, customerNotes: e.target.value })); setDirty(true); }}
                />
              </FormField>
            </div>
          </Panel>

          {/* Above ยอดรวม so a rep attaches while still filling in. An upload is stored at once (or,
              before the first save, held and sent by it), so it is not part of what บันทึกร่าง saves
              and never blocks ส่งขออนุมัติ. */}
          <QuotationAttachmentsPanel
            user={user}
            quotation={quotation}
            pending={pendingAttachments}
            onPendingChange={setPendingAttachments}
            busy={!quotation && saving}
          />

          <Panel title="ยอดรวม">
            {/* An English document prints Grand Total (USD) and NO VAT row, so the editor shows no
                VAT line either (a "0.00" one would imply a rate the English form lacks). */}
            {quotation && !dirty ? (
              <div className="flex flex-col items-end gap-1">
                <span className="text-sm text-text-muted">รวมเป็นเงิน{isEnglish ? ' (USD)' : ''} <span className="tabular-nums text-text">{formatQuotationMoney(quotation.subtotalAmount, currency)}</span></span>
                {!isEnglish ? (
                  <span className="text-sm text-text-muted">ภาษีมูลค่าเพิ่ม 7% <span className="tabular-nums text-text">{formatQuotationMoney(quotation.vatAmount, currency)}</span></span>
                ) : null}
                <span className="text-lg font-extrabold">ยอดรวมทั้งสิ้น{isEnglish ? ' (USD)' : ''} <span className="tabular-nums">{formatQuotationMoney(quotation.grandTotal, currency)}</span></span>
                {isEnglish ? <span className="text-2xs text-text-muted">เอกสารภาษาอังกฤษไม่มีภาษีมูลค่าเพิ่ม</span> : null}
              </div>
            ) : (
              // Computed client-side from `items` (not a bare "ยังไม่บันทึก") so sales gets a live
              // total while typing, labelled an estimate since the authoritative rounding is
              // server-side (WastageCalculator).
              <div className="flex flex-col items-end gap-1">
                <p className="m-0 text-xs font-bold text-warning">ยอดโดยประมาณ (ยังไม่บันทึก)</p>
                <span className="text-sm text-text-muted">รวมเป็นเงิน{isEnglish ? ' (USD)' : ''} <span className="tabular-nums text-text">{formatQuotationMoney(provisionalSubtotal, currency)}</span></span>
                {!isEnglish ? (
                  <span className="text-sm text-text-muted">ภาษีมูลค่าเพิ่ม 7% <span className="tabular-nums text-text">{formatQuotationMoney(provisionalVat, currency)}</span></span>
                ) : null}
                <span className="text-lg font-extrabold">ยอดรวมทั้งสิ้น{isEnglish ? ' (USD)' : ''} <span className="tabular-nums">{formatQuotationMoney(provisionalGrand, currency)}</span></span>
                {isEnglish ? <span className="text-2xs text-text-muted">เอกสารภาษาอังกฤษไม่มีภาษีมูลค่าเพิ่ม</span> : null}
                {/* The server refuses a document whose total goes negative (large adjustments, or a
                    flat one bigger than the rows). Warned here from the estimate; save is NOT blocked
                    because the estimate can lag a pending preview; the server's 400 is the authority. */}
                {provisionalSubtotal < 0 ? (
                  <p className="m-0 text-xs font-bold text-danger">ส่วนลดพิเศษมากกว่ายอดรวมรายการ — ระบบจะไม่รับบันทึกยอดติดลบ</p>
                ) : null}
              </div>
            )}
            {saveError ? (
              <p role="alert" className="m-0 mt-3 rounded-md border border-danger-border bg-danger/10 p-3 text-xs text-danger">
                บันทึกไม่สำเร็จ: {saveError}
                {/* The server named the live quotation: link to it instead of retyping. */}
                {saveErrorLiveQuotationId != null ? (
                  <>
                    {' '}
                    <Link to={`/quotations/${saveErrorLiveQuotationId}`} className="font-bold underline">เปิดใบเสนอราคา</Link>
                  </>
                ) : null}
              </p>
            ) : null}
          </Panel>

          {isEditable ? (
            // `justify-stretch` governs the CROSS axis, not a flex main-axis item's own size, so on
            // mobile:flex-row the button stayed small and left-aligned; it needs `mobile:w-full`.
            <div className="flex justify-end rounded-md border border-border-muted bg-surface p-4">
              <Button
                variant="secondary"
                className="mobile:w-full"
                loading={saving}
                // Disabled while ส่งขออนุมัติ runs its own pre-save PUT too, so a manual save can't race it.
                disabled={hasValidationErrors || submitMutation.isPending}
                title={hasValidationErrors ? validationErrors.join(' ') : undefined}
                onClick={handleSaveDraft}
              >
                บันทึกร่าง
              </Button>
            </div>
          ) : null}
        </>
      )}

      {/* The read-only details end with เอกสารแนบ (the header link jumps to it). Outside the
          details' panels on purpose: they reproduce the printed form and these files are never
          printed. The edit form draws it above ยอดรวม instead. Nothing for import and account. */}
      {quotation && !isEditable ? <QuotationAttachmentsPanel user={user} quotation={quotation} /> : null}

      {submitConfirmOpen ? (
        <Modal
          title="ส่งขออนุมัติใบเสนอราคา"
          onClose={() => setSubmitConfirmOpen(false)}
          footer={(
            <>
              <Button variant="secondary" onClick={() => setSubmitConfirmOpen(false)}>ยกเลิก</Button>
              {/* Disabled while any OTHER save is in flight: the open dialog suppresses the next
                  autosave tick but does not cancel one already on the wire. */}
              <Button
                variant="primary"
                loading={submitMutation.isPending}
                // Defensive: the opening button is already disabled while `hasMissingLeadTimes`/
                // `hasUnresolvedZeroDeposit`/`hasMissingCreditDays`, so this only matters if an edit
                // mid-dialog removed a lead time, re-typed a 0% deposit, or blanked a CREDIT
                // remainder's days.
                disabled={saving || hasMissingLeadTimes || hasUnresolvedZeroDeposit || hasMissingCreditDays}
                title={leadTimeBlockMessage ?? zeroDepositBlockMessage ?? creditDaysBlockMessage ?? undefined}
                onClick={() => submitMutation.mutate()}
              >
                ส่งขออนุมัติ
              </Button>
            </>
          )}
        >
          <p>ส่งใบเสนอราคา {quotation?.number} ให้ผู้จัดการฝ่ายขายหรือผู้บริหารอนุมัติ ต้องการดำเนินการต่อหรือไม่</p>
          {/* Unlike checklistWarnings below, these THREE genuinely block (the opening button is
              disabled while any is true), so they only show if the dialog was already open when a
              concurrent edit removed a lead time, re-typed a 0% deposit, or blanked a CREDIT
              remainder's days. */}
          {leadTimeBlockMessage ? (
            <p role="alert" className="mt-3 rounded-md border border-danger-border bg-danger/10 p-3 text-xs text-danger">
              {leadTimeBlockMessage}
            </p>
          ) : null}
          {zeroDepositBlockMessage ? (
            <p role="alert" className="mt-3 rounded-md border border-danger-border bg-danger/10 p-3 text-xs text-danger">
              {zeroDepositBlockMessage}
            </p>
          ) : null}
          {creditDaysBlockMessage ? (
            <p role="alert" className="mt-3 rounded-md border border-danger-border bg-danger/10 p-3 text-xs text-danger">
              {creditDaysBlockMessage}
            </p>
          ) : null}
          {/* The optional gaps, restated at the moment of sending: never a blocker (see
              QUOTATION_BLOCKING_CHECKS), but the last chance to notice the document prints without them. */}
          {checklistWarnings.length ? (
            <div className="mt-3 rounded-md border border-warning-border bg-warning/10 p-3 text-xs text-warning" data-testid="submit-warnings">
              <strong className="block">ยังไม่ได้กรอก (ไม่บังคับ — ส่งได้ แต่ข้อมูลนี้จะว่างในเอกสาร)</strong>
              <ul className="m-0 mt-1 list-disc pl-4">
                {checklistWarnings.map((entry) => <li key={entry.check}>{entry.message}</li>)}
              </ul>
            </div>
          ) : null}
          {dirty ? <p className="mt-2 text-xs text-text-muted">การแก้ไขที่ยังไม่บันทึกจะถูกบันทึกก่อนส่ง</p> : null}
        </Modal>
      ) : null}

      {approveConfirmOpen ? (
        // Same confirm-Modal shape as ส่งขออนุมัติ/ยกเลิกร่าง above, so อนุมัติ doesn't fire straight off a click.
        <Modal
          title="อนุมัติใบเสนอราคา"
          onClose={() => setApproveConfirmOpen(false)}
          footer={(
            <>
              <Button variant="secondary" onClick={() => setApproveConfirmOpen(false)}>ยกเลิก</Button>
              <Button
                variant="success"
                loading={approveMutation.isPending}
                onClick={() => approveMutation.mutate(undefined)}
              >
                ยืนยันอนุมัติ
              </Button>
            </>
          )}
        >
          <p>
            อนุมัติใบเสนอราคา {quotation?.number} ยอดรวมทั้งสิ้น {formatQuotationMoney(quotation?.grandTotal, quotation?.currency)} ต้องการดำเนินการต่อหรือไม่
          </p>
          {/* "Sales may change the price but the CEO must approve": every changed line, the header
              mode change and the removed-item count are surfaced HERE at approval rather than left
              for the approver to notice while scrolling. Rendered only for a PRICING_REQUEST row;
              a DEAL_DIRECT approval sees none of this. */}
          {isPricingRequestOrigin ? (
            <div className="mt-3 flex flex-col gap-2 rounded-md border border-warning-border bg-warning/10 p-3 text-xs">
              {quotation?.priceModeChangedFromCeo ? (
                <p className="m-0 font-bold text-warning">
                  เปลี่ยนวิธีกรอกราคาจาก CEO
                  <span className="ml-1 font-normal text-text-muted">
                    (CEO เลือก: {PRICE_MODE_OPTIONS.find((opt) => opt.code === quotation.ceoPriceMode)?.label ?? quotation.ceoPriceMode})
                  </span>
                </p>
              ) : null}
              {(quotation?.items ?? []).filter((item) => item.priceChangedFromCeo).map((item) => (
                <p key={item.id} className="m-0 flex flex-wrap items-baseline gap-x-2 gap-y-0.5 font-bold text-warning">
                  <span>{[item.brand, item.model, item.color, item.texture].filter(Boolean).join(' ') || `รายการ ${item.seq}`}</span>
                  <span className="font-normal text-text-muted">
                    {ceoOriginalPriceText(quotation.ceoPriceMode ?? quotation.priceMode, item, quotation.currency)}
                  </span>
                </p>
              ))}
              {quotation?.itemsRemovedFromCeoCount > 0 ? (
                <p className="m-0 font-bold text-warning">
                  ลบรายการที่ CEO อนุมัติ {quotation.itemsRemovedFromCeoCount} รายการ
                </p>
              ) : null}
              {!quotation?.priceModeChangedFromCeo && !quotation?.itemsRemovedFromCeoCount
                && !(quotation?.items ?? []).some((item) => item.priceChangedFromCeo) ? (
                <p className="m-0 flex items-center gap-1.5 font-bold text-text-muted">
                  <Icon name="check" size={12} /> ราคาทุกรายการตรงกับที่ CEO อนุมัติ ไม่มีการเปลี่ยนแปลง
                </p>
              ) : null}
            </div>
          ) : null}
        </Modal>
      ) : null}

      {changeCustomerOpen ? (
        <ChangeCustomerModal
          ticketId={effectiveTicketId}
          showToast={showToast}
          onClose={() => setChangeCustomerOpen(false)}
          onChanged={async (project) => {
            // The server re-snapshotted every draft of the deal in the same call, so re-read the deal
            // and this quotation; there is nothing left for a save to do. The one piece of local
            // state still holding the old value is the project field (every save sends it in full):
            // seed it from the project the server just wrote.
            await Promise.all([
              queryClient.invalidateQueries({ queryKey: queryKeys.ticketDetail(effectiveTicketId) }),
              queryClient.invalidateQueries({ queryKey: queryKeys.dealQuotationDetail(id) }),
            ]);
            setTerms((t) => ({ ...t, projectName: project.name }));
            // The server dropped the old customer's contact from every draft; so does the picker.
            setContact(null);
            showToast('success', 'เปลี่ยนลูกค้าแล้ว');
          }}
        />
      ) : null}

      {rejectOpen ? (
        <Modal
          title="ไม่อนุมัติใบเสนอราคา"
          onClose={() => setRejectOpen(false)}
          footer={(
            <>
              <Button variant="secondary" onClick={() => setRejectOpen(false)}>ยกเลิก</Button>
              <Button
                variant="danger"
                loading={rejectMutation.isPending}
                disabled={!rejectReason.trim()}
                onClick={() => rejectMutation.mutate(rejectReason.trim())}
              >
                ยืนยันไม่อนุมัติ
              </Button>
            </>
          )}
        >
          <FormField label="เหตุผลที่ไม่อนุมัติ" htmlFor="reject-reason" required>
            <textarea id="reject-reason" className="min-h-24" value={rejectReason} onChange={(e) => setRejectReason(e.target.value)} />
          </FormField>
        </Modal>
      ) : null}

      {cancelConfirmOpen ? (
        <Modal
          title="ยกเลิกร่างใบเสนอราคา"
          onClose={() => setCancelConfirmOpen(false)}
          footer={(
            <>
              <Button variant="secondary" onClick={() => setCancelConfirmOpen(false)}>ปิด</Button>
              <Button variant="danger" loading={cancelMutation.isPending} onClick={() => cancelMutation.mutate()}>ยืนยันยกเลิก</Button>
            </>
          )}
        >
          <p>ยกเลิกร่างใบเสนอราคา {quotation?.number} — ไม่สามารถกู้คืนได้ ต้องการดำเนินการต่อหรือไม่</p>
        </Modal>
      ) : null}

      {/* Promotion is not reversible (nothing un-promotes a deal), so it is confirmed, and the
          dialog says exactly where the deal lands and what it carries over. */}
      {promoteConfirmOpen ? (
        <Modal
          title="ยืนยันคำสั่งซื้อ"
          onClose={() => setPromoteConfirmOpen(false)}
          footer={(
            <>
              <Button variant="secondary" onClick={() => setPromoteConfirmOpen(false)}>ปิด</Button>
              <Button
                variant="primary"
                loading={promoteMutation.isPending}
                onClick={() => { setPromoteConfirmOpen(false); promoteMutation.mutate(); }}
                data-testid="quotation-promote-to-deal-confirm"
              >
                ยืนยันคำสั่งซื้อ
              </Button>
            </>
          )}
        >
          {/* The copy lives in quotationMeta.js so the deal page's ยืนยันคำสั่งซื้อ CTA says exactly the
              same thing (TicketDetailPage renders the same three parts). */}
          <p className="m-0">
            {CONFIRM_ORDER_FROM_QUOTATION_COPY.lead(quotation?.number)} <strong>{CONFIRM_ORDER_FROM_QUOTATION_COPY.stage}</strong>
            {' '}{CONFIRM_ORDER_FROM_QUOTATION_COPY.tail(quotation?.depositPercent)}
          </p>
        </Modal>
      ) : null}
      {/* "ผสมหลายวิธี" → one mode for the whole document. Asked only when it would re-price rows
          (#changePriceMode); the title names the mode and the body the number of rows. Cancelling
          leaves every row, and the mix, as they were. */}
      <ConfirmDialog
        open={leaveMixedConfirm != null}
        title={`ใช้ “${availablePriceModes(docSettings.documentLanguage).find((opt) => opt.code === leaveMixedConfirm?.priceMode)?.label ?? ''}” ทั้งใบ?`}
        message={`มี ${leaveMixedConfirm?.count ?? 0} รายการที่กรอกราคาด้วยวิธีอื่นอยู่ — เมื่อเปลี่ยนแล้ว รายการเหล่านั้นจะคิดราคาด้วยวิธีนี้แทน และต้องตรวจหรือกรอกราคาใหม่ตามวิธีนี้`}
        confirmLabel="เปลี่ยนทั้งใบ"
        cancelLabel="ยกเลิก"
        onConfirm={() => applyOnePriceModeToTheDocument(leaveMixedConfirm.priceMode)}
        onCancel={() => setLeaveMixedConfirm(null)}
      />
    </PageStack>
  );
}
