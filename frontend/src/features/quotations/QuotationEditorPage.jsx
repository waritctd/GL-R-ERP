import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { api } from '../../api/index.js';
import { canAccessPath } from '../../app/permissions.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { FormField } from '../../components/common/FormField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Panel, PageStack } from '../../components/common/Layout.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { RouteFallback } from '../../components/common/RouteFallback.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { downloadBlob } from '../../utils/download.js';
import { addDaysIso, bangkokTodayIso } from '../../utils/format.js';
import {
  canApproveDealQuotation, canCancelDealQuotation, canCreateDealQuotation,
  canCreateDealQuotationStandalone, canDecideDealQuotation, canEditDealQuotation,
  canReviseDealQuotation, canSubmitDealQuotation, DEPOSIT_PERCENT_PRESETS,
  availablePriceModes, adjustmentDescriptionPreview, buildQuotationChecklist, currencyForLanguage, DOCUMENT_LANGUAGE_OPTIONS,
  estimateAdjustmentAmount, formatQuotationMoney, hasSpecialPricing, LINE_TYPE_ADJUSTMENT, LINE_TYPE_PLAIN, LINE_TYPE_TILE,
  lineTypeOf, priceModeForLanguage, rowHasPriceForPreview, rowsWithPricesCleared, validateAdjustment, vatRateForLanguage,
  dealQuotationStatusLabel, isDealQuotationEditable, isDealQuotationReadOnlyViewer,
  duplicateLocationLabelGroupIds, emptyLocationGroupIds,
  locationGroupsFromItems, newLocationGroupId,
  REMAINDER_MODE_OPTIONS, UNLABELLED_LOCATION_TEXT, validateQuotationItem, VALIDITY_DAYS_OPTIONS,
  VALIDITY_MODE_OPTIONS,
} from './quotationMeta.js';
import { CustomerDetailsFields } from './CustomerDetailsFields.jsx';
import { DealCustomerCard } from './DealCustomerCard.jsx';
import { QuotationDealFields } from './QuotationDealFields.jsx';
import { QuotationChecklist } from './QuotationChecklist.jsx';
import { QuotationContactPicker } from './QuotationContactPicker.jsx';
import { QuotationDocumentView } from './QuotationDocumentView.jsx';
import {
  adjustmentInputFromRow, emptyAdjustment, emptyPlainItem, emptyQuotationItem, itemInputFromRow,
  newItemClientId, QuotationItemRow, rowFromServerItem,
} from './QuotationItemRow.jsx';
import { QuotationPlainItemRow } from './QuotationPlainItemRow.jsx';
import { QuotationAdjustmentRow } from './QuotationAdjustmentRow.jsx';
import {
  pushRecentCatalogPick, readQuotationDefaults, readRecentCatalogPicks, writeQuotationDefaults,
} from './quotationPrefs.js';

// #M3: Bangkok LOCAL calendar date, not `new Date().toISOString().slice(0, 10)` -- that reads
// the UTC calendar day off a UTC instant, which is one day BEHIND the Bangkok date for any
// session open between 00:00-06:59 Bangkok time (UTC+7). bangkokTodayIso (utils/format.js) is
// this repo's existing fix for exactly this class of bug (see its own doc comment).
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

// Autosave debounce (owner ask 2026-09-10). Two seconds is long enough that it never fires
// mid-word and short enough that a rep who closes the tab loses at most one field.
export const AUTOSAVE_DELAY_MS = 2000;

function pickCalculatedFields(source) {
  const result = {};
  for (const key of CALCULATED_ITEM_FIELDS) {
    if (key in source) result[key] = source[key];
  }
  return result;
}

// `defaults` is readQuotationDefaults()'s own return value (quotationPrefs.js) -- the rep's last
// saved terms, or null. Only ever seeds a BRAND-NEW quotation; an existing one always loads its
// own stored values (see the init effect below), so a remembered default can never overwrite what
// is on the server. วันที่ is deliberately never remembered: it is always today.
function emptyTerms(defaults = null) {
  const depositPercent = defaults?.depositPercent ?? '';
  return {
    deptCode: '', unitCode: '', offerDate: todayIso(),
    depositPercent,
    depositPercentCustom: depositPercent !== '' && !DEPOSIT_PERCENT_PRESETS.includes(Number(depositPercent)),
    remainderMode: defaults?.remainderMode ?? '',
    creditDays: defaults?.creditDays ?? '',
    validityDays: defaults?.validityDays ?? '',
    // V178: the MODE remembers (see quotationPrefs.js); validityUntil never does — a brand-new
    // quotation always starts with no date typed, even when it starts in DATE mode.
    validityMode: defaults?.validityMode ?? 'DAYS',
    validityUntil: '',
    customerNotes: '',
    // V179 (owner feedback #4, 2026-09-14) — ผู้พิมพ์/พนักงานขาย print-name override. Neither
    // remembers a default (unlike validityMode above): the override is deal/document-specific
    // ("admin filled this one in for the rep"), not a rep-wide preference, so a brand-new
    // quotation always starts with "(ค่าเริ่มต้น)" — use the real names.
    printedByDisplayId: '',
    salesRepDisplayId: '',
    // Owner feedback 2026-09-14 ("sometimes there's a typo in the ... project so they should be
    // able to correct it"): a brand-new /quotations/new visit has no ticket yet either, so this
    // starts blank same as everything else here -- the ?ticket= path below fills it from the
    // deal's own project name instead of leaving a rep looking at an empty box for a project the
    // deal already has.
    projectName: '',
  };
}

// Owner ask 2026-09-10 ("inline deal creation", inline-deal-spec.md): the "ลูกค้าและโครงการ" card
// state for a brand-new /quotations/new visit with no `?ticket=` -- nothing here exists on the
// server yet, unlike `terms`/`items` which round-trip a real ticket/quotation.
// ผู้สั่งซื้อ is NOT here: it is `contact` state on the page itself, because owner feedback F2
// (2026-09-10) made it mandatory on all three entry paths and only ONE of them renders this card.
function emptyDealForm() {
  return { customer: null, project: null, entryChannel: 'UNSPECIFIED' };
}

