/* Hallmark · genre: modern-minimal · macrostructure: Workbench · design-system: design.md · designed-as-app · pre-emit critique: P4 H4 E4 S4 R4 V4 */
import { useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button, buttonVariants } from '../../components/common/Button.jsx';
import { FormField } from '../../components/common/FormField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { cn } from '../../utils/cn.js';
import { dealStageLabel } from '../../utils/format.js';
import {
  canCreateDealQuotation, dealQuotationStatusLabel, hasLivePricingRequest, isLiveDirectQuotation,
  LIVE_PRICING_REQUEST_BLOCK_MESSAGE,
} from './quotationMeta.js';

/**
 * One pricing route per deal (owner ruling 2026-09-30): does the deal `ticketId` hold a LIVE pricing
 * request (quotationMeta.hasLivePricingRequest — any status but CANCELLED / SUPERSEDED)? Reads the
 * deal's own requests through the existing api.pricingRequests.listForTicket (the same read the deal
 * page makes; ticket list rows carry no pricing-request info, and one deal's list is exact where the
 * rep-wide queue would be N rows to filter). Shared by DealPicker's notice and the editor's checklist
 * under one query key, so it is one request. retry:false — a role that cannot read pricing requests
 * (403) reads as "none known", and DealQuotationService#create's own 409 is the backstop.
 */
export function useDealHasLivePricingRequest(ticketId, enabled = true) {
  const query = useQuery({
    queryKey: queryKeys.pricingRequestsByTicket(ticketId),
    queryFn: () => api.pricingRequests.listForTicket(ticketId).then((r) => r?.items ?? []),
    enabled: enabled && ticketId != null,
    retry: false,
  });
  return hasLivePricingRequest(query.data ?? [], ticketId);
}

const LIST_ID = 'deal-picker-list';

function optionId(deal) {
  return `deal-option-${deal.id}`;
}

function matches(deal, needle) {
  if (!needle) return true;
  return [deal.code, deal.customerName, deal.projectName]
    .some((value) => value && String(value).toLowerCase().includes(needle));
}

/** One deal's identity, used by both the option rows and the picked chip so the two always read
 * the same: code (mono) · customer (bold) · project (muted), with the stage as a Thai badge. */
function DealIdentity({ deal }) {
  const stage = dealStageLabel(deal.salesStage);
  return (
    <span className="flex min-w-0 flex-1 flex-col gap-0.5">
      {/* Wraps (never collides) on a narrow phone: the badge drops under the code rather than
          overlapping whatever sits beside this identity block. */}
      <span className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
        <code className="shrink-0 font-mono text-xs text-text-muted">{deal.code ?? `#${deal.id}`}</code>
        <StatusBadge tone={stage.tone}>{stage.label}</StatusBadge>
      </span>
      <strong className="min-w-0 truncate text-sm font-bold text-text" title={deal.customerName ?? undefined}>
        {deal.customerName ?? '-'}
      </strong>
      {deal.projectName ? (
        <span className="min-w-0 truncate text-xs text-text-muted" title={deal.projectName}>{deal.projectName}</span>
      ) : null}
    </span>
  );
}

/**
 * "เลือกดีลที่มีอยู่" — slice 2, flow A (SLICE-2-FLOW-A.md §A). One combobox over the rows
 * api.tickets.list() already returns (server-scoped: a sales rep only ever receives their own
 * deals), narrowed here to the ACTIVE deals this user may start a quotation on
 * (canCreateDealQuotation — the same gate the editor itself uses) and filtered client-side on
 * code · customer · project. No per-keystroke request: the list is already in memory, and shares
 * its cache entry (queryKeys.ticketList('')) with every overview that reads the same list.
 *
 * Picking a deal sets `?ticket=<id>` and nothing else — the editor's EXISTING ?ticket= path takes
 * over from there (deal summary, customer record, recipient preselect), so there is no second code
 * path for "a quotation on a picked deal". The keyboard contract is the โครงการ typeahead's in
 * DealCustomerCard (ARIA 1.2 combobox: the highlighted row is an index, focus stays on the input).
 *
 * `selected` is the editor's own ticket summary for the current `?ticket=`. When it carries a live
 * direct quotation (TicketSummaryDto.liveDirectQuotation, S2-B4), an inline notice says so — never
 * a modal (DESIGN.md §16) — with the way forward: open that quotation, or (APPROVED) revise it.
 */
