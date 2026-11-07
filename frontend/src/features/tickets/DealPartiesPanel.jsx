/* Hallmark · genre: modern-minimal · macrostructure: Workbench · design-system: design.md · designed-as-app
 * Tokens only. "ผู้รับเหมาที่เสนอราคา" (docs/decisions/deal-party-model.md, prototype screen 2): one
 * row per bidding contractor (BUYER party) of the deal, each with its own quotation chain.
 */
import { useEffect, useRef, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button, buttonVariants } from '../../components/common/Button.jsx';
import { FormField } from '../../components/common/FormField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { OverflowMenu } from '../../components/common/OverflowMenu.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { cn } from '../../utils/cn.js';
import { formatMoney } from '../../utils/format.js';
import { dealQuotationStatusLabel } from '../quotations/quotationMeta.js';
import { buyerParties, isBiddingParty, partyStatusLabel } from './dealPartyMeta.js';

// A party that is out of the bid takes no new price.
const CLOSED_PARTY_STATUSES = ['NOT_AWARDED', 'WITHDRAWN', 'AWARDED_SPLIT'];

function quoteLink(ticketId, party) {
  if (party.latestQuotationId != null) return { to: `/quotations/${party.latestQuotationId}`, label: 'แก้ไขราคา' };
  return { to: `/quotations/new?ticket=${ticketId}&party=${party.id}`, label: 'เสนอราคา' };
}

function PartyQuotation({ party }) {
  if (party.latestQuotationId == null) return <span className="text-xs text-text-muted">ยังไม่มีใบเสนอราคา</span>;
  const status = dealQuotationStatusLabel(party.latestQuotationStatus);
  return (
    <span className="flex min-w-0 flex-col items-start gap-1">
      <Link to={`/quotations/${party.latestQuotationId}`} className="text-xs text-info underline">
        <code>{party.latestQuotationNumber}</code>
      </Link>
      <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
    </span>
  );
}

function PartyAmount({ party }) {
  return (
    <span className="flex flex-col items-end gap-0.5">
      <span className="tabular-nums text-sm font-bold text-text">
        {party.latestQuotationAmount != null ? formatMoney(party.latestQuotationAmount) : '—'}
      </span>
      {party.headline ? <span className="text-2xs font-bold text-warning-dark">★ ยอดหลัก</span> : null}
    </span>
  );
}