// v3 / v3b (owner feedback pass 3, 2026-09-11): the two per-QUOTATION choices. Language first,
// because it constrains the price modes on offer (no SPECIAL_SQM on an English document). Always
// sent explicitly on create AND update — DealQuotationService keeps the stored value when either is
// missing on an update, which is safe, but a UI that relied on that would be one refactor away
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
  const [searchParams] = useSearchParams();
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

  // Blocker fix (Opus re-check): GET /api/tickets/{id} responds `TicketDetailResponse`
  // (`{ ticket: TicketDto }`, TicketResponses.java) and TicketDto itself is the ENVELOPE
  // (`{summary, items, events, quotation, quotations}`, TicketDto.java) -- TWO levels of
  // wrapping, not one. `.then((r) => r.ticket)` alone (the bug) read the whole TicketDto, so
  // every summary field this page needs (createdById/customerName/createdByName/projectName)
  // was undefined on it (they live one level deeper, under `.summary`) -- which made
  // canCreateDealQuotation(user, ticket) false even for the deal's own owner. `r.ticket?.summary`
  // unwraps BOTH levels in one step; TicketDetailPage.jsx:348+392 takes the same two-step trip
  // (keeps the whole `r.ticket` around because it also needs `.items`/`.events`, then reads
  // `.summary` separately at the call site) -- this page only ever needs summary fields, so it
  // flattens directly instead of keeping an intermediate `ticket.summary.x` everywhere below.
  //
  // Owner feedback F2 (2026-09-10) widened `enabled`: ผู้สั่งซื้อ is now mandatory AND changeable
  // on an existing DRAFT, and DealQuotationDto carries the frozen `contactId`/`contactName`
  // snapshot but NOT `customerId` -- which QuotationContactPicker needs to list the customer's
  // other contacts. The ticket is the only place that id lives, so an editable existing draft
  // fetches it too. Deliberately NOT fetched for a read-only/non-DRAFT quotation: there is no
  // picker to feed there, and the document view prints the snapshot the DTO already carries.
  const ticketQuery = useQuery({
    queryKey: queryKeys.ticketDetail(effectiveTicketId),
    queryFn: () => api.tickets.get(effectiveTicketId).then((r) => r.ticket?.summary),
    enabled: !!effectiveTicketId && (!id || quotation?.docStatus === 'DRAFT'),
  });
  const ticket = ticketQuery.data ?? null;

  // The deal's customer MASTER row (owner, 2026-09-11: fill in the address, "autofill in later on
  // if they get the same customer"). On the ?ticket= and existing-draft paths the rep can now edit
  // ที่อยู่ / เลขที่ผู้เสียภาษี / โทร. here too, and the checklist needs the LIVE values — the
  // quotation's own columns are a snapshot as of its last save.
  //
  // ⚠️ There is no GET /api/customers/{id}; the only read is the name search (ORDER BY name
  // LIMIT 30, CustomerRepository.search). So this searches the deal's customer name and matches
  // on id. If the row is not in those 30 (a name that is a substring of 30+ others), it falls
  // back to the quotation's snapshot below — degraded, never wrong about which customer.
  const customerRecordQuery = useQuery({
    queryKey: queryKeys.customerRecord(ticket?.customerId),
    queryFn: async () => {
      const res = await api.customers.search(ticket.customerName ?? '');
      return (res?.customers ?? []).find((c) => Number(c.id) === Number(ticket.customerId)) ?? null;
    },
    // `ticket` is null on the inline-create path (DealCustomerCard holds the full record there), so
    // this only ever runs on the ?ticket= and editable-draft paths.
    enabled: Boolean(ticket?.customerId) && (!id || quotation?.docStatus === 'DRAFT'),
  });
  // What the rep just saved through CustomerDetailsFields, until the refetch catches up.
  const [customerOverride, setCustomerOverride] = useState(null);

  // V179 (owner feedback #4, 2026-09-14) — the ผู้พิมพ์/พนักงานขาย print-name selector options.
  // Gated the same as who may SET the fields at all (canCreateDealQuotationStandalone: sales,
  // sales_manager, or the canCreateQuotation grant — role/grant-only, no ticket/quotation needed),
  // matching DealQuotationService#findQuotationDisplayNameOptions' own gate. Every branch that
  // renders the two selectors below is already inside `isEditable`, which implies this same set,
  // so no separate role check is needed at render time — only at fetch time.
  const displayNameOptionsQuery = useQuery({
    queryKey: queryKeys.dealQuotationDisplayNameOptions(),
    queryFn: () => api.dealQuotations.displayNameOptions().then((r) => r.items ?? []),
    enabled: canCreateDealQuotationStandalone(user),
  });
  const displayNameOptions = displayNameOptionsQuery.data ?? [];

  // Owner ask 2026-09-10 ("inline deal creation"): /quotations/new with NEITHER an :id NOR a
  // ?ticket= is a brand-new deal that does not exist anywhere yet -- the rep picks/creates the
  // customer and project right here instead of being sent to /tickets first. Stable per-render
  // (not stateful): it only ever describes the URL the page was loaded with.
  const isInlineCreate = !id && !ticketIdParam;
  const [dealForm, setDealForm] = useState(emptyDealForm);
  // DealCustomerCard still reports ผู้สั่งซื้อ through the same one-patch-upward contract as
  // ลูกค้า/โครงการ (including `contact: null` when picking a different customer clears it), so
  // that key is split back out to the page-level `contact` state rather than kept twice.
  function updateDealForm(patch) {
    const { contact: nextContact, ...rest } = patch;
    if ('contact' in patch) setContact(nextContact);
    if (Object.keys(rest).length) setDealForm((prev) => ({ ...prev, ...rest }));
    setDirty(true);
  }
  // Set once the inline flow's own `tickets.create` succeeds. Kept across a subsequent
  // dealQuotations.create failure so a retry reuses this ticket instead of minting a second deal
  // for the same customer/project (see handleInlineCreate below).
  const [createdTicketId, setCreatedTicketId] = useState(null);
  const [creatingDeal, setCreatingDeal] = useState(false);

  // The rep's own remembered defaults / recent picks (quotationPrefs.js). Read ONCE, lazily, so a
  // browser that throws on `localStorage` (private window, blocked site data) throws inside the
  // module's own try/catch during the initial state computation and never on a later render.
  const [storedDefaults] = useState(() => readQuotationDefaults(user?.id));
  const [recentPicks, setRecentPicks] = useState(() => readRecentCatalogPicks(user?.id));

  const [items, setItems] = useState([]);
  // v3: ส่วนลดพิเศษ rows live in their OWN ordered list, not in `items`. They always print LAST
  // (DealQuotationService#buildItems moves every ADJUSTMENT to the end), and they belong to no
  // ตำแหน่งติดตั้ง group — keeping them out of `items` means the group contiguity invariant above
  // never has to reason about a row with no group, and the payload is simply items + adjustments,
  // which is already the server's printed order.
  const [adjustments, setAdjustments] = useState([]);
  const [docSettings, setDocSettings] = useState(defaultDocSettings);
  // A mode/currency change the rep did not directly choose (e.g. SPECIAL_SQM moved off when the
  // document became English) is SAID, persistently, until the next settings change.
  const [settingsNotice, setSettingsNotice] = useState(null);
  // The last save's refusal, kept on screen (a toast vanishes). A 400 like "ยอดรวมหลังหักส่วนลด
  // พิเศษติดลบ" is exactly the kind of error that must not be swallowed or scroll away.
  const [saveError, setSaveError] = useState(null);
  // ตำแหน่งติดตั้ง groups (owner feedback F1), ordered. `items` stays the single flat, ordered
  // list it always was -- see locationGroupsFromItems' comment for the contiguity invariant every
  // mutation below maintains, which is what lets buildUpsertPayload save with no re-sorting.
  const [groups, setGroups] = useState(() => [{ groupId: newLocationGroupId(), label: '' }]);
  const [terms, setTerms] = useState(() => emptyTerms(storedDefaults));
  // ผู้สั่งซื้อ (owner feedback F2) -- a contact OBJECT, or null. Owned here rather than inside
  // DealCustomerCard because it is required on all three entry paths, only one of which renders
  // that card.
  const [contact, setContact] = useState(null);
  const [dirty, setDirtyState] = useState(false);
  // #S1 (lost-edit-on-save fix): a monotonically increasing counter of "the form changed" events,
  // bumped every time something calls setDirty(true) — i.e. once per genuine edit, never once per
  // render. A save's onSuccess captures this value at the moment it BUILT its request (see
  // buildSaveRequest below); if the counter has moved on by the time the response lands, an edit
  // happened WHILE that request was in flight and setDirty(false) must not run, or that edit would
  // never get its own save (autosave only fires while dirty, and submit's pre-save only fires
  // `if (dirty)`). Deliberately a ref, not state: it is read-only ever compared for equality, never
  // rendered, and updating it must never itself cause a re-render.
  const editSeqRef = useRef(0);
  const setDirty = useCallback((next) => {
    if (next) editSeqRef.current += 1;
    setDirtyState(next);
  }, []);
  // V178 (owner ruling 2026-09-14): ระบุวันที่ is offered only on a document with special
  // pricing — mirrors DealQuotationRenderAdapter#hasSpecialPricing exactly (quotationMeta.js).
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
  const [initializedFor, setInitializedFor] = useState(null);
  // Item completeness (M4/owner ruling 2026-09-10): which rows should show their per-field inline
  // red hints yet. A row seeded from the server (an existing DRAFT the rep reopened) is touched
  // immediately -- it is not "first paint" from the rep's point of view, they need to see what is
  // missing right away. A row freshly created by "เพิ่มรายการ" starts UNtouched, so a brand-new
  // blank row does not instantly show eight red messages before the rep has typed anything --
  // updateItem below adds its clientId the moment the rep edits ANY field on it. The top-of-form
  // per-row "รายการที่ N: ขาด ..." summary is NOT gated on this (see validationErrors below) -- it
  // always reflects every row, touched or not, since that is what the disabled บันทึกร่าง/
  // ส่งขออนุมัติ buttons are explaining.
  const [touchedRowIds, setTouchedRowIds] = useState(() => new Set());
  const calcTimers = useRef({});
  // #H2: a per-row request sequence. Bumped once per `updateItem` call (i.e. once per debounce
  // restart, not once per fired request); a response is applied only if it still matches the
  // LATEST sequence issued for that row at the time it settles, so an older, slower response
  // arriving after a newer one can never clobber it -- see the response handler below.
  const calcSeq = useRef({});

  // Seeds local editable state from the server exactly once per quotation/ticket -- after that,
  // the user's own edits are the source of truth until the next save round-trips a fresh copy.
  useEffect(() => {
    const key = id ? `q-${id}` : `t-${effectiveTicketId ?? ''}`;
    if (initializedFor === key) return;
    if (id) {
      if (!quotation) return;
      // F1: "loading rebuilds groups from consecutive equal labels" -- the flat items array the
      // server sends IS the document order, so the runs of equal `locationLabel` in it are exactly
      // the headings the renderer prints. Never re-sorted here.
      const storedMode = quotation.priceMode || 'NET';
      const rows = quotation.items.map((it) => rowFromServerItem(it, storedMode));
      const rebuilt = locationGroupsFromItems(rows.filter((it) => lineTypeOf(it) !== LINE_TYPE_ADJUSTMENT));
      setGroups(rebuilt.groups);
      setItems(rebuilt.items);
      setAdjustments(rows.filter((it) => lineTypeOf(it) === LINE_TYPE_ADJUSTMENT));
      setDocSettings({ priceMode: storedMode, documentLanguage: quotation.documentLanguage || 'TH' });
      setSettingsNotice(null);
      setTouchedRowIds(new Set(quotation.items.map((it) => it.id)));
      // F2: seeded from the frozen snapshot on the DTO, so the picker shows the right ผู้สั่งซื้อ
      // before (and even if) the customer's contact list ever loads -- see
      // QuotationContactPicker's own note on injecting a selected-but-unlisted option.
      // The snapshot's phone/email ride along (raw — `undefined` on a DTO that lacks them means
      // "unknown", which QuotationContactPicker's onResolve then fills from the contact list).
      setContact(quotation.contactId
        ? {
          id: quotation.contactId, firstName: quotation.contactName ?? '', lastName: '',
          phone: quotation.contactPhone, email: quotation.contactEmail,
        }
        : null);
      setTerms({
        deptCode: quotation.deptCode ?? '', unitCode: quotation.unitCode ?? '',
        offerDate: quotation.offerDate ?? todayIso(),
        depositPercent: quotation.depositPercent ?? '',
        depositPercentCustom: quotation.depositPercent != null && !DEPOSIT_PERCENT_PRESETS.includes(quotation.depositPercent),
        remainderMode: quotation.remainderMode ?? '', creditDays: quotation.creditDays ?? '',
        validityDays: quotation.validityDays ?? '', customerNotes: quotation.customerNotes ?? '',
        // V178: a stored NULL validityMode (every pre-V178 row) normalises to DAYS, same as the
        // server's own read-side default.
        validityMode: quotation.validityMode ?? 'DAYS', validityUntil: quotation.validityUntil ?? '',
        // V179: null (the DTO's own default) reads as "(ค่าเริ่มต้น)" — the select's own empty option.
        printedByDisplayId: quotation.printedByDisplayId ?? '',
        salesRepDisplayId: quotation.salesRepDisplayId ?? '',
        projectName: quotation.projectName ?? '',
      });
      setDirty(false);
      setInitializedFor(key);
    } else if (effectiveTicketId) {
      setItems([]);
      setAdjustments([]);
      setDocSettings(defaultDocSettings());
      setGroups([{ groupId: newLocationGroupId(), label: '' }]);
      setTerms(emptyTerms(storedDefaults));
      setTouchedRowIds(new Set());
      setDirty(false);
      setInitializedFor(key);
    }
    // setDirty is a useCallback with an empty dep list (stable identity forever), so listing it
    // here can never cause an extra run of this effect -- it only satisfies exhaustive-deps now
    // that setDirty is no longer the raw, hook-recognised useState setter.
  }, [id, quotation, effectiveTicketId, initializedFor, storedDefaults, setDirty]);

  // ผู้สั่งซื้อ prefill on the `?ticket=` path (F2: "With `?ticket=`, prefill from the deal's
  // contact, changeable"). Deliberately its OWN effect, not a branch of the seeding effect above.
  //
  // The editor renders while the ticket query is still in flight, so the rep can already be adding
  // items when it resolves. Folding this into the seeding effect meant either waiting for the
  // ticket before marking the key initialised -- which lets a late `setItems([])` DELETE rows the
  // rep had already typed -- or never prefilling at all. Keyed by a ref on the ticket id so it
  // fires exactly once per deal and can never overwrite a contact the rep chose themselves.
  const contactSeededForTicket = useRef(null);
  useEffect(() => {
    if (id || !ticket?.id || contactSeededForTicket.current === ticket.id) return;
    contactSeededForTicket.current = ticket.id;
    if (ticket.contactId) {
      setContact({ id: ticket.contactId, firstName: ticket.contactName ?? '', lastName: '' });
    }
  }, [id, ticket]);

  // Opus review fix (2026-09-14): the SAME race the contact-seeding effect above exists to avoid.
  // The main seeding effect (above) marks `initializedFor` on the FIRST render of a `?ticket=`
  // visit, before `ticketQuery` has resolved, so seeding `projectName` from `ticket.projectName`
  // there directly was always seeding from `undefined` and that `initializedFor` guard then made
  // the ticket dependency inert forever -- verified: a `?ticket=` visit whose deal already HAS a
  // project name rendered โครงการ EMPTY instead of prefilled, a real regression against this
  // card's pre-existing plain-text display (`{projectName ?? '-'}`) it replaced. A separate ref,
  // firing once per ticket id once `ticket` genuinely resolves, functionally-updates `terms`
  // instead of racing the main effect's own `setTerms` call.
  //
  // Second Opus follow-up nit (2026-09-14): unlike contactSeededForTicket above -- which cannot
  // be raced, because the contact picker's own candidate list is itself keyed off `ticket` and so
  // has nothing to pick from until the ticket resolves -- โครงการ is a plain, always-enabled
  // `<input>`, so a rep who starts typing during the query window could have this effect fire
  // afterward and clobber it. Guard by checking the CURRENT field value inside the updater, not
  // just the ref: only seed when the rep hasn't already put something there.
  const projectNameSeededForTicket = useRef(null);
  useEffect(() => {
    if (id || !ticket?.id || projectNameSeededForTicket.current === ticket.id) return;
    projectNameSeededForTicket.current = ticket.id;
    if (ticket.projectName) {
      setTerms((t) => (t.projectName ? t : { ...t, projectName: ticket.projectName }));
    }
  }, [id, ticket]);

  function updateItem(clientId, patch) {
    setDirty(true);
    // First real edit of this row -- flip on its inline red hints (see touchedRowIds' own comment
    // above). A no-op setState once already touched (Set membership check first) rather than a
    // fresh Set on every keystroke.
    setTouchedRowIds((prev) => (prev.has(clientId) ? prev : new Set(prev).add(clientId)));
    // #H2 (L3): the setItems updater is now a PURE `prev.map(...)` -- no clearTimeout/setTimeout,
    // no captured-variable side effect. React StrictMode double-invokes updaters to catch exactly
    // this kind of impurity; the previous version scheduled the debounced calculate-line call
    // FROM INSIDE the updater, so a double-invoke could schedule it twice.
    setItems((prev) => prev.map((it) => (it.clientId === clientId ? { ...it, ...patch, calcPending: true } : it)));

    // Read the row's current (already-patched) input fields from the `items` closure -- by the
    // time this synchronous call returns, the setItems above has been applied (React 18 does not
    // defer a synchronous updater's application past this line in an event handler), so `items`
    // here is stale-by-one-render at worst and the merge below reconstructs the exact patched
    // shape anyway.
    const currentItem = items.find((it) => it.clientId === clientId);
    if (!currentItem) return; // row was removed (e.g. by a fast double-click on ลบรายการ)
    scheduleCalc(clientId, { ...currentItem, ...patch }, docSettings.priceMode, docSettings.documentLanguage);
  }

  /** The debounced calculate-line call for one row, in the QUOTATION's price mode (which decides
   * which mode-specific price itemInputFromRow sends — see its ⚠️ note). Split out of updateItem so
   * a price-mode switch can re-preview every tile row through the exact same path. */
  function scheduleCalc(clientId, target, priceMode, documentLanguage) {
    clearTimeout(calcTimers.current[clientId]);
    const seq = (calcSeq.current[clientId] = (calcSeq.current[clientId] ?? 0) + 1);
    // A row missing the price its mode needs has nothing honest to preview (quotationMeta
    // #rowHasPriceForPreview: calculate-line infers the mode FROM THE ROW, so it would come back
    // priced under another mode). Show no money until the rep types the price — which is also what
    // keeps the rows cleared by a language switch (owner ruling 2026-09-13, "Clear all prices on
    // switch") from reappearing with a stale figure in the row or the totals.
    if (!rowHasPriceForPreview(target, priceMode, documentLanguage)) {
      setItems((cur) => cur.map((it) => (
        it.clientId === clientId ? { ...it, netUnitPrice: null, lineAmount: null, calcPending: false } : it
      )));
      return;
    }
    calcTimers.current[clientId] = setTimeout(() => {
      // Owner decision 2026-09-13 (B): the preview is computed in the DOCUMENT's language, so its
      // lines (and, on English per-sqm, its quantity) are what the saved document will print.
      api.dealQuotations.calculateLine(itemInputFromRow(target, priceMode, documentLanguage), documentLanguage)
        .then((res) => {
          // A newer edit (and therefore a newer, still-in-flight or already-settled request)
          // superseded this one while it was on the wire -- discard rather than let an older,
          // slower response overwrite what the user has typed since (#H2's core bug: the
          // response echoes the request's own input fields back).
          if (calcSeq.current[clientId] !== seq) return;
          setItems((cur) => cur.map((it) => (
            it.clientId === clientId ? { ...it, ...pickCalculatedFields(res.item ?? {}), calcPending: false } : it
          )));
        })
        .catch(() => {
          if (calcSeq.current[clientId] !== seq) return;
          // v3: a refused preview CLEARS the money rather than leaving the last good figure up. A
          // row the server cannot price as typed (a ราคาพิเศษ with no ตร.ม./แผ่น, a zero price) must
          // not keep showing an amount — least of all a net per piece from a previous price mode,
          // which the ราคาพิเศษ field would then present as its own derivation.
          setItems((cur) => cur.map((it) => (
            it.clientId === clientId ? { ...it, netUnitPrice: null, lineAmount: null, calcPending: false } : it
          )));
        });
    }, 300);
  }

  // ── Document settings (v3/v3b) ────────────────────────────────────────────────────────────

  /** Switches the QUOTATION's tile price mode and re-previews every tile row in it. `prefill`
   * carries the net already on screen into ราคาสุทธิ/แผ่น when moving INTO DIRECT_NET — the rep
   * typed nothing and the figure does not change, which is the least-typing reading of "I want to
   * state the net directly". Never done across a currency change (see changeLanguage). */
  function applyPriceMode(priceMode, {
    prefill = true, documentLanguage = docSettings.documentLanguage, clearPrices = false,
  } = {}) {
    const next = (clearPrices ? rowsWithPricesCleared(items) : items).map((it) => {
      if (lineTypeOf(it) !== LINE_TYPE_TILE) return it;
      const carried = prefill && priceMode === 'DIRECT_NET'
        && (it.directNetPrice === '' || it.directNetPrice == null) && it.netUnitPrice != null
        ? { directNetPrice: it.netUnitPrice } : {};
      // The previous mode's derived money is dropped until the new preview lands — see the
      // calc-error note above for why a stale net must never be shown under the new mode.
      return { ...it, ...carried, netUnitPrice: null, lineAmount: null, specialPriceLine: null, calcPending: true };
    });
    setItems(next);
    setDirty(true);
    next.filter((it) => lineTypeOf(it) === LINE_TYPE_TILE).forEach((it) => scheduleCalc(it.clientId, it, priceMode, documentLanguage));
  }

  function changePriceMode(priceMode) {
    if (priceMode === docSettings.priceMode) return;
    setDocSettings((prev) => ({ ...prev, priceMode }));
    setSettingsNotice(null);
    applyPriceMode(priceMode);
  }

  /** The ONE choice that carries others with it: currency (TH→THB, EN→USD) and, on English, the
   * loss of SPECIAL_SQM. Every consequence the rep did not pick is announced in `settingsNotice`
   * rather than applied silently — the brief's words. Typed prices are NOT converted: the server
   * has no exchange rate for this and neither should the editor, so the rep is told that the same
   * numbers will now print in the other currency. */
  function changeLanguage(documentLanguage) {
    if (documentLanguage === docSettings.documentLanguage) return;
    const { priceMode, moved } = priceModeForLanguage(docSettings.priceMode, documentLanguage);
    // Owner ruling 2026-09-13, "Clear all prices on switch": the editor has no exchange rate, so no
    // typed amount is carried into the new currency as the same number — every currency amount, in
    // EVERY price mode, is cleared (quotationMeta#rowsWithPricesCleared) and the rep re-enters it.
    // Percentages, quantities and box figures are kept. An EDITOR rule: the server cannot tell a
    // re-typed price from a stale one and accepts what the PUT carries.
    const notices = [
      documentLanguage === 'EN'
        ? 'เปลี่ยนภาษาเอกสาร: ล้างราคาทั้งหมดแล้ว — กรุณากรอกราคาใหม่เป็น USD (ไม่มี VAT)'
        : 'เปลี่ยนภาษาเอกสาร: ล้างราคาทั้งหมดแล้ว — กรุณากรอกราคาใหม่เป็นบาท',
    ];
    if (moved) {
      notices.push('เอกสารภาษาอังกฤษใช้ "ราคาพิเศษ บาท/ตร.ม." ไม่ได้ (ราคานั้นรวม VAT แต่เอกสารภาษาอังกฤษไม่มี VAT) — เปลี่ยนวิธีกรอกราคาเป็น "ราคาสุทธิต่อแผ่น" ให้แล้ว กรุณากรอกราคาสุทธิของทุกรายการ');
    }
    setDocSettings({ priceMode, documentLanguage });
    setSettingsNotice(notices.length ? notices.join(' · ') : null);
    setDirty(true);
    // Every price is cleared (above), and every tile row is re-previewed in the NEW language (owner
    // decision 2026-09-13, B) — which, with its price now blank, shows no money until re-entered.
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

  function addPlainItem(targetGroupId) {
    setDirty(true);
    const groupId = targetGroupId ?? groups[groups.length - 1]?.groupId ?? null;
    setItems((prev) => insertIntoGroup(prev, emptyPlainItem(groupId), groupId));
  }

  // ── ตำแหน่งติดตั้ง group operations (owner feedback F1, 2026-09-10) ──────────────────────────
  //
  // Every one of these keeps the contiguity invariant: items of the same group are adjacent in
  // `items`, in group order. `insertIntoGroup` is the single place that decides WHERE a row lands,
  // so add / move / duplicate can never drift apart on that question.

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
    setItems((prev) => insertIntoGroup(prev, emptyQuotationItem(groupId, storedDefaults), groupId));
  }

  /** MED-4: a new group is born BLANK, so adding one while an existing group is also blank would
   * create two groups the document cannot tell apart (the renderer groups by runs of equal label,
   * and both runs are ''), which then merge on the next reload. Refused with the reason instead —
   * naming the existing one first is the whole fix, and it takes one word. */
  function addGroup() {
    const unnamed = groups.find((g) => !(g.label ?? '').trim());
    if (unnamed) {
      showToast('error', 'กรุณาตั้งชื่อตำแหน่งติดตั้งที่ยังว่างก่อน มิฉะนั้นสองตำแหน่งจะรวมเป็นตำแหน่งเดียวกันในเอกสาร');
      return;
    }
    setDirty(true);
    setGroups((prev) => [...prev, { groupId: newLocationGroupId(), label: '' }]);
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

  // MED-4: the two states the flat `locationLabel` wire format cannot round-trip — see
  // quotationMeta.js for why neither is fixable without a schema AND a renderer change. A
  // duplicate label BLOCKS the save (below); an empty group only warns, because it is a normal
  // transient state between "เพิ่มตำแหน่ง" and the first item in it.
  const duplicateGroupIds = useMemo(() => duplicateLocationLabelGroupIds(groups), [groups]);
  const emptyGroupIds = useMemo(() => emptyLocationGroupIds(groups, items), [groups, items]);

  // #M6: live sums off `items` -- what the SERVER would compute once saved (WastageCalculator
  // itself lives only in the backend, so this is an estimate, labelled as one below) -- shown
  // only while there is nothing authoritative yet (no saved quotation, or unsaved edits on top of
  // one). `lineAmount` is null until a row's first calculate-line response lands, which sums to 0
  // -- exactly the "nothing to show yet" state the old bare "ยังไม่บันทึก" message covered.
  //
  // v3: the ส่วนลดพิเศษ rows come off that sum as ESTIMATES of the server's figure (see
  // quotationMeta#estimateAdjustmentAmount), each a percentage of the same NON-adjustment base
  // because the server does not compound them. v3b: an English document has no VAT at all.
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

  // #M4 (owner ruling 2026-09-10): every item must be COMPLETE before บันทึกร่าง / ส่งขออนุมัติ --
  // "sales can also fill in their own item if it is not in the database, but ALL info about the
  // tile has to be completed." validateQuotationItem (quotationMeta.js) is the single source of
  // truth for what "complete" means; this array parallels `items` 1:1 so both the per-row summary
  // below AND each row's inline hints (QuotationItemRow's `errors` prop) read off the exact same
  // computation -- never two independent checks that could disagree about the same row.
  const itemErrorsByRow = useMemo(
    () => items.map((it) => validateQuotationItem(it, docSettings.priceMode, docSettings.documentLanguage)),
    [items, docSettings.priceMode, docSettings.documentLanguage],
  );
  const adjustmentErrorsByRow = useMemo(() => adjustments.map((a) => validateAdjustment(a)), [adjustments]);

  // Owner feedback #7 (2026-09-14): mirrors DealQuotationService#requireEveryTileItemHasALeadTime
  // — SUBMIT only, deliberately a SEPARATE array from `itemErrorsByRow` above rather than the same
  // one with `requireLeadTime` always on: `itemErrorsByRow` feeds `buildQuotationChecklist`, whose
  // blocking set gates บันทึกร่าง too, and a draft must still save with no lead time (only submit
  // is refused server-side). `index` here is the printed "รายการที่" number the same way
  // buildUpsertPayload sends `items` — tiles in document order, adjustments always last.
  const submitItemErrorsByRow = useMemo(
    () => items.map((it) => validateQuotationItem(it, docSettings.priceMode, docSettings.documentLanguage,
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
    ? `กรุณาระบุระยะเวลานำเข้า (วัน) ของรายการที่ ${missingLeadTimeSeqs.join(', ')}` : null;

  // ── "ข้อมูลที่ยังไม่ครบ" (owner, 2026-09-11) ───────────────────────────────────────────────
  // ONE derivation (quotationMeta#buildQuotationChecklist) feeds three things: the checklist
  // panel, the disabled บันทึกร่าง/ส่งขออนุมัติ buttons (blocking entries only — the set lives in
  // QUOTATION_BLOCKING_CHECKS, and is exactly what the backend already refuses), and the
  // ส่งขออนุมัติ confirm dialog's list of optional gaps. The blocking messages are the same Thai
  // strings this page showed before — advisory only, the server re-validates regardless.
  const snapshotCustomer = ticket?.customerId
    ? {
      id: ticket.customerId,
      name: quotation?.customerName ?? ticket.customerName,
      // Raw from the DTO: a real one always carries these (null when empty); `undefined` on
      // anything else reads as "unknown" and raises no warning.
      ...(quotation ? { address: quotation.customerAddress, taxId: quotation.customerTaxId, phone: quotation.customerPhone } : {}),
    }
    : null;
  const summaryCustomer = (customerOverride && Number(customerOverride.id) === Number(ticket?.customerId) ? customerOverride : null)
    ?? customerRecordQuery.data ?? snapshotCustomer;
  const checklistCustomer = isInlineCreate ? dealForm.customer : summaryCustomer;
  const checklistProjectName = isInlineCreate ? null : (quotation ? quotation.projectName : (ticket ? ticket.projectName : undefined));
  const duplicateGroupIndex = duplicateGroupIds.size
    ? groups.findIndex((g) => duplicateGroupIds.has(g.groupId)) : null;
  const checklist = useMemo(() => buildQuotationChecklist({
    isInlineCreate,
    customer: checklistCustomer,
    hasProject: Boolean(dealForm.project),
    projectName: checklistProjectName,
    contact,
    contactFieldId: isInlineCreate ? 'deal-contact' : 'quotation-contact',
    terms,
    designerFieldId: `${isInlineCreate ? 'inline-quotation' : 'quotation'}-designer-picker`,
    items,
    itemErrorsByRow,
    adjustments,
    adjustmentErrorsByRow,
    duplicateGroupIndex,
    // Wrong-way-round: a mode the language does not offer (none today — owner decision 2026-09-13
    // made SPECIAL_SQM available on English) stays unsaveable if some path ever reaches it.
    priceModeLanguageConflict: !availablePriceModes(docSettings.documentLanguage).some((opt) => opt.code === docSettings.priceMode),
  }), [isInlineCreate, checklistCustomer, dealForm.project, checklistProjectName, contact, terms, items, itemErrorsByRow,
    adjustments, adjustmentErrorsByRow, duplicateGroupIndex, docSettings]);
  const validationErrors = useMemo(() => checklist.filter((e) => e.blocking).map((e) => e.message), [checklist]);
  const checklistWarnings = useMemo(() => checklist.filter((e) => !e.blocking), [checklist]);
  const hasValidationErrors = validationErrors.length > 0;
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

  // useCallback (not a plain function) so the autosave effect's backoff check (#S4, below) can
  // list it as a dependency with a STABLE identity -- it only changes when one of the values it
  // actually reads changes, exactly matching that effect's own [items, adjustments, docSettings,
  // terms, contact, groups] dependency list (labelByGroupId is itself a groups-keyed useMemo).
  // Every other caller (buildSaveRequest, createMutation, handleInlineCreate) just calls it same
  // as before -- a memoized function is still a function.
  const buildUpsertPayload = useCallback(() => ({
    // F2: `contactId` is optional on the wire (UpsertDealQuotationRequest) and defaults to the
    // deal's own contact server-side — but this editor always knows which one is selected, so it
    // always sends it explicitly rather than relying on that default.
    contactId: contact?.id ?? null,
    deptCode: terms.deptCode || null,
    unitCode: terms.unitCode || null,
    offerDate: terms.offerDate || null,
    depositPercent: terms.depositPercent === '' ? null : Number(terms.depositPercent),
    remainderMode: terms.remainderMode || null,
    creditDays: terms.creditDays === '' ? null : Number(terms.creditDays),
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
    // V179 (owner feedback #4, 2026-09-14) — print-only ผู้พิมพ์/พนักงานขาย name override. Always
    // sent explicitly (like priceMode/documentLanguage above): a full PUT carries the payload's
    // own value, null included, so there is no ambiguity between "omitted" and "cleared".
    printedByDisplayId: terms.printedByDisplayId === '' ? null : Number(terms.printedByDisplayId),
    salesRepDisplayId: terms.salesRepDisplayId === '' ? null : Number(terms.salesRepDisplayId),
    // Owner feedback 2026-09-14 ("sometimes there's a typo in the ... project so they should be
    // able to correct it") — genuinely editable now, no "missing keeps stored": always sent
    // explicitly, blank included, same discipline as customerNotes just above.
    projectName: terms.projectName || null,
    // F1: still the FLAT items array the API has always taken, in group order — `items` is
    // already stored that way (see insertIntoGroup), so this is a plain map with no sort. Each
    // row's `locationLabel` is stamped from ITS GROUP, which is the only place that text lives
    // now; a blank group label sends null, which prints no heading row on the document.
    //
    // v3: each row in the quotation's price mode, then the ส่วนลดพิเศษ rows LAST — which is where
    // the server would put them anyway, so the order sent is the order printed.
    items: [
      ...items.map((item) => ({
        ...itemInputFromRow(item, docSettings.priceMode, docSettings.documentLanguage),
        locationLabel: (labelByGroupId.get(item.groupId) || '').trim() || null,
      })),
      ...adjustments.map(adjustmentInputFromRow),
    ],
  }), [contact, terms, docSettings, items, adjustments, labelByGroupId]);

  /** The ONE place that builds a save request — autosave, manual บันทึกร่าง, AND submit's own
   * pre-save all call this instead of pairing buildUpsertPayload() with editSeqRef.current
   * separately, so the payload actually sent and the edit-sequence snapshot describing it can
   * never disagree (#S1). `sentClientIds` walks `items` then `adjustments` — the SAME two arrays,
   * in the SAME order, that buildUpsertPayload's own `items:` array above concatenates — so
   * `sentClientIds[i]` names exactly the row `payload.items[i]` describes. Called from inside a
   * mutationFn (never onMutate): building it there means the snapshot is taken at the moment the
   * request is actually issued, not at some earlier point that could go stale before `mutate()`
   * is even invoked. */
  function buildSaveRequest() {
    return {
      payload: buildUpsertPayload(),
      sentSeq: editSeqRef.current,
      sentClientIds: [...items.map((it) => it.clientId), ...adjustments.map((a) => a.clientId)],
    };
  }

  /** #S3 (item ids): after ANY successful save, the server's response carries each item's
   * (possibly newly-minted) `id`, in `seq` order — which is the order SENT (buildUpsertPayload
   * sends non-adjustment rows then adjustments, and DealQuotationService prints/returns them in
   * that same order). `sentClientIds[i]` is therefore the clientId of whatever row became
   * `resItems[i]`. A length mismatch means something about the round trip is not what this
   * function assumes (a concurrent edit that changed row COUNT, a malformed response) — skip
   * adoption entirely rather than guess at a mapping that could assign the wrong id to the wrong
   * row, which would be worse than not adopting at all.
   *
   * Raw `setItems`/`setAdjustments` — NOT `updateItem`/`updateAdjustment` — because adopting a
   * server id is bookkeeping, not an edit: it must never bump editSeqRef (that would make a save
   * that adopted ids look like it landed mid-edit and refuse to clear `dirty`) and must never touch
   * `clientId` or `touchedRowIds`. Rows added after this request was sent (not in `sentClientIds`)
   * are untouched; rows removed meanwhile are simply absent from `prev` and skipped. */
  function adoptServerIds(sentClientIds, resItems) {
    if (!Array.isArray(resItems) || resItems.length !== sentClientIds.length) return;
    const idByClientId = new Map(sentClientIds.map((clientId, i) => [clientId, resItems[i].id]));
    setItems((prev) => prev.map((it) => (
      idByClientId.has(it.clientId) ? { ...it, id: idByClientId.get(it.clientId) } : it
    )));
    setAdjustments((prev) => prev.map((a) => (
      idByClientId.has(a.clientId) ? { ...a, id: idByClientId.get(a.clientId) } : a
    )));
  }

  /** The one place autosave, manual บันทึกร่าง, and submit's pre-save all land after a successful
   * PUT — adopt whatever ids the response minted, then clear `dirty` ONLY if no edit happened while
   * this request was in flight (#S1: `editSeqRef.current === sentSeq`, captured by buildSaveRequest
   * at send time). If the rep kept typing, `dirty` stays true so the NEXT autosave tick (or the
   * next submit attempt) picks up what this request could not have known about. */
  function applySavedQuotation(quotation, sentSeq, sentClientIds) {
    adoptServerIds(sentClientIds, quotation.items);
    if (editSeqRef.current === sentSeq) setDirty(false);
  }

  // "บันทึกอัตโนมัติแล้ว HH:mm" — set by an autosave, cleared by any later manual save so the two
  // indicators never contradict each other.
  const [autoSavedAt, setAutoSavedAt] = useState(null);

  /** The rep's own terms become the starting point for their NEXT new quotation (owner ask
   * 2026-09-10). Best-effort by construction — see quotationPrefs.js. */
  function rememberDefaults() {
    writeQuotationDefaults(user?.id, {
      depositPercent: terms.depositPercent,
      remainderMode: terms.remainderMode,
      creditDays: terms.creditDays,
      validityDays: terms.validityDays,
      // V178: the MODE is remembered (a rep who dates their validity keeps starting there); the
      // DATE itself never is — see quotationPrefs.js's own comment on DEFAULT_TERM_FIELDS.
      validityMode: terms.validityMode,
      originCountry: items[items.length - 1]?.originCountry ?? '',
    });
  }

  const createMutation = useMutation({
    mutationFn: () => api.dealQuotations.create(effectiveTicketId, buildUpsertPayload()),
    onSuccess: (res) => {
      // NOT applySavedQuotation: a brand-new quotation has no prior editSeq worth comparing (there
      // was nothing to race with yet) and this immediately navigates to /quotations/:id, which
      // re-mounts the page and re-hydrates from the server anyway. A KNOWN, genuinely UNFIXED edge
      // case, not an oversight: an edit typed while this very create request is in flight is still
      // lost, same as before this change -- out of scope here (see the handoff for this branch).
      setDirty(false);
      setSaveError(null);
      rememberDefaults();
      queryClient.invalidateQueries({ queryKey: ['dealQuotations'] });
      showToast('success', 'บันทึกร่างแล้ว');
      navigate(`/quotations/${res.quotation.id}`, { replace: true });
    },
    onError: (error) => {
      setSaveError(error.message || 'บันทึกไม่สำเร็จ');
      showToast('error', error.message || 'บันทึกไม่สำเร็จ');
    },
  });

  // #S4 (item-4 backoff): the JSON of the payload of the last UPDATE (autosave or manual) that
  // failed — e.g. "ยอดรวมหลังหักส่วนลดพิเศษติดลบ" while a flat ส่วนลดพิเศษ outweighs the rows above
  // it. The autosave effect below refuses to schedule while the CURRENT payload still serializes
  // to this exact string, so a refusal that no edit has changed does not re-fire (and re-toast)
  // every AUTOSAVE_DELAY_MS forever. Any edit changes the payload and lifts the backoff on its
  // own — no separate "reset" path needed. Cleared on the next successful save (see
  // applySavedQuotation's callers). A MANUAL บันทึกร่าง never consults this ref -- it always
  // attempts and always toasts, matching how it always did before this existed.
  const lastFailedAutoPayloadRef = useRef(null);

  // `variables.auto` marks an AUTOSAVE (see the debounce effect below): same request, same
  // invalidation, but no toast — a toast every two seconds while typing would be noise, and the
  // "บันทึกอัตโนมัติแล้ว HH:mm" line in the header is the quieter signal that replaces it.
  const updateMutation = useMutation({
    // #S1/#S3: the request and its edit-seq/clientId snapshot are built TOGETHER, inside
    // mutationFn (never onMutate) so they are read off the exact same instant — see
    // buildSaveRequest's own comment for why that matters.
    mutationFn: async () => {
      const { payload, sentSeq, sentClientIds } = buildSaveRequest();
      try {
        const res = await api.dealQuotations.update(id, payload);
        return { res, sentSeq, sentClientIds };
      } catch (error) {
        lastFailedAutoPayloadRef.current = JSON.stringify(payload);
        throw error;
      }
    },
    onSuccess: ({ res, sentSeq, sentClientIds }, variables) => {
      setSaveError(null);
      rememberDefaults();
      queryClient.setQueryData(queryKeys.dealQuotationDetail(id), res.quotation);
      queryClient.invalidateQueries({ queryKey: ['dealQuotations'] });
      // #S1/#S3: adopt any ids the response minted, and clear `dirty` only if nothing changed
      // while this request was in flight — see applySavedQuotation's own comment.
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

  const [submitConfirmOpen, setSubmitConfirmOpen] = useState(false);

  const submitMutation = useMutation({
    // Unsaved edits are SAVED first. Submit acts on the STORED quotation, and the autosave is a
    // 2-second debounce — so a rep who corrects the ที่อยู่ (which only reaches the document when
    // the draft is re-saved and re-snapshots the customer) and clicks ส่งขออนุมัติ at once would
    // otherwise send the document without it.
    //
    // #S2 (submit-vs-autosave race): this is the ONLY other place besides updateMutation that PUTs
    // the draft, and autoSaveEligible below is suppressed for as long as `submitConfirmOpen` is
    // true or this mutation is pending — so the two can never both be in flight at once. Uses
    // buildSaveRequest/applySavedQuotation exactly like updateMutation's own mutationFn/onSuccess,
    // so a submit's pre-save adopts server ids and clears `dirty` the same way an autosave would.
    mutationFn: async () => {
      if (dirty) {
        const { payload, sentSeq, sentClientIds } = buildSaveRequest();
        const saved = await api.dealQuotations.update(id, payload);
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
        showToast('success', 'ส่งขออนุมัติแล้ว (ฉบับแก้ไขใหม่ ' + res.quotation.number + ')');
        setSubmitConfirmOpen(false);
        navigate(`/quotations/${res.quotation.id}`);
        return;
      }
      queryClient.setQueryData(queryKeys.dealQuotationDetail(id), res.quotation);
      queryClient.invalidateQueries({ queryKey: ['dealQuotations'] });
      showToast('success', 'ส่งขออนุมัติแล้ว');
      setSubmitConfirmOpen(false);
    },
    onError: (error) => showToast('error', error.message || 'ส่งขออนุมัติไม่สำเร็จ'),
  });

  // ── Autosave (owner ask 2026-09-10) ─────────────────────────────────────────────────────────
  //
  // Fires AUTOSAVE_DELAY_MS after the last edit, and only once the quotation already has an id --
  // there is nothing to PUT to before that, and silently minting a deal + a quotation off a
  // half-typed form is not something a debounce should ever decide to do (the first บันทึกร่าง
  // stays an explicit, deliberate click).
  //
  // It also refuses while the form is INCOMPLETE. That is not caution for its own sake: the same
  // completeness rule disables บันทึกร่าง, and the server rejects an incomplete upsert, so an
  // autosave there would do nothing but toast an error every two seconds while the rep types.
  //
  // #S2: also refused for as long as the ส่งขออนุมัติ confirm dialog is open, or submit itself is
  // pending — submit's OWN pre-save is the one PUT that must happen right before submit fires, and
  // letting an independently-scheduled autosave land in between (or worse, race it) is exactly
  // the 409 this fixes. `submitConfirmOpen`/`submitMutation.isPending` flipping false→true is a
  // dependency change like any other below, so the effect's cleanup clears an already-scheduled
  // timer the instant the dialog opens — no separate cancel path needed.
  //
  // The eligibility flag is computed OUTSIDE the effect (rather than early-returning inside it) so
  // that it, and not a re-render, is what the dependency array keys on -- `items`/`terms`/
  // `contact` are the edits themselves, which is what restarts the debounce.
  const autoSaveEligible = Boolean(id) && dirty && !hasValidationErrors && !!quotation
    && isDealQuotationEditable(quotation) && canEditDealQuotation(user, quotation)
    && !submitConfirmOpen && !submitMutation.isPending;
  const { mutate: runUpdate, isPending: updatePending } = updateMutation;
  useEffect(() => {
    if (!autoSaveEligible || updatePending) return undefined;
    // #S4: the same payload that just failed is refused again with no further edit — see
    // lastFailedAutoPayloadRef's own comment.
    if (lastFailedAutoPayloadRef.current != null
      && JSON.stringify(buildUpsertPayload()) === lastFailedAutoPayloadRef.current) {
      return undefined;
    }
    const timer = setTimeout(() => runUpdate({ auto: true }), AUTOSAVE_DELAY_MS);
    return () => clearTimeout(timer);
    // `groups` feeds `labelByGroupId`, which buildUpsertPayload (via buildSaveRequest) reads for
    // every row's locationLabel — an edit that only renames a group must restart the debounce too.
    // `buildUpsertPayload` itself is listed for the #S4 backoff comparison above; it is a
    // useCallback keyed on exactly these same values, so listing both is redundant but harmless,
    // never a source of extra debounce restarts.
  }, [autoSaveEligible, updatePending, runUpdate, items, adjustments, docSettings, terms, contact, groups, buildUpsertPayload]);

  // Owner ask 2026-09-10 ("inline deal creation"): the FIRST บันทึกร่าง on an inline-create visit
  // has to mint the ticket itself before there is anything to hang a quotation off of --
  // api.tickets.create, then api.dealQuotations.create(ticketId, ...), then navigate. A plain
  // useMutation doesn't fit this two-step, partial-failure shape (react-query has no built-in way
  // to remember "step 1 already succeeded" across a retried mutate()), so this is a hand-rolled
  // async handler in the same try/catch/finally shape handleDownload above already uses.
  //
  // Partial-failure contract (spec): if tickets.create fails, the form (dealForm/items/terms)
  // stays exactly as the user left it and the backend's own Thai message is toasted -- nothing
  // here resets state on error, so that falls out for free. If dealQuotations.create THEN fails
  // (ticket already exists), createdTicketId stays set so the very next click skips tickets.create
  // entirely and retries only the quotation -- a plain retry of createMutation-style logic would
  // instead mint a SECOND ticket for the same customer/project on every retry.
  async function handleInlineCreate() {
    setCreatingDeal(true);
    try {
      let ticketId = createdTicketId;
      if (!ticketId) {
        const ticketRes = await api.tickets.create({
          title: dealForm.customer.name,
          customerName: dealForm.customer.name,
          customerId: dealForm.customer.id,
          projectId: dealForm.project.id,
          contactId: contact?.id ?? null,
          entryChannel: dealForm.entryChannel,
          priority: 'NORMAL',
          items: [],
          nextFollowUpAt: addDaysIso(todayIso(), 7),
        });
        // TicketDto envelope (see the ticketQuery fix above) -- the new ticket's id lives at
        // `.summary.id`, never `.ticket.id`/`.id`.
        ticketId = ticketRes.ticket?.summary?.id;
        if (!ticketId) throw new Error('สร้างดีลไม่สำเร็จ');
        setCreatedTicketId(ticketId);
      }
      const quotationRes = await api.dealQuotations.create(ticketId, buildUpsertPayload());
      setDirty(false);
      setSaveError(null);
      rememberDefaults();
      queryClient.invalidateQueries({ queryKey: ['dealQuotations'] });
      showToast('success', 'บันทึกร่างแล้ว');
      navigate(`/quotations/${quotationRes.quotation.id}`, { replace: true });
    } catch (error) {
      setSaveError(error.message || 'บันทึกไม่สำเร็จ');
      showToast('error', error.message || 'บันทึกไม่สำเร็จ');
    } finally {
      setCreatingDeal(false);
    }
  }

  function handleSaveDraft() {
    if (id) updateMutation.mutate();
    else if (isInlineCreate) handleInlineCreate();
    else createMutation.mutate();
  }

  // submitConfirmOpen/submitMutation moved up above the autosave effect -- see that block's own
  // comment (#S2) for why the effect needs to read submitMutation.isPending.
  const [approveConfirmOpen, setApproveConfirmOpen] = useState(false);
  const [rejectOpen, setRejectOpen] = useState(false);
  const [rejectReason, setRejectReason] = useState('');
  const [cancelConfirmOpen, setCancelConfirmOpen] = useState(false);

  const approveMutation = useMutation({
    mutationFn: () => api.dealQuotations.approve(id, {}),
    onSuccess: (res) => {
      queryClient.setQueryData(queryKeys.dealQuotationDetail(id), res.quotation);
      queryClient.invalidateQueries({ queryKey: ['dealQuotations'] });
      showToast('success', 'อนุมัติแล้ว');
      setApproveConfirmOpen(false);
    },
    // #M8: a 409 here means another approver already decided this quotation while this screen
    // was open (or stale from having sat unfocused) -- refetch so the page reflects the real
    // current status instead of staying stuck showing PENDING_APPROVAL action buttons for a
    // decision that already happened, and surface the backend's own Thai message (it already
    // names the actual status) rather than the generic fallback.
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
      showToast('success', 'บันทึกการไม่อนุมัติแล้ว');
      setRejectOpen(false);
      setRejectReason('');
    },
    // #M8: same staleness guard as approve's onError above.
    onError: (error) => {
      if (error.status === 409) quotationQuery.refetch();
      showToast('error', error.message || 'บันทึกไม่สำเร็จ');
    },
  });

  const reviseMutation = useMutation({
    mutationFn: () => api.dealQuotations.createRevision(id, {}),
    onSuccess: (res) => {
      queryClient.invalidateQueries({ queryKey: ['dealQuotations'] });
      showToast('success', 'สร้างฉบับแก้ไขแล้ว');
      navigate(`/quotations/${res.quotation.id}`);
    },
    onError: (error) => showToast('error', error.message || 'สร้างฉบับแก้ไขไม่สำเร็จ'),
  });

  const cancelMutation = useMutation({
    mutationFn: () => api.dealQuotations.cancel(id, {}),
    onSuccess: (res) => {
      queryClient.setQueryData(queryKeys.dealQuotationDetail(id), res.quotation);
      queryClient.invalidateQueries({ queryKey: ['dealQuotations'] });
      showToast('success', 'ยกเลิกร่างแล้ว');
      setCancelConfirmOpen(false);
    },
    onError: (error) => showToast('error', error.message || 'ยกเลิกไม่สำเร็จ'),
  });

  const [downloading, setDownloading] = useState(null);
  async function handleDownload(format) {
    setDownloading(format);
    try {
      const blob = format === 'pdf' ? await api.dealQuotations.downloadPdf(id) : await api.dealQuotations.downloadXlsx(id);
      downloadBlob(blob, quotation?.number ?? 'quotation', format);
    } catch (error) {
      showToast('error', error.message || 'ดาวน์โหลดไม่สำเร็จ');
    } finally {
      setDownloading(null);
    }
  }

  if (id && quotationQuery.isLoading) return <RouteFallback />;
  if (id && quotationQuery.isError) {
    return (
      <PageStack>
        <PageHeader title="ใบเสนอราคา" />
        <Panel><p className="text-danger">{quotationQuery.error?.message || 'ไม่พบใบเสนอราคานี้'}</p></Panel>
      </PageStack>
    );
  }
  // isInlineCreate deliberately bypasses this gate -- it is the ONE case with neither an :id nor a
  // resolvable ticket where that is expected (there genuinely is no deal yet). A `?ticket=` that
  // fails to resolve (stale link, wrong id, no access) still hits this exactly as before.
  if (!id && !isInlineCreate && !ticketQuery.isLoading && !ticket) {
    return (
      <PageStack>
        <PageHeader title="สร้างใบเสนอราคา" />
        <Panel><p className="text-danger">ไม่พบดีลนี้ — เปิดหน้านี้จากรายละเอียดดีลเท่านั้น</p></Panel>
      </PageStack>
    );
  }

  // #M2: a brand-new quotation (`!quotation`) used to hardcode `true` here regardless of who was
  // looking -- an import/account read-only viewer (or anyone else who reached /quotations/new
  // via a stale link) got the full editor. Use canCreateDealQuotation(user, ticket), the same
  // predicate DealDirectQuotationPanel.jsx already gates the "สร้างใบเสนอราคา" link with, so the
  // two never disagree about who may start one. isInlineCreate has no ticket to check ownership
  // against yet (there is no deal until the first บันทึกร่าง creates one) -- the role-only
  // canCreateDealQuotationStandalone stands in for exactly that moment; it grants the same three
  // audiences canCreateDealQuotation already would the instant a ticket existed.
  const isEditable = quotation
    ? (isDealQuotationEditable(quotation) && canEditDealQuotation(user, quotation))
    : isInlineCreate
      ? canCreateDealQuotationStandalone(user)
      : canCreateDealQuotation(user, ticket);
  const readOnlyViewer = isDealQuotationReadOnlyViewer(user);
  const status = quotation ? dealQuotationStatusLabel(quotation.docStatus) : null;
  // V7 (owner review 2026-09-10): a pristine /quotations/new has no customer yet, and the `'-'`
  // fallback rendered the page subtitle as a bare "-" — a dash where a sentence belongs reads as a
  // failed load, not as "nothing chosen yet". Kept as `null` here so the subtitle below can say
  // what to do instead; the `'-'` fallback still applies wherever a dash is genuinely a value
  // (salesRepName in the document context strip).
  const customerName = quotation?.customerName ?? ticket?.customerName ?? (isInlineCreate ? dealForm.customer?.name : null) ?? null;
  const projectName = quotation?.projectName ?? ticket?.projectName ?? (isInlineCreate ? dealForm.project?.name : null) ?? null;
  // The REAL rep's name -- what "(ค่าเริ่มต้น)" (no override) actually means on both
  // salesRepDisplayId selects. Opus review fix (2026-09-14): this used to feed an
  // override-AWARE salesRepName/salesRepPhone pair too, used only by the card select's empty
  // option -- which made that option lie the moment an override was already saved (it showed the
  // override's own name+phone right next to the choice that turns the override OFF). Both
  // selects' options now read straight off `quotation.*`/`displayNameOptions` with no
  // intermediate override-resolved consts.
  const realSalesRepName = quotation?.salesRepName ?? ticket?.createdByName ?? (isInlineCreate ? user.name : null) ?? '-';
  const wasRejected = quotation?.docStatus === 'DRAFT' && Boolean(quotation?.approvalNote);
  const saving = createMutation.isPending || updateMutation.isPending || creatingDeal;
  // The customer whose contacts ผู้สั่งซื้อ may be chosen from: the deal's on the ?ticket= and
  // existing-DRAFT paths, the one being picked right now on the inline-create path.
  const contactCustomerId = ticket?.customerId ?? (isInlineCreate ? dealForm.customer?.id : null) ?? null;
  const contactError = showValidationSummary && !contact?.id ? 'กรุณาระบุผู้สั่งซื้อ' : undefined;

  return (
    <PageStack>
      <PageHeader
        title={quotation ? `ใบเสนอราคา ${quotation.number}` : 'สร้างใบเสนอราคา'}
        subtitle={customerName
          ? `${customerName}${projectName ? ` · ${projectName}` : ''}`
          : 'เริ่มจากเลือกลูกค้าและโครงการด้านล่าง'}
        context={(
          <>
            {status ? <StatusBadge tone={status.tone}>{status.label}</StatusBadge> : null}
            {/* Autosave receipt (owner ask 2026-09-10). `role="status"` so it is announced once
                rather than read as a live-updating alert; Bangkok local time, HH:mm, because that
                is the clock the rep is looking at. */}
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
              <>
                <Button variant="secondary" disabled={downloading === 'pdf'} onClick={() => handleDownload('pdf')}>ดาวน์โหลด PDF</Button>
                <Button variant="secondary" disabled={downloading === 'xlsx'} onClick={() => handleDownload('xlsx')}>ดาวน์โหลด Excel</Button>
              </>
            ) : null}
            {quotation && canSubmitDealQuotation(user, quotation) ? (
              <Button
                variant="primary"
                // #S2: disabled while ANY save is in flight (createMutation/updateMutation/
                // handleInlineCreate — the same three `saving` already covers), so a rep cannot
                // open the confirm dialog and submit while an autosave the dialog hasn't had a
                // chance to suppress yet is still on the wire.
                disabled={hasValidationErrors || hasMissingLeadTimes || saving}
                title={hasValidationErrors ? validationErrors.join(' ') : (leadTimeBlockMessage ?? undefined)}
                onClick={() => setSubmitConfirmOpen(true)}
              >
                ส่งขออนุมัติ
              </Button>
            ) : null}
            {quotation && canDecideDealQuotation(user, quotation) ? (
              <>
                <Button variant="danger" onClick={() => setRejectOpen(true)}>ไม่อนุมัติ</Button>
                <Button variant="success" onClick={() => setApproveConfirmOpen(true)}>อนุมัติ</Button>
              </>
            ) : null}
            {quotation && canReviseDealQuotation(user, quotation) ? (
              <Button variant="secondary" loading={reviseMutation.isPending} onClick={() => reviseMutation.mutate()}>สร้างฉบับแก้ไข</Button>
            ) : null}
            {quotation && canCancelDealQuotation(user, quotation) ? (
              <Button variant="danger" onClick={() => setCancelConfirmOpen(true)}>ยกเลิกร่าง</Button>
            ) : null}
          </>
        )}
      />

      {wasRejected ? (
        <div className="rounded-md border border-danger-border bg-danger/10 p-4 text-sm text-danger">
          <strong className="block">ไม่อนุมัติ — ส่งกลับเป็นร่าง</strong>
          <p className="m-0 mt-1">{quotation.approvalNote}</p>
        </div>
      ) : null}

      {!isEditable && quotation ? (
        <QuotationDocumentView quotation={quotation} />
      ) : !isEditable && !quotation ? (
        // #M2: a new-quotation visit (`!quotation`) from someone canCreateDealQuotation refuses --
        // a read-only viewer (import/account) who followed a stale link, or any other role that
        // reached /quotations/new some other way -- gets this instead of the editor form.
        <Panel>
          <p className="text-danger">
            {readOnlyViewer
              ? 'บัญชีนี้มีสิทธิ์ดูใบเสนอราคาเท่านั้น ไม่สามารถสร้างใบเสนอราคาได้'
              : 'ไม่มีสิทธิ์สร้างใบเสนอราคา — สร้างได้เฉพาะพนักงานขายเจ้าของดีลหรือผู้จัดการฝ่ายขายเท่านั้น'}
          </p>
        </Panel>
      ) : (
        <>
          {/* "ข้อมูลที่ยังไม่ครบ" — first thing in the form, so it is read before ส่งขออนุมัติ (which
              lives in the header above). Hidden on a pristine /quotations/new for the reason
              showValidationSummary documents; shown at once on an existing draft. */}
          {dirty || quotation ? <QuotationChecklist entries={checklist} /> : null}

          {isInlineCreate ? (
            // Owner ask 2026-09-10: no ticket exists yet -- ลูกค้า/โครงการ/ผู้ติดต่อ/ช่องทาง are
            // picked (or created) right here instead of the read-only summary below, which has
            // nothing to summarize until the first บันทึกร่าง mints the ticket.
            <>
              <DealCustomerCard
                value={{ ...dealForm, contact }}
                onChange={updateDealForm}
                errors={{
                  customer: showValidationSummary && !dealForm.customer ? 'กรุณาเลือกลูกค้า' : undefined,
                  project: showValidationSummary && dealForm.customer && !dealForm.project ? 'กรุณาเลือกโครงการ' : undefined,
                  contact: contactError,
                }}
                showToast={showToast}
              />
              <Panel title="ข้อมูลลูกค้าและผู้ขาย">
                <QuotationDealFields
                  terms={terms}
                  onChange={(patch) => { setTerms((current) => ({ ...current, ...patch })); setDirty(true); }}
                  idPrefix="inline-quotation"
                />
              </Panel>
            </>
          ) : (
            <Panel title="ข้อมูลลูกค้าและผู้ขาย">
              <div className="grid grid-cols-2 gap-3 mobile:grid-cols-1 text-sm">
                <div><span className="block text-2xs font-bold uppercase text-text-muted">ลูกค้า</span><strong>{customerName ?? '-'}</strong></div>
                {/* Owner feedback 2026-09-14 ("sometimes there's a typo ... they should be able to
                    correct it") — was static text; now a real field on `terms`, same as every
                    other เงื่อนไข input, saved on the next draft save. */}
                <FormField label="โครงการ" htmlFor="projectNameCard">
                  <input
                    id="projectNameCard"
                    value={terms.projectName}
                    maxLength={200}
                    onChange={(e) => { setTerms((t) => ({ ...t, projectName: e.target.value })); setDirty(true); }}
                  />
                </FormField>
                {/* V179: the SAME salesRepDisplayId select as the เงื่อนไข panel below (owner,
                    2026-09-14: "keep both") — a distinct DOM id ("...Card") since a page may not
                    repeat an id, both bound to the one `terms.salesRepDisplayId`, so picking a
                    value in either place updates both.
                    Opus review fix (2026-09-14): the EMPTY option means "use the real name" — its
                    label must show the REAL rep (realSalesRepName + quotation.salesRepPhone
                    directly), never whatever override happens to be CURRENTLY saved — an earlier
                    version read an override-resolved pair here, which made this option lie the
                    moment an override was set: it showed the override's own name+phone right next
                    to the choice that turns the override off. The phone shown when an override IS
                    selected still comes from the last SAVED value (quotation.salesRepDisplayPhone,
                    read inside buildDealQuotationDto/QuotationDocumentView) — the options list
                    itself carries no phone, so an unsaved selection cannot preview one; it appears
                    on the next successful save. */}
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
                {/* Hidden when the viewer cannot open the deal page — under the release lock
                    (owner, 2026-09-11) a sales rep reaches /quotations but not /tickets, and a
                    link straight to the access-denied page is worse than no link. */}
                {effectiveTicketId && canAccessPath(`/tickets/${effectiveTicketId}`, user) ? (
                  <div>
                    <span className="block text-2xs font-bold uppercase text-text-muted">ดีล</span>
                    <Link to={`/tickets/${effectiveTicketId}`} className="text-sm text-info underline">ดูรายละเอียดดีลนี้</Link>
                  </div>
                ) : null}
                {/* Owner, 2026-09-11: the customer's ที่อยู่ / เลขที่ผู้เสียภาษี / โทร., prefilled
                    from the customer record and editable here too — the SAME component the
                    inline-create card renders. A save marks the quotation dirty, because only the
                    next draft save re-snapshots the customer onto the document. */}
                {summaryCustomer?.id ? (
                  <div className="col-span-full">
                    <CustomerDetailsFields
                      customer={summaryCustomer}
                      onChange={(next) => { setCustomerOverride(next); setDirty(true); }}
                      showToast={showToast}
                    />
                  </div>
                ) : null}
                {/* ผู้สั่งซื้อ (owner feedback F2) — the SAME picker DealCustomerCard renders on
                    the inline-create path, so the required-ness and the inline-add fields cannot
                    diverge between the two. Prefilled from the deal's own contact (see the init
                    effect) and changeable from here. */}
                <QuotationContactPicker
                  customerId={contactCustomerId}
                  customerName={customerName}
                  value={contact}
                  onChange={(next) => { setContact(next); setDirty(true); }}
                  onResolve={setContact}
                  error={contactError}
                  showToast={showToast}
                  idPrefix="quotation-contact"
                />
                <QuotationDealFields
                  terms={terms}
                  onChange={(patch) => { setTerms((current) => ({ ...current, ...patch })); setDirty(true); }}
                  idPrefix="quotation"
                />
              </div>
            </Panel>
          )}

          {/* ── รูปแบบเอกสาร (quotation v3/v3b, owner feedback pass 3, 2026-09-11) ──────────────
              Chosen ONCE per quotation, above the rows they reshape. Language comes first because
              it constrains the price modes (no ราคาพิเศษ on the English form) and carries the
              currency with it — one pick, everything else follows. */}
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
                hint={availablePriceModes(docSettings.documentLanguage).find((opt) => opt.code === docSettings.priceMode)?.hint}
              >
                <div className="flex flex-wrap gap-2" role="group" aria-label="วิธีกรอกราคากระเบื้อง">
                  {/* SPECIAL_SQM is not RENDERED on an English document — not merely disabled:
                      a greyed-out option still invites the question, and there is no state in
                      which choosing it would be accepted. */}
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
              </FormField>
            </div>
            {settingsNotice ? (
              <p role="status" className="m-0 mt-3 rounded-md border border-warning-border bg-warning/10 p-3 text-xs text-warning">
                {settingsNotice}
              </p>
            ) : null}
          </Panel>

          {/* ── รายการสินค้า, grouped by ตำแหน่งติดตั้ง (owner feedback F1, 2026-09-10) ──────
              "if there were to be a ตำแหน่งติดตั้ง there might be multiple รายการ in one
              ตำแหน่งติดตั้ง." Each group is a bordered block with its label typed ONCE in its own
              header — which is the typing saving: a floor with six tiles is one label, not six.
              The blocks read top-to-bottom in exactly the order the printed document groups them
              (see locationGroupsFromItems), so the editor is a preview of the page structure. */}
          <Panel
            title="รายการสินค้า"
            actions={(
              <Button variant="secondary" size="sm" onClick={addGroup}>
                <Icon name="plus" size={14} />
                เพิ่มตำแหน่ง
              </Button>
            )}
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
                      {/* `min-w-0` so this action cluster is squeezable: "เพิ่มรายการในตำแหน่งนี้"
                          is a 179 px unbreakable-ish Thai run, and a flex item's automatic minimum
                          size is its min-content unless this is set — the same mechanism that let
                          the item rows' selects force the page wide (see QuotationItemRow). */}
                      <div className="flex min-w-0 flex-wrap items-center gap-2">
                        <span className="text-2xs font-bold text-text-muted">{groupItems.length} รายการ</span>
                        <Button variant="secondary" size="sm" onClick={() => addItem(group.groupId)}>
                          <Icon name="plus" size={13} />
                          เพิ่มรายการในตำแหน่งนี้
                        </Button>
                        {/* v3 S2: freight, consumables, a cut service, sanitary ware — a row with
                            none of the tile fields. Deliberately NOT worded "เพิ่มรายการ…": the
                            tile button above keeps that name, and this one says what it is for. */}
                        <Button
                          variant="secondary"
                          size="sm"
                          aria-label="เพิ่มสินค้าหรือบริการอื่นในตำแหน่งนี้"
                          onClick={() => addPlainItem(group.groupId)}
                        >
                          <Icon name="plus" size={13} />
                          สินค้า/บริการอื่น
                        </Button>
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
                      // MED-4: an empty group has no item rows to carry its label, so it does not
                      // survive a save and prints nothing. Said out loud rather than letting the
                      // group quietly disappear on the next reload.
                      <p className="m-0 text-xs text-warning">
                        ยังไม่มีรายการในตำแหน่งนี้ — ตำแหน่งที่ไม่มีรายการจะไม่ถูกบันทึกและไม่ปรากฏในเอกสาร
                      </p>
                    ) : (
                      <ul className="grid gap-3">
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
                              priceMode={docSettings.priceMode}
                              documentLanguage={docSettings.documentLanguage}
                              currency={currency}
                              key={item.clientId}
                              item={item}
                              index={index}
                              groupId={group.groupId}
                              locationGroups={groups}
                              recentPicks={recentPicks}
                              // #7: `submitItemErrorsByRow` is `itemErrorsByRow` PLUS the lead-time
                              // check — a strict superset — so the row's own inline hints highlight
                              // a missing lead time too, without that check feeding the (blocking)
                              // checklist itemErrorsByRow otherwise drives.
                              errors={touchedRowIds.has(item.clientId) ? submitItemErrorsByRow[index] : EMPTY_ITEM_ERRORS}
                              onChange={(patch) => updateItem(item.clientId, patch)}
                              onRemove={() => removeItem(item.clientId)}
                              onMove={(targetGroupId) => moveItemToGroup(item.clientId, targetGroupId)}
                              onDuplicate={(targetGroupId) => duplicateItem(item.clientId, targetGroupId)}
                              onCatalogPicked={handleCatalogPicked}
                            />
                          );
                        })}
                      </ul>
                    )}
                  </section>
                );
              })}

              {/* ── ส่วนลดพิเศษ (v3 S3) — always AFTER every group, because it always prints last:
                  it is a percentage of the rows above it. Offered only once there is a product row
                  to take it from; an adjustment-only quotation is a server 400. */}
              <section aria-label="ส่วนลดพิเศษ" className="grid gap-3">
                {adjustments.length ? (
                  <ul className="grid gap-3">
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
              </section>
            </div>
          </Panel>

          <Panel title="เงื่อนไข">
            <div className="grid grid-cols-2 gap-3 mobile:grid-cols-1">
              {/* V8 (owner review 2026-09-10): this was labelled "วันที่รับจำนวน", which reads as
                  "the date a quantity was received" with no subject and was taken for the
                  quotation's own date. It is neither — it is the date the CUSTOMER supplied the
                  quantities being quoted, and the document says so verbatim in remark 1
                  (DealQuotationRenderAdapter: "1.จำนวนที่เสนอข้างต้นเป็นจำนวนที่ได้รับมาเมื่อวันที่ …").
                  Label and hint now match that sentence, so the field and the line it feeds
                  cannot be read as two different things. The quotation's own date is `createdAt`
                  (F8) and is not editable here. */}
              <FormField
                label="วันที่ที่ได้รับจำนวนจากลูกค้า"
                htmlFor="offerDate"
                hint="พิมพ์ในหมายเหตุข้อ 1: จำนวนที่เสนอข้างต้นเป็นจำนวนที่ได้รับมาเมื่อวันที่ …"
              >
                <input id="offerDate" type="date" value={terms.offerDate} onChange={(e) => { setTerms((t) => ({ ...t, offerDate: e.target.value })); setDirty(true); }} />
              </FormField>
              <FormField label="มัดจำ %" htmlFor="depositPercent">
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
              </FormField>
              {/* No `htmlFor`: ส่วนที่เหลือ is a SET of toggle buttons, not one control. The label
                  used to point at `remainderMode`, an id that belongs to the เครดิต day-count
                  input below — which is a different field with a different meaning, and which only
                  exists at all while CREDIT is selected, so for the other modes the label pointed
                  at nothing. `role="group"` + aria-label is how a group of controls carries one
                  name; the day input now carries its own. */}
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
                        /* NOT "จำนวนวันเครดิต": an accessible name STARTING with "จำนวน"
                           collides with the item row's own จำนวน field under the prefix
                           queries the tests use, and this input renders only in CREDIT mode,
                           so the collision appears or vanishes with a remembered preference —
                           i.e. it made a test's outcome depend on which test ran before it.
                           Leading with เครดิต keeps the name specific and unambiguous. */
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
              {/* V178 (owner ruling 2026-09-14): ระบุวันที่ ("กำหนดวันที่ได้") is a SECOND way to
                  say กำหนดยืนยันราคา, for when a promotion or a factory allocation needs an exact
                  deadline rather than "N days from now" — but it only makes sense on a document
                  that HAS special pricing to protect, so the option itself is hidden otherwise
                  (docHasSpecialPricing, mirrored from the server's own gate). No `htmlFor`, same
                  reasoning as ส่วนที่เหลือ above: this is a group of controls, not one. */}
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
                    <input
                      id="validityUntil"
                      aria-label="ยืนราคาถึงวันที่"
                      type="date"
                      value={terms.validityUntil}
                      onChange={(e) => { setTerms((t) => ({ ...t, validityUntil: e.target.value })); setDirty(true); }}
                    />
                  ) : (
                    // จำนวนวัน keeps its OWN select even while ระบุวันที่ shows — the value is
                    // never cleared on a mode switch (see #buildUpsertPayload's own comment), so
                    // switching back to จำนวนวัน (by choice, or by the auto-switch effect above)
                    // always has a real number to fall back to.
                    // Opus review of V178 (2026-09-14): FormField's <label> is a SIBLING of its
                    // children (FormField.jsx), not a wrapper — it labels nothing once this
                    // conditional branch stopped passing htmlFor to FormField itself. Same
                    // collision as the ส่วนที่เหลือ block above (see that comment): the accessible
                    // name must NOT start with "จำนวน" or it collides with เครดิต's own field.
                    <select id="validityDays" aria-label="ยืนราคา (จำนวนวัน)" value={terms.validityDays} onChange={(e) => { setTerms((t) => ({ ...t, validityDays: e.target.value })); setDirty(true); }}>
                      <option value="">-</option>
                      {VALIDITY_DAYS_OPTIONS.map((d) => <option key={d} value={d}>{d} วัน</option>)}
                    </select>
                  )}
                </div>
              </FormField>
              {/* V179 (owner feedback #4, 2026-09-14): "กรณีที่ admin ช่วยทำใบเสนอราคาแทนเซลล์
                  อยากให้แสดงชื่อผู้พิมพ์เป็นชื่อแอดมิน ส่วนชื่อพนักงานขายเป็นชื่อเซลล์" — PRINT-ONLY
                  name selection. Neither field changes who created/owns the deal, who may edit it,
                  or who earns commission on it (createdById/salesRepId are untouched) — only what
                  two names the PDF prints. "(ค่าเริ่มต้น)" (the empty option, value="") sends null,
                  which prints exactly as today: the real creator/sales rep. Both selects share the
                  SAME eligible-employee list (the union of ฝ่ายขาย members and can_create_quotation
                  grant holders), fetched once above. */}
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
            <FormField label="หมายเหตุเพิ่มเติม" htmlFor="customerNotes">
              <textarea
                id="customerNotes"
                className="min-h-24"
                value={terms.customerNotes}
                onChange={(e) => { setTerms((t) => ({ ...t, customerNotes: e.target.value })); setDirty(true); }}
              />
            </FormField>
          </Panel>

          <Panel title="ยอดรวม">
            {/* v3b: an English document prints Grand Total (USD) and NO VAT row at all, so the
                editor shows no VAT line for it either — not a "0.00" one, which would imply a
                rate that the English form does not have. */}
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
              // #M6: computed client-side from `items` rather than a bare "ยังไม่บันทึก" — sales
              // gets a live running total while typing, explicitly labelled an estimate since the
              // authoritative rounding is server-side (WastageCalculator).
              <div className="flex flex-col items-end gap-1">
                <p className="m-0 text-xs font-bold text-warning">ยอดโดยประมาณ (ยังไม่บันทึก)</p>
                <span className="text-sm text-text-muted">รวมเป็นเงิน{isEnglish ? ' (USD)' : ''} <span className="tabular-nums text-text">{formatQuotationMoney(provisionalSubtotal, currency)}</span></span>
                {!isEnglish ? (
                  <span className="text-sm text-text-muted">ภาษีมูลค่าเพิ่ม 7% <span className="tabular-nums text-text">{formatQuotationMoney(provisionalVat, currency)}</span></span>
                ) : null}
                <span className="text-lg font-extrabold">ยอดรวมทั้งสิ้น{isEnglish ? ' (USD)' : ''} <span className="tabular-nums">{formatQuotationMoney(provisionalGrand, currency)}</span></span>
                {isEnglish ? <span className="text-2xs text-text-muted">เอกสารภาษาอังกฤษไม่มีภาษีมูลค่าเพิ่ม</span> : null}
                {/* v3 F3: the server refuses a document whose total goes negative (two large
                    adjustments, or a flat one bigger than the rows). Warned here from the
                    estimate; the save is NOT blocked on it, because the estimate can lag a
                    pending preview — the server's own 400 is the authority and is shown below. */}
                {provisionalSubtotal < 0 ? (
                  <p className="m-0 text-xs font-bold text-danger">ส่วนลดพิเศษมากกว่ายอดรวมรายการ — ระบบจะไม่รับบันทึกยอดติดลบ</p>
                ) : null}
              </div>
            )}
            {saveError ? (
              <p role="alert" className="m-0 mt-3 rounded-md border border-danger-border bg-danger/10 p-3 text-xs text-danger">
                บันทึกไม่สำเร็จ: {saveError}
              </p>
            ) : null}
          </Panel>

          {isEditable ? (
            // `justify-stretch` has no effect on a flex MAIN axis item's own size (it governs the
            // CROSS axis) -- on mobile:flex-row this left the button small and left-aligned. The
            // button itself needs `mobile:w-full` to actually fill the bar.
            <div className="flex justify-end rounded-md border border-border-muted bg-surface p-4">
              <Button
                variant="secondary"
                className="mobile:w-full"
                loading={saving}
                // #S2: disabled while ส่งขออนุมัติ is running its own pre-save PUT too -- a manual
                // save firing at the same moment would be the exact race this fix removes, just
                // from the other button.
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

      {submitConfirmOpen ? (
        <Modal
          title="ส่งขออนุมัติใบเสนอราคา"
          onClose={() => setSubmitConfirmOpen(false)}
          footer={(
            <>
              <Button variant="secondary" onClick={() => setSubmitConfirmOpen(false)}>ยกเลิก</Button>
              {/* #S2: disabled while any OTHER save is still in flight — the dialog being open
                  already suppresses the NEXT autosave tick, but one already on the wire when the
                  dialog opened is not cancelled by that, only waited out. */}
              <Button
                variant="primary"
                loading={submitMutation.isPending}
                // #7: defensive — the opening button above is already disabled while
                // `hasMissingLeadTimes`, so this only matters if an edit made mid-dialog removed a
                // lead time the rep had entered.
                disabled={saving || hasMissingLeadTimes}
                title={leadTimeBlockMessage ?? undefined}
                onClick={() => submitMutation.mutate()}
              >
                ส่งขออนุมัติ
              </Button>
            </>
          )}
        >
          <p>ส่งใบเสนอราคา {quotation?.number} ให้ผู้จัดการฝ่ายขายหรือผู้บริหารอนุมัติ ต้องการดำเนินการต่อหรือไม่</p>
          {/* #7: unlike checklistWarnings below, this ONE genuinely blocks — the opening button is
              disabled while it is true, so this only shows if the dialog was already open when a
              concurrent edit removed a lead time. */}
          {leadTimeBlockMessage ? (
            <p role="alert" className="mt-3 rounded-md border border-danger-border bg-danger/10 p-3 text-xs text-danger">
              {leadTimeBlockMessage}
            </p>
          ) : null}
          {/* The optional gaps, restated at the moment of sending — never a blocker (see
              QUOTATION_BLOCKING_CHECKS), but the last chance to notice the document will print
              without them. */}
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
        // #M5: same confirm-Modal shape as ส่งขออนุมัติ/ยกเลิกร่าง above -- อนุมัติ used to fire
        // the mutation straight off the button click, the only write action here that did.
        <Modal
          title="อนุมัติใบเสนอราคา"
          onClose={() => setApproveConfirmOpen(false)}
          footer={(
            <>
              <Button variant="secondary" onClick={() => setApproveConfirmOpen(false)}>ยกเลิก</Button>
              <Button variant="success" loading={approveMutation.isPending} onClick={() => approveMutation.mutate()}>ยืนยันอนุมัติ</Button>
            </>
          )}
        >
          <p>
            อนุมัติใบเสนอราคา {quotation?.number} ยอดรวมทั้งสิ้น {formatQuotationMoney(quotation?.grandTotal, quotation?.currency)} ต้องการดำเนินการต่อหรือไม่
          </p>
        </Modal>
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
    </PageStack>
  );
}
