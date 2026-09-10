import { useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { api } from '../../api/index.js';
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
import { addDaysIso, bangkokTodayIso, formatMoney } from '../../utils/format.js';
import {
  canApproveDealQuotation, canCancelDealQuotation, canCreateDealQuotation,
  canCreateDealQuotationStandalone, canDecideDealQuotation, canEditDealQuotation,
  canReviseDealQuotation, canSubmitDealQuotation, DEPOSIT_PERCENT_PRESETS,
  dealQuotationStatusLabel, isDealQuotationEditable, isDealQuotationReadOnlyViewer,
  quotationItemMissingSummary, REMAINDER_MODE_OPTIONS, validateQuotationItem, VALIDITY_DAYS_OPTIONS,
} from './quotationMeta.js';
import { DealCustomerCard } from './DealCustomerCard.jsx';
import { QuotationDocumentView } from './QuotationDocumentView.jsx';
import { emptyQuotationItem, itemInputFromRow, QuotationItemRow } from './QuotationItemRow.jsx';

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
];

// A stable empty-object reference for an untouched row's `errors` prop -- avoids handing
// QuotationItemRow a fresh `{}` literal every render (new reference each time otherwise, though
// this component doesn't rely on it for memoisation today; cheap to keep stable regardless).
const EMPTY_ITEM_ERRORS = {};

function pickCalculatedFields(source) {
  const result = {};
  for (const key of CALCULATED_ITEM_FIELDS) {
    if (key in source) result[key] = source[key];
  }
  return result;
}

function emptyTerms() {
  return {
    deptCode: '', unitCode: '', offerDate: todayIso(),
    depositPercent: '', depositPercentCustom: false,
    remainderMode: '', creditDays: '', validityDays: '', customerNotes: '',
  };
}

