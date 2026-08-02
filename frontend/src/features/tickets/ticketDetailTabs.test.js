import { describe, expect, it } from 'vitest';
import {
  DEFAULT_TICKET_DETAIL_TAB_ID, resolveTicketDetailTab, TICKET_DETAIL_TABS, visibleTicketDetailTabIds,
} from './ticketDetailTabs.js';

describe('visibleTicketDetailTabIds', () => {
  it('sales (deal owner) sees every tab', () => {
    expect(visibleTicketDetailTabIds('sales')).toEqual([
      'deal', 'items', 'documents', 'money', 'fulfilment', 'history',
    ]);
  });

  it('ceo and sales_manager see every tab (oversight roles — unconditional pass-through)', () => {
    expect(visibleTicketDetailTabIds('ceo')).toEqual(visibleTicketDetailTabIds('sales'));
    expect(visibleTicketDetailTabIds('sales_manager')).toEqual(visibleTicketDetailTabIds('sales'));
  });

  // Slice D ("the เอกสาร document register") widens `documents` to
  // `() => true` — account now sees the TAB (it is the intended,
  // owner-authorised widening: account confirms money against the
  // deposit-notice/attachment rows the register now lists) even though it
  // still cannot reach a single quotation ROW inside it — see
  // DealDocumentRegister's own per-row gates and
  // TicketDetailPage.test.jsx's content-level proof.
  it('account sees every one of the 6 tabs (documents included, since Slice D)', () => {
    const tabs = visibleTicketDetailTabIds('account');
    expect(tabs).toEqual(['deal', 'items', 'documents', 'money', 'fulfilment', 'history']);
  });

  // ledger_importCannotReadThePaymentLedger / depositNotice_import...Refused —
  // import still loses money. It now sees the `documents` TAB too (Slice D's
  // `() => true` widening), but — per the KNOWN GAP this file's own header
  // still documents — reaches zero quotation rows inside it, and (per
  // DepositNoticeService#requireTicketViewer) zero deposit-notice/
  // remaining-invoice rows either; only an attachments roll-up appears, and
  // only once import is this deal's assignee. See
  // DealDocumentRegister.test.jsx for that content-level proof.
  it('import loses money but now sees documents too (Slice D widening) — keeps deal/items/documents/fulfilment/history', () => {
    const tabs = visibleTicketDetailTabIds('import');
    expect(tabs).toEqual(['deal', 'items', 'documents', 'fulfilment', 'history']);
  });

  // deliveries_hrCannotReadDeliveries / overview_hrCannotReadTheTicket — hr
  // never actually reaches this page (route-gated) but the function must not
  // leak a section-gated tab to an unknown role if it somehow did. `history`
  // and (since Slice D) `documents` join `deal`/`items` as role-unconditional
  // tabs — hr/employee never reach this component in practice (the ticket
  // fetch itself 403s them upstream), so this only pins that the FUNCTION
  // itself degrades safely if it somehow were called for one.
  it('an unknown/unreachable role (hr, employee) only gets the four ungated tabs', () => {
    expect(visibleTicketDetailTabIds('hr')).toEqual(['deal', 'items', 'documents', 'history']);
    expect(visibleTicketDetailTabIds('employee')).toEqual(['deal', 'items', 'documents', 'history']);
  });

  it('every tab id in the visibility list is a real TICKET_DETAIL_TABS id, in the table\'s declared order', () => {
    const allIds = TICKET_DETAIL_TABS.map((t) => t.id);
    for (const role of ['sales', 'ceo', 'sales_manager', 'import', 'account']) {
      const visible = visibleTicketDetailTabIds(role);
      expect(visible.every((id) => allIds.includes(id))).toBe(true);
      expect(visible).toEqual(allIds.filter((id) => visible.includes(id)));
    }
  });
});

describe('TICKET_DETAIL_TABS display copy', () => {
  it('uses concise Thai helper labels for compact tabs', () => {
    expect(TICKET_DETAIL_TABS.map(({ helper }) => helper)).toEqual([
      'ข้อมูลดีล',
      'รายการและราคา',
      // Slice D: was "เอกสารลูกค้า" (customer documents) — the tab now also
      // rolls up the deposit notice, remaining invoice, and internal
      // attachments (PO/other), not just customer-facing quotations.
      'เอกสารของดีล',
      'ยอดชำระ',
      'นำเข้าและจัดส่ง',
      'กิจกรรมและไฟล์แนบ',
    ]);
  });
});

describe('resolveTicketDetailTab', () => {
  it('keeps a tab id the role may see', () => {
    expect(resolveTicketDetailTab('items', 'sales')).toBe('items');
    expect(resolveTicketDetailTab('money', 'account')).toBe('money');
    // "history" is role-unconditional (the follow-up-feed sub-gate moved
    // inside the tab), so account keeps it too.
    expect(resolveTicketDetailTab('history', 'account')).toBe('history');
  });

  it('falls back to deal for a tab the role may NOT see', () => {
    expect(resolveTicketDetailTab('money', 'import')).toBe(DEFAULT_TICKET_DETAIL_TAB_ID);
  });

  it('keeps "documents" for account and import too — Slice D made the tab role-unconditional', () => {
    expect(resolveTicketDetailTab('documents', 'account')).toBe('documents');
    expect(resolveTicketDetailTab('documents', 'import')).toBe('documents');
  });

  it('falls back to deal for an absent or unknown tab id', () => {
    expect(resolveTicketDetailTab(null, 'sales')).toBe(DEFAULT_TICKET_DETAIL_TAB_ID);
    expect(resolveTicketDetailTab(undefined, 'sales')).toBe(DEFAULT_TICKET_DETAIL_TAB_ID);
    expect(resolveTicketDetailTab('not-a-real-tab', 'sales')).toBe(DEFAULT_TICKET_DETAIL_TAB_ID);
  });
});