function PartyOutcome({ party }) {
  const chip = partyStatusLabel(party.status, party.notAwardedReason);
  return (
    <span className="flex flex-col items-start gap-1">
      <StatusBadge tone={chip.tone}>{chip.label}</StatusBadge>
      {party.splitTicketId ? (
        <Link to={`/tickets/${party.splitTicketId}`} className="text-2xs text-info underline">
          {`ดีล ${party.splitTicketCode ?? `#${party.splitTicketId}`}`}
        </Link>
      ) : null}
    </span>
  );
}

/** แก้ไขราคา / เสนอราคา, plus the ⋯ of pre-outcome edits (headline, negotiating, remove). */
function PartyActions({ ticketId, party, canEdit, onUpdate, onRemove, busy }) {
  if (!canEdit || CLOSED_PARTY_STATUSES.includes(party.status)) return null;
  const link = quoteLink(ticketId, party);
  const menu = [
    isBiddingParty(party) && !party.headline && {
      key: 'headline', label: '★ ตั้งเป็นยอดหลัก', disabled: busy, onSelect: () => onUpdate(party, { headline: true }, 'ตั้งเป็นยอดหลักแล้ว'),
    },
    party.status === 'QUOTED' && {
      key: 'negotiating', label: 'กำลังต่อรอง', disabled: busy, onSelect: () => onUpdate(party, { status: 'NEGOTIATING' }, 'บันทึกว่ากำลังต่อรองแล้ว'),
    },
    party.status === 'NEGOTIATING' && {
      key: 'quoted', label: 'กลับเป็นเสนอราคาแล้ว', disabled: busy, onSelect: () => onUpdate(party, { status: 'QUOTED' }, 'บันทึกสถานะแล้ว'),
    },
    party.status === 'ACTIVE' && party.latestQuotationId == null && {
      key: 'remove', label: 'นำออกจากดีล', disabled: busy, onSelect: () => onRemove(party),
    },
  ].filter(Boolean);
  return (
    <span className="flex items-center justify-end gap-1">
      {party.status !== 'AWARDED' ? (
        <Link
          to={link.to}
          className={cn(buttonVariants({ variant: 'secondary', size: 'sm' }), 'no-underline mobile:min-h-[44px]')}
          data-testid={`party-quote-${party.id}`}
        >
          {link.label}
        </Link>
      ) : null}
      {menu.length > 0 ? <OverflowMenu items={menu} label={`การดำเนินการของ ${party.customerName ?? ''}`} /> : null}
    </span>
  );
}

/**
 * Customer typeahead (api.customers.search) + an optional contact of that customer, then addParty.
 * A 409 (the customer is already a contractor on this deal) stays in the dialog, in the server's words.
 */
function AddPartyModal({ ticketId, onClose, showToast }) {
  const queryClient = useQueryClient();
  const [search, setSearch] = useState('');
  const [results, setResults] = useState([]);
  const [searching, setSearching] = useState(false);
  const [customer, setCustomer] = useState(null);
  const [contacts, setContacts] = useState([]);
  const [contactId, setContactId] = useState('');
  const [error, setError] = useState(null);
  const timer = useRef(null);
  useEffect(() => () => clearTimeout(timer.current), []);

  useEffect(() => {
    if (!customer) { setContacts([]); return undefined; }
    let cancelled = false;
    api.customers.contacts(customer.id)
      .then((res) => { if (!cancelled) setContacts(res?.contacts ?? []); })
      .catch(() => { if (!cancelled) setContacts([]); });
    return () => { cancelled = true; };
  }, [customer]);

  function onSearch(value) {
    setSearch(value);
    clearTimeout(timer.current);
    timer.current = setTimeout(async () => {
      setSearching(true);
      try {
        const res = await api.customers.search(value);
        setResults(res?.customers ?? []);
      } catch {
        setResults([]);
      } finally {
        setSearching(false);
      }
    }, 250);
  }

  const addMutation = useMutation({
    mutationFn: () => api.tickets.addParty(ticketId, {
      customerId: customer.id, role: 'BUYER', ...(contactId ? { contactId: Number(contactId) } : {}),
    }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.ticketParties(ticketId) });
      queryClient.invalidateQueries({ queryKey: queryKeys.ticketDetail(ticketId) });
      showToast?.('success', 'เพิ่มผู้รับเหมาแล้ว');
      onClose();
    },
    onError: (err) => setError(err.message || 'เพิ่มผู้รับเหมาไม่สำเร็จ'),
  });

  return (
    <Modal
      title="เพิ่มผู้รับเหมา"
      subtitle="ผู้รับเหมาที่เสนอราคาโครงการนี้ แต่ละรายมีใบเสนอราคาของตัวเอง"
      onClose={onClose}
      testId="add-party-modal"
      footer={(
        <>
          <Button variant="secondary" onClick={onClose} disabled={addMutation.isPending}>ยกเลิก</Button>
          <Button variant="primary" onClick={() => { setError(null); addMutation.mutate(); }} disabled={!customer} loading={addMutation.isPending}>
            เพิ่มผู้รับเหมา
          </Button>
        </>
      )}
    >
      <div className="grid gap-4">
        <div className="relative">
          <FormField label="ผู้รับเหมา" htmlFor="add-party-customer" required>
            {customer ? (
              <div className="flex items-center gap-2 rounded-md border border-border-muted bg-surface-muted px-2.5 py-1.5 text-sm">
                <strong className="flex-1">{customer.name}</strong>
                <Button variant="text" className="text-sm" onClick={() => { setCustomer(null); setContactId(''); }}>เปลี่ยน</Button>
              </div>
            ) : (
              <input
                id="add-party-customer"
                autoComplete="off"
                placeholder="พิมพ์ค้นหาชื่อบริษัท / ลูกค้า…"
                value={search}
                onChange={(e) => onSearch(e.target.value)}
              />
            )}
          </FormField>
          {!customer && search.trim() ? (
            <ul role="listbox" aria-label="ผลการค้นหาลูกค้า" className="m-0 mt-1 max-h-56 list-none overflow-auto rounded-md border border-border bg-surface p-0">
              {searching ? <li role="presentation" className="px-3 py-2 text-xs text-text-muted">กำลังค้นหา…</li> : null}
              {!searching && results.length === 0 ? <li role="presentation" className="px-3 py-2 text-xs text-text-muted">ไม่พบข้อมูล</li> : null}
              {results.map((c) => (
                <li key={c.id} role="presentation">
                  <button
                    type="button"
                    role="option"
                    aria-selected={false}
                    className="block min-h-11 w-full border-0 bg-surface px-3 py-2 text-left text-sm text-text hover:bg-surface-hover"
                    onClick={() => { setCustomer(c); setResults([]); }}
                  >
                    {c.name}
                  </button>
                </li>
              ))}
            </ul>
          ) : null}
        </div>
        <FormField label="ผู้ติดต่อ" htmlFor="add-party-contact" hint={customer ? undefined : 'เลือกผู้รับเหมาก่อน'}>
          <select id="add-party-contact" value={contactId} disabled={!customer} onChange={(e) => setContactId(e.target.value)}>
            <option value="">ไม่ระบุ</option>
            {contacts.map((c) => (
              <option key={c.id} value={c.id}>{`${c.firstName ?? ''} ${c.lastName ?? ''}`.trim()}</option>
            ))}
          </select>
        </FormField>
        {error ? <p role="alert" className="m-0 text-sm font-bold text-danger">{error}</p> : null}
      </div>
    </Modal>
  );
}