export function DealPicker({ user, selected = null, error, showToast }) {
  const [, setSearchParams] = useSearchParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const [search, setSearch] = useState('');
  const [open, setOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState(-1);
  const blurTimer = useRef(null);
  useEffect(() => () => clearTimeout(blurTimer.current), []);

  const dealsQuery = useQuery({
    queryKey: queryKeys.ticketList(''),
    queryFn: () => api.tickets.list({}).then((response) => response?.tickets ?? []),
    // The picked deal renders from `selected`; the list is only needed while searching.
    enabled: !selected,
  });

  const eligible = useMemo(
    () => (dealsQuery.data ?? []).filter((deal) => (deal.lifecycle ?? 'ACTIVE') === 'ACTIVE' && canCreateDealQuotation(user, deal)),
    [dealsQuery.data, user],
  );
  const needle = search.trim().toLowerCase();
  const filtered = useMemo(() => eligible.filter((deal) => matches(deal, needle)), [eligible, needle]);

  function closeList() {
    setOpen(false);
    setActiveIndex(-1);
  }

  function pick(deal) {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      next.set('ticket', String(deal.id));
      return next;
    }, { replace: true });
    setSearch('');
    closeList();
  }

  function clearPick() {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      next.delete('ticket');
      return next;
    }, { replace: true });
  }

  function handleKeyDown(event) {
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      if (!open) { setOpen(true); return; }
      if (filtered.length) setActiveIndex((i) => (i + 1) % filtered.length);
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      if (!open) { setOpen(true); return; }
      if (filtered.length) setActiveIndex((i) => (i <= 0 ? filtered.length - 1 : i - 1));
    } else if (event.key === 'Enter') {
      if (!open || activeIndex < 0 || !filtered[activeIndex]) return;
      event.preventDefault();
      pick(filtered[activeIndex]);
    } else if (event.key === 'Escape' && open) {
      event.preventDefault();
      closeList();
    }
  }

  const live = selected && isLiveDirectQuotation(selected.liveDirectQuotation) ? selected.liveDirectQuotation : null;
  const livePricingRequest = useDealHasLivePricingRequest(selected?.id ?? null, Boolean(selected));
  const canRevise = live?.docStatus === 'APPROVED' && canCreateDealQuotation(user, selected);
  const reviseMutation = useMutation({
    mutationFn: () => api.dealQuotations.createRevision(live.id, {}),
    onSuccess: (res) => {
      queryClient.invalidateQueries({ queryKey: ['dealQuotations'] });
      showToast?.('success', 'สร้างฉบับแก้ไขแล้ว');
      navigate(`/quotations/${res.quotation.id}`);
    },
    onError: (err) => showToast?.('error', err.message || 'สร้างฉบับแก้ไขไม่สำเร็จ'),
  });

  if (selected) {
    return (
      <div className="grid gap-2">
        <span className="text-sm font-bold text-text-secondary">ดีล</span>
        <div className="flex items-center gap-3 rounded-md border border-border-muted bg-surface-muted px-3 py-2">
          <DealIdentity deal={selected} />
          <Button variant="text" className="shrink-0 px-1 text-sm mobile:min-h-[44px]" onClick={clearPick}>
            เปลี่ยนดีล
          </Button>
        </div>
        {live ? (
          <div
            role="status"
            data-testid="deal-picker-live-quotation"
            className="flex items-start gap-2 rounded-md border border-info-border bg-info-bg px-3 py-2.5 text-sm text-info-dark"
          >
            <Icon name="info" size={16} className="mt-0.5 shrink-0" />
            <div className="flex min-w-0 flex-1 flex-col gap-2">
              <div className="flex flex-col gap-0.5">
                <strong className="break-words">
                  {`ดีลนี้มีใบเสนอราคาตรงที่ใช้งานอยู่ — ${live.number} · ${dealQuotationStatusLabel(live.docStatus).label}`}
                </strong>
                <span className="text-xs">
                  {live.docStatus === 'APPROVED'
                    ? 'สร้างใบเสนอราคาใหม่บนดีลนี้ไม่ได้ — สร้างฉบับแก้ไขจากฉบับที่อนุมัติแล้วแทน เลขที่จะต่อจากเดิม'
                    : 'สร้างใบเสนอราคาใหม่บนดีลนี้ไม่ได้ — แก้ไขฉบับนั้นแทน'}
                </span>
              </div>
              <div className="flex flex-wrap gap-2">
                <Link to={`/quotations/${live.id}`} className={cn(buttonVariants({ variant: 'secondary', size: 'sm' }), 'no-underline mobile:min-h-[44px]')}>
                  เปิดใบเสนอราคา
                </Link>
                {canRevise ? (
                  <Button
                    variant="secondary"
                    size="sm"
                    className="mobile:min-h-[44px]"
                    loading={reviseMutation.isPending}
                    onClick={() => reviseMutation.mutate()}
                  >
                    สร้างฉบับแก้ไข
                  </Button>
                ) : null}
              </div>
            </div>
          </div>
        ) : null}
        {livePricingRequest ? (
          // One pricing route per deal (owner ruling 2026-09-30) — the same inline-notice pattern as
          // the live direct quotation above: never a modal (DESIGN.md §16), and the editor disables
          // บันทึกร่าง with this exact sentence (DESIGN.md §14).
          <div
            role="status"
            data-testid="deal-picker-live-pricing-request"
            className="flex items-start gap-2 rounded-md border border-info-border bg-info-bg px-3 py-2.5 text-sm text-info-dark"
          >
            <Icon name="info" size={16} className="mt-0.5 shrink-0" />
            <strong className="min-w-0 break-words">{LIVE_PRICING_REQUEST_BLOCK_MESSAGE}</strong>
          </div>
        ) : null}
      </div>
    );
  }

  const activeOption = open && activeIndex >= 0 ? filtered[activeIndex] : null;

  return (
    <div className="relative">
      <FormField
        label="ดีล"
        htmlFor="deal-picker"
        required
        error={error}
        // A list this role cannot read (e.g. a quotation grant-holder with no deal pipeline access)
        // is said up front, beside the field, with the way round it — not discovered on focus.
        hint={dealsQuery.isError ? 'โหลดรายการดีลไม่ได้ — เลือก “สร้างดีลใหม่” แทน' : undefined}
      >
        <input
          id="deal-picker"
          role="combobox"
          autoComplete="off"
          aria-expanded={open}
          aria-controls={LIST_ID}
          aria-autocomplete="list"
          aria-activedescendant={activeOption ? optionId(activeOption) : undefined}
          placeholder="ค้นหา รหัสดีล / ลูกค้า / โครงการ…"
          value={search}
          onChange={(event) => { setSearch(event.target.value); setActiveIndex(-1); setOpen(true); }}
          onFocus={() => setOpen(true)}
          onBlur={() => { blurTimer.current = setTimeout(closeList, 150); }}
          onKeyDown={handleKeyDown}
        />
      </FormField>
      {open ? (
        <ul
          id={LIST_ID}
          role="listbox"
          aria-label="ผลการค้นหาดีล"
          // Same popup voice as the ลูกค้า/โครงการ typeaheads beside it. `max-h` in viewport units so
          // the list never runs off a short phone screen; `w-full` of the field keeps it inside the
          // 16px page gutter at 320px.
          className="absolute z-10 mt-1 max-h-[min(20rem,60vh)] w-full list-none overflow-auto rounded-md border border-border bg-surface pl-0 shadow-[var(--shadow-lg-heavy)]"
        >
          {dealsQuery.isLoading ? (
            <li role="presentation" className="px-3 py-2 text-xs text-text-muted">กำลังโหลด…</li>
          ) : dealsQuery.isError ? null : eligible.length === 0 ? (
            <li role="presentation" className="px-3 py-2 text-xs text-text-muted">ยังไม่มีดีลที่สร้างใบเสนอราคาได้ — เลือก “สร้างดีลใหม่” แทน</li>
          ) : filtered.length === 0 ? (
            <li role="presentation" className="px-3 py-2 text-xs text-text-muted">ไม่พบดีลที่ตรงกับคำค้น</li>
          ) : null}
          {filtered.map((deal, index) => (
            // The OPTION is the button, not the <li> — same reasoning as DealCustomerCard's own
            // listboxes: a listbox child must be an option, never a wrapper around a control.
            <li key={deal.id} role="presentation" className="border-b border-border-subtle last:border-b-0">
              <button
                type="button"
                id={optionId(deal)}
                role="option"
                aria-selected={index === activeIndex}
                // Explicit flat row (surface, no border, no radius): a bare <button> otherwise picks
                // up the legacy global button chrome, which drew every option as a grey card.
                className={cn(
                  'flex min-h-11 w-full items-start gap-2 rounded-none border-0 bg-surface px-3 py-2 text-left font-normal hover:bg-surface-hover',
                  index === activeIndex && 'bg-surface-hover',
                )}
                onMouseEnter={() => setActiveIndex(index)}
                onMouseDown={(event) => { event.preventDefault(); pick(deal); }}
              >
                <DealIdentity deal={deal} />
              </button>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}