// Owner ask 2026-09-10 ("inline deal creation", inline-deal-spec.md): the "ลูกค้าและโครงการ" card
// state for a brand-new /quotations/new visit with no `?ticket=` -- nothing here exists on the
// server yet, unlike `terms`/`items` which round-trip a real ticket/quotation.
function emptyDealForm() {
  return { customer: null, project: null, contact: null, entryChannel: 'UNSPECIFIED' };
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
  const ticketQuery = useQuery({
    queryKey: queryKeys.ticketDetail(effectiveTicketId),
    queryFn: () => api.tickets.get(effectiveTicketId).then((r) => r.ticket?.summary),
    enabled: !id && !!effectiveTicketId,
  });
  const ticket = ticketQuery.data ?? null;

  // Owner ask 2026-09-10 ("inline deal creation"): /quotations/new with NEITHER an :id NOR a
  // ?ticket= is a brand-new deal that does not exist anywhere yet -- the rep picks/creates the
  // customer and project right here instead of being sent to /tickets first. Stable per-render
  // (not stateful): it only ever describes the URL the page was loaded with.
  const isInlineCreate = !id && !ticketIdParam;
  const [dealForm, setDealForm] = useState(emptyDealForm);
  function updateDealForm(patch) {
    setDealForm((prev) => ({ ...prev, ...patch }));
    setDirty(true);
  }
  // Set once the inline flow's own `tickets.create` succeeds. Kept across a subsequent
  // dealQuotations.create failure so a retry reuses this ticket instead of minting a second deal
  // for the same customer/project (see handleInlineCreate below).
  const [createdTicketId, setCreatedTicketId] = useState(null);
  const [creatingDeal, setCreatingDeal] = useState(false);

  const [items, setItems] = useState([]);
  const [terms, setTerms] = useState(emptyTerms());
  const [dirty, setDirty] = useState(false);
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
      setItems(quotation.items.map((it) => ({ ...it, clientId: it.id, calcPending: false })));
      setTouchedRowIds(new Set(quotation.items.map((it) => it.id)));
      setTerms({
        deptCode: quotation.deptCode ?? '', unitCode: quotation.unitCode ?? '',
        offerDate: quotation.offerDate ?? todayIso(),
        depositPercent: quotation.depositPercent ?? '',
        depositPercentCustom: quotation.depositPercent != null && !DEPOSIT_PERCENT_PRESETS.includes(quotation.depositPercent),
        remainderMode: quotation.remainderMode ?? '', creditDays: quotation.creditDays ?? '',
        validityDays: quotation.validityDays ?? '', customerNotes: quotation.customerNotes ?? '',
      });
      setDirty(false);
      setInitializedFor(key);
    } else if (effectiveTicketId) {
      setItems([]);
      setTerms(emptyTerms());
      setTouchedRowIds(new Set());
      setDirty(false);
      setInitializedFor(key);
    }
  }, [id, quotation, effectiveTicketId, initializedFor]);

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
    const target = { ...currentItem, ...patch };

    clearTimeout(calcTimers.current[clientId]);
    const seq = (calcSeq.current[clientId] = (calcSeq.current[clientId] ?? 0) + 1);
    calcTimers.current[clientId] = setTimeout(() => {
      api.dealQuotations.calculateLine(itemInputFromRow(target))
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
          setItems((cur) => cur.map((it) => (it.clientId === clientId ? { ...it, calcPending: false } : it)));
        });
    }, 300);
  }

  function addItem() {
    setDirty(true);
    setItems((prev) => [...prev, emptyQuotationItem()]);
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

  const existingLabels = useMemo(
    () => [...new Set(items.map((it) => it.locationLabel).filter(Boolean))],
    [items],
  );

  // #M6: live sums off `items` -- what the SERVER would compute once saved (WastageCalculator
  // itself lives only in the backend, so this is an estimate, labelled as one below) -- shown
  // only while there is nothing authoritative yet (no saved quotation, or unsaved edits on top of
  // one). `lineAmount` is null until a row's first calculate-line response lands, which sums to 0
  // -- exactly the "nothing to show yet" state the old bare "ยังไม่บันทึก" message covered.
  const provisionalSubtotal = useMemo(
    () => items.reduce((sum, it) => sum + (Number(it.lineAmount) || 0), 0),
    [items],
  );
  const provisionalVat = Math.round(provisionalSubtotal * 0.07 * 100) / 100;
  const provisionalGrand = provisionalSubtotal + provisionalVat;

  // #M4 (owner ruling 2026-09-10): every item must be COMPLETE before บันทึกร่าง / ส่งขออนุมัติ --
  // "sales can also fill in their own item if it is not in the database, but ALL info about the
  // tile has to be completed." validateQuotationItem (quotationMeta.js) is the single source of
  // truth for what "complete" means; this array parallels `items` 1:1 so both the per-row summary
  // below AND each row's inline hints (QuotationItemRow's `errors` prop) read off the exact same
  // computation -- never two independent checks that could disagree about the same row.
  const itemErrorsByRow = useMemo(() => items.map((it) => validateQuotationItem(it)), [items]);

  // Thai messages, shown inline AND as the disabled button's tooltip -- advisory only, the server
  // re-validates regardless (e.g. submit's own "ต้องมีอย่างน้อยหนึ่งรายการ" 400).
  //
  // Inline-create adds its own precondition on top: ลูกค้า/โครงการ (owner ask 2026-09-10) are what
  // handleInlineCreate needs to even attempt tickets.create, so the same disabled-button + inline
  // Thai-hint mechanism gates them exactly like every other precondition here.
  const validationErrors = useMemo(() => {
    const errors = [];
    if (isInlineCreate) {
      if (!dealForm.customer) errors.push('ต้องเลือกลูกค้าก่อนบันทึกร่าง');
      if (!dealForm.project) errors.push('ต้องเลือกโครงการก่อนบันทึกร่าง');
    }
    if (items.length === 0) {
      errors.push('ต้องมีรายการสินค้าอย่างน้อย 1 รายการ');
      return errors;
    }
    // The per-row "ขาด ..." summary is NOT gated on touchedRowIds -- unlike the inline hints on
    // each field, this box is the whole reason the button is disabled, so it always shows every
    // incomplete row regardless of whether the rep has touched it yet.
    itemErrorsByRow.forEach((rowErrors, index) => {
      const summary = quotationItemMissingSummary(rowErrors, index);
      if (summary) errors.push(summary);
    });
    return errors;
  }, [items, itemErrorsByRow, isInlineCreate, dealForm.customer, dealForm.project]);
  const hasValidationErrors = validationErrors.length > 0;

  function buildUpsertPayload() {
    return {
      deptCode: terms.deptCode || null,
      unitCode: terms.unitCode || null,
      offerDate: terms.offerDate || null,
      depositPercent: terms.depositPercent === '' ? null : Number(terms.depositPercent),
      remainderMode: terms.remainderMode || null,
      creditDays: terms.creditDays === '' ? null : Number(terms.creditDays),
      validityDays: terms.validityDays === '' ? null : Number(terms.validityDays),
      customerNotes: terms.customerNotes || null,
      items: items.map(itemInputFromRow),
    };
  }

  const createMutation = useMutation({
    mutationFn: () => api.dealQuotations.create(effectiveTicketId, buildUpsertPayload()),
    onSuccess: (res) => {
      setDirty(false);
      queryClient.invalidateQueries({ queryKey: ['dealQuotations'] });
      showToast('success', 'บันทึกร่างแล้ว');
      navigate(`/quotations/${res.quotation.id}`, { replace: true });
    },
    onError: (error) => showToast('error', error.message || 'บันทึกไม่สำเร็จ'),
  });

  const updateMutation = useMutation({
    mutationFn: () => api.dealQuotations.update(id, buildUpsertPayload()),
    onSuccess: (res) => {
      setDirty(false);
      queryClient.setQueryData(queryKeys.dealQuotationDetail(id), res.quotation);
      queryClient.invalidateQueries({ queryKey: ['dealQuotations'] });
      showToast('success', 'บันทึกร่างแล้ว');
    },
    onError: (error) => showToast('error', error.message || 'บันทึกไม่สำเร็จ'),
  });

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
          contactId: dealForm.contact?.id ?? null,
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
      queryClient.invalidateQueries({ queryKey: ['dealQuotations'] });
      showToast('success', 'บันทึกร่างแล้ว');
      navigate(`/quotations/${quotationRes.quotation.id}`, { replace: true });
    } catch (error) {
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

  const [submitConfirmOpen, setSubmitConfirmOpen] = useState(false);
  const [approveConfirmOpen, setApproveConfirmOpen] = useState(false);
  const [rejectOpen, setRejectOpen] = useState(false);
  const [rejectReason, setRejectReason] = useState('');
  const [cancelConfirmOpen, setCancelConfirmOpen] = useState(false);

  const submitMutation = useMutation({
    mutationFn: () => api.dealQuotations.submit(id),
    onSuccess: (res) => {
      queryClient.setQueryData(queryKeys.dealQuotationDetail(id), res.quotation);
      queryClient.invalidateQueries({ queryKey: ['dealQuotations'] });
      showToast('success', 'ส่งขออนุมัติแล้ว');
      setSubmitConfirmOpen(false);
    },
    onError: (error) => showToast('error', error.message || 'ส่งขออนุมัติไม่สำเร็จ'),
  });

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
  const customerName = quotation?.customerName ?? ticket?.customerName ?? (isInlineCreate ? dealForm.customer?.name : null) ?? '-';
  const projectName = quotation?.projectName ?? ticket?.projectName ?? (isInlineCreate ? dealForm.project?.name : null) ?? null;
  const salesRepName = quotation?.salesRepName ?? ticket?.createdByName ?? (isInlineCreate ? user.name : null) ?? '-';
  const salesRepPhone = quotation?.salesRepPhone ?? null;
  const wasRejected = quotation?.docStatus === 'DRAFT' && Boolean(quotation?.approvalNote);
  const saving = createMutation.isPending || updateMutation.isPending || creatingDeal;

  return (
    <PageStack>
      <PageHeader
        title={quotation ? `ใบเสนอราคา ${quotation.number}` : 'สร้างใบเสนอราคา'}
        subtitle={`${customerName}${projectName ? ` · ${projectName}` : ''}`}
        context={status ? <StatusBadge tone={status.tone}>{status.label}</StatusBadge> : null}
        actions={(
          <>
            {quotation ? (
              <>
                <Button variant="secondary" disabled={downloading === 'pdf'} onClick={() => handleDownload('pdf')}>ดาวน์โหลด PDF</Button>
                <Button variant="secondary" disabled={downloading === 'xlsx'} onClick={() => handleDownload('xlsx')}>ดาวน์โหลด Excel</Button>
              </>
            ) : null}
            {isEditable ? (
              <Button
                variant="secondary"
                loading={saving}
                disabled={hasValidationErrors}
                title={hasValidationErrors ? validationErrors.join(' ') : undefined}
                onClick={handleSaveDraft}
              >
                บันทึกร่าง
              </Button>
            ) : null}
            {quotation && canSubmitDealQuotation(user, quotation) ? (
              <Button
                variant="primary"
                disabled={hasValidationErrors}
                title={hasValidationErrors ? validationErrors.join(' ') : undefined}
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
          {isInlineCreate ? (
            // Owner ask 2026-09-10: no ticket exists yet -- ลูกค้า/โครงการ/ผู้ติดต่อ/ช่องทาง are
            // picked (or created) right here instead of the read-only summary below, which has
            // nothing to summarize until the first บันทึกร่าง mints the ticket.
            <DealCustomerCard
              value={dealForm}
              onChange={updateDealForm}
              errors={{
                customer: !dealForm.customer ? 'กรุณาเลือกลูกค้า' : undefined,
                project: dealForm.customer && !dealForm.project ? 'กรุณาเลือกโครงการ' : undefined,
              }}
              showToast={showToast}
            />
          ) : (
            <Panel title="ข้อมูลลูกค้าและผู้ขาย">
              <div className="grid grid-cols-2 gap-3 mobile:grid-cols-1 text-sm">
                <div><span className="block text-2xs font-bold uppercase text-text-muted">ลูกค้า</span><strong>{customerName}</strong></div>
                <div><span className="block text-2xs font-bold uppercase text-text-muted">โครงการ</span><strong>{projectName ?? '-'}</strong></div>
                <div><span className="block text-2xs font-bold uppercase text-text-muted">พนักงานขาย</span><strong>{salesRepName}{salesRepPhone ? ` · T.${salesRepPhone}` : ''}</strong></div>
                {effectiveTicketId ? (
                  <div>
                    <span className="block text-2xs font-bold uppercase text-text-muted">ดีล</span>
                    <Link to={`/tickets/${effectiveTicketId}`} className="text-sm text-info underline">ดูรายละเอียดดีลนี้</Link>
                  </div>
                ) : null}
                <FormField label="ฝ่าย" htmlFor="deptCode">
                  <input id="deptCode" value={terms.deptCode} onChange={(e) => { setTerms((t) => ({ ...t, deptCode: e.target.value })); setDirty(true); }} />
                </FormField>
                <FormField label="หน่วยงาน" htmlFor="unitCode">
                  <input id="unitCode" value={terms.unitCode} onChange={(e) => { setTerms((t) => ({ ...t, unitCode: e.target.value })); setDirty(true); }} />
                </FormField>
              </div>
            </Panel>
          )}

          <Panel
            title="รายการสินค้า"
            actions={(
              <Button variant="secondary" size="sm" onClick={addItem}>
                <Icon name="plus" size={14} />
                เพิ่มรายการ
              </Button>
            )}
          >
            <datalist id="quotation-location-labels">
              {existingLabels.map((label) => <option key={label} value={label} />)}
            </datalist>
            {items.length === 0 ? (
              <p className="text-sm text-text-muted">ยังไม่มีรายการสินค้า — กด &quot;เพิ่มรายการ&quot; เพื่อเริ่มต้น</p>
            ) : (
              <ul className="grid gap-3">
                {items.map((item, index) => (
                  <QuotationItemRow
                    key={item.clientId}
                    item={item}
                    index={index}
                    errors={touchedRowIds.has(item.clientId) ? itemErrorsByRow[index] : EMPTY_ITEM_ERRORS}
                    onChange={(patch) => updateItem(item.clientId, patch)}
                    onRemove={() => removeItem(item.clientId)}
                  />
                ))}
              </ul>
            )}
          </Panel>

          {hasValidationErrors ? (
            <div className="rounded-md border border-warning-border bg-warning/10 p-3.5 text-xs text-warning" role="alert">
              <strong className="block">กรอกข้อมูลให้ครบก่อนบันทึกหรือส่งขออนุมัติ</strong>
              <ul className="m-0 mt-1 list-disc pl-4">
                {validationErrors.map((message) => <li key={message}>{message}</li>)}
              </ul>
            </div>
          ) : null}

          <Panel title="เงื่อนไข">
            <div className="grid grid-cols-2 gap-3 mobile:grid-cols-1">
              <FormField label="วันที่รับจำนวน" htmlFor="offerDate">
                <input id="offerDate" type="date" value={terms.offerDate} onChange={(e) => { setTerms((t) => ({ ...t, offerDate: e.target.value })); setDirty(true); }} />
              </FormField>
              <FormField label="มัดจำ %" htmlFor="depositPercent">
                <div className="flex flex-wrap gap-2">
                  {DEPOSIT_PERCENT_PRESETS.map((pct) => (
                    <button
                      key={pct}
                      type="button"
                      aria-pressed={!terms.depositPercentCustom && Number(terms.depositPercent) === pct}
                      className={`min-h-[38px] rounded-md border px-3 text-xs font-bold ${!terms.depositPercentCustom && Number(terms.depositPercent) === pct ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface'}`}
                      onClick={() => { setTerms((t) => ({ ...t, depositPercent: pct, depositPercentCustom: false })); setDirty(true); }}
                    >
                      {pct}%
                    </button>
                  ))}
                  <button
                    type="button"
                    aria-pressed={terms.depositPercentCustom}
                    className={`min-h-[38px] rounded-md border px-3 text-xs font-bold ${terms.depositPercentCustom ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface'}`}
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
              <FormField label="ส่วนที่เหลือ" htmlFor="remainderMode">
                <div className="flex flex-wrap items-center gap-2">
                  {REMAINDER_MODE_OPTIONS.map((opt) => (
                    <button
                      key={opt.code}
                      type="button"
                      aria-pressed={terms.remainderMode === opt.code}
                      className={`min-h-[38px] rounded-md border px-3 text-xs font-bold ${terms.remainderMode === opt.code ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface'}`}
                      onClick={() => { setTerms((t) => ({ ...t, remainderMode: opt.code })); setDirty(true); }}
                    >
                      {opt.label}
                    </button>
                  ))}
                  {terms.remainderMode === 'CREDIT' ? (
                    <span className="inline-flex items-center gap-1.5 text-xs text-text-muted">
                      เครดิต
                      <input
                        id="remainderMode"
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
              <FormField label="ยืนราคา (วัน)" htmlFor="validityDays">
                <select id="validityDays" value={terms.validityDays} onChange={(e) => { setTerms((t) => ({ ...t, validityDays: e.target.value })); setDirty(true); }}>
                  <option value="">-</option>
                  {VALIDITY_DAYS_OPTIONS.map((d) => <option key={d} value={d}>{d} วัน</option>)}
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
            {quotation && !dirty ? (
              <div className="flex flex-col items-end gap-1">
                <span className="text-sm text-text-muted">รวมเป็นเงิน <span className="tabular-nums text-text">{formatMoney(quotation.subtotalAmount)}</span></span>
                <span className="text-sm text-text-muted">ภาษีมูลค่าเพิ่ม 7% <span className="tabular-nums text-text">{formatMoney(quotation.vatAmount)}</span></span>
                <span className="text-lg font-extrabold">ยอดรวมทั้งสิ้น <span className="tabular-nums">{formatMoney(quotation.grandTotal)}</span></span>
              </div>
            ) : (
              // #M6: computed client-side from `items` rather than a bare "ยังไม่บันทึก" — sales
              // gets a live running total while typing, explicitly labelled an estimate since the
              // authoritative rounding is server-side (WastageCalculator).
              <div className="flex flex-col items-end gap-1">
                <p className="m-0 text-xs font-bold text-warning">ยอดโดยประมาณ (ยังไม่บันทึก)</p>
                <span className="text-sm text-text-muted">รวมเป็นเงิน <span className="tabular-nums text-text">{formatMoney(provisionalSubtotal)}</span></span>
                <span className="text-sm text-text-muted">ภาษีมูลค่าเพิ่ม 7% <span className="tabular-nums text-text">{formatMoney(provisionalVat)}</span></span>
                <span className="text-lg font-extrabold">ยอดรวมทั้งสิ้น <span className="tabular-nums">{formatMoney(provisionalGrand)}</span></span>
              </div>
            )}
          </Panel>
        </>
      )}

      {submitConfirmOpen ? (
        <Modal
          title="ส่งขออนุมัติใบเสนอราคา"
          onClose={() => setSubmitConfirmOpen(false)}
          footer={(
            <>
              <Button variant="secondary" onClick={() => setSubmitConfirmOpen(false)}>ยกเลิก</Button>
              <Button variant="primary" loading={submitMutation.isPending} onClick={() => submitMutation.mutate()}>ส่งขออนุมัติ</Button>
            </>
          )}
        >
          <p>ส่งใบเสนอราคา {quotation?.number} ให้ผู้จัดการฝ่ายขายหรือผู้บริหารอนุมัติ ต้องการดำเนินการต่อหรือไม่</p>
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
            อนุมัติใบเสนอราคา {quotation?.number} ยอดรวมทั้งสิ้น {formatMoney(quotation?.grandTotal)} ต้องการดำเนินการต่อหรือไม่
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