/**
 * "ผู้รับเหมาที่เสนอราคา" — the deal's BUYER parties. Visible to anyone who can view the deal; the
 * add / price / ⋯ controls only to its editors (`canEdit`: owner or keyer sales, sales_manager, ceo,
 * the gate DealPartyService re-checks).
 */
export function DealPartiesPanel({ ticketId, parties = [], loading = false, canEdit = false, showToast }) {
  const queryClient = useQueryClient();
  const [adding, setAdding] = useState(false);
  const buyers = buyerParties(parties);

  function refresh() {
    queryClient.invalidateQueries({ queryKey: queryKeys.ticketParties(ticketId) });
    queryClient.invalidateQueries({ queryKey: ['dealQuotations'] });
  }
  const updateMutation = useMutation({
    mutationFn: ({ party, patch }) => api.tickets.updateParty(ticketId, party.id, patch),
    onSuccess: (_res, { message }) => { refresh(); showToast?.('success', message); },
    onError: (err) => showToast?.('error', err.message || 'บันทึกไม่สำเร็จ'),
  });
  const removeMutation = useMutation({
    mutationFn: (party) => api.tickets.removeParty(ticketId, party.id),
    onSuccess: () => { refresh(); showToast?.('success', 'นำผู้รับเหมาออกจากดีลแล้ว'); },
    onError: (err) => showToast?.('error', err.message || 'นำออกไม่สำเร็จ'),
  });
  const busy = updateMutation.isPending || removeMutation.isPending;
  const onUpdate = (party, patch, message) => updateMutation.mutate({ party, patch, message });
  const onRemove = (party) => removeMutation.mutate(party);

  return (
    <Panel
      flush
      data-testid="deal-parties-panel"
      title={(
        <span className="flex items-start gap-2">
          <Icon name="building" size={18} className="mt-1 shrink-0 text-text-muted" />
          <span className="flex flex-col">
            ผู้รับเหมาที่เสนอราคา
            <span className="text-2xs font-normal text-text-muted">ผู้ซื้อ · หนึ่งแถวต่อผู้รับเหมา ใบเสนอราคาแยกกัน นับใน pipeline ครั้งเดียว</span>
          </span>
        </span>
      )}
    >
      {loading ? (
        <p className="m-0 px-5 py-4 text-sm text-text-muted">กำลังโหลด…</p>
      ) : buyers.length === 0 ? (
        <p className="m-0 px-5 py-4 text-sm text-text-muted">ยังไม่มีผู้รับเหมาในดีลนี้</p>
      ) : (
        <>
          {/* Desktop: a compact table. */}
          <table className="w-full border-collapse text-sm mobile:hidden">
            <thead>
              <tr className="border-b border-border-subtle text-left text-2xs font-bold uppercase tracking-wide text-text-muted">
                <th className="px-5 py-2.5 font-bold">ผู้รับเหมา</th>
                <th className="px-3 py-2.5 font-bold">ใบเสนอราคา</th>
                <th className="px-3 py-2.5 text-right font-bold">ยอดรวม</th>
                <th className="px-3 py-2.5 font-bold">ผล</th>
                <th className="px-5 py-2.5"><span className="sr-only">การทำงาน</span></th>
              </tr>
            </thead>
            <tbody>
              {buyers.map((party) => (
                <tr key={party.id} data-testid={`party-row-${party.id}`} className={cn('border-b border-border-subtle last:border-b-0 align-top', party.status === 'NOT_AWARDED' && 'opacity-60')}>
                  <td className="px-5 py-3">
                    <strong className="block text-text">{party.customerName ?? '-'}</strong>
                    {party.contactName ? <span className="text-xs text-text-muted">{party.contactName}</span> : null}
                  </td>
                  <td className="px-3 py-3"><PartyQuotation party={party} /></td>
                  <td className="px-3 py-3 text-right"><PartyAmount party={party} /></td>
                  <td className="px-3 py-3"><PartyOutcome party={party} /></td>
                  <td className="px-5 py-3 text-right">
                    <PartyActions ticketId={ticketId} party={party} canEdit={canEdit} onUpdate={onUpdate} onRemove={onRemove} busy={busy} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {/* Phone (≤720px): one stacked card per contractor. */}
          <ul className="m-0 hidden list-none flex-col gap-0 p-0 mobile:flex">
            {buyers.map((party) => (
              <li key={party.id} className={cn('flex flex-col gap-2 border-b border-border-subtle px-4 py-3 last:border-b-0', party.status === 'NOT_AWARDED' && 'opacity-60')}>
                <div className="flex items-start justify-between gap-3">
                  <span className="min-w-0">
                    <strong className="block text-sm text-text">{party.customerName ?? '-'}</strong>
                    {party.contactName ? <span className="text-xs text-text-muted">{party.contactName}</span> : null}
                  </span>
                  <PartyAmount party={party} />
                </div>
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <PartyQuotation party={party} />
                  <PartyOutcome party={party} />
                </div>
                <PartyActions ticketId={ticketId} party={party} canEdit={canEdit} onUpdate={onUpdate} onRemove={onRemove} busy={busy} />
              </li>
            ))}
          </ul>
        </>
      )}
      {canEdit ? (
        <div className="border-t border-border-subtle px-5 py-3 mobile:px-4">
          <Button variant="secondary" size="sm" className="mobile:min-h-[44px]" onClick={() => setAdding(true)} data-testid="add-party">
            <Icon name="plus" size={14} />
            เพิ่มผู้รับเหมา
          </Button>
        </div>
      ) : null}
      {adding ? <AddPartyModal ticketId={ticketId} onClose={() => setAdding(false)} showToast={showToast} /> : null}
    </Panel>
  );
}
