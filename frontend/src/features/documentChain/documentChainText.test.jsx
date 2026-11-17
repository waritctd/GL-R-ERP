import React from 'react';
import { describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider, focusManager } from '@tanstack/react-query';
import { act, render, waitFor } from '@testing-library/react';
import { chainNextStep, remainingBadge, remainingHint } from './documentChainText.js';
import { useDocumentChain } from './documentChain.js';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return { ...actual, api: { documentChain: { get: vi.fn() } } };
});

const chain = (remaining, viewer = {}) => ({
  order: { confirmed: true }, deposit: { state: 'PAID' }, remaining,
  viewer: { mayConfirmOrder: true, mayWriteDepositNotice: true, mayWriteRemainingInvoice: true, ...viewer },
});

describe('documentChainText — the words both pages print', () => {
  // Fix round 1, F-4: an unconfirmed no-deposit deal has no deposit to wait for.
  it('a remaining invoice blocked on the ORDER never says "รอรับมัดจำก่อน"', () => {
    expect(remainingBadge(chain({ state: 'TO_CREATE', blockedReason: 'ORDER_NOT_CONFIRMED' })).label).toBe('ยังไม่ถึงขั้นตอน');
    expect(remainingBadge(chain({ state: 'WAITING', blockedReason: 'ORDER_NOT_CONFIRMED' })).label).toBe('ยังไม่ถึงขั้นตอน');
    expect(remainingBadge(chain({ state: 'WAITING', blockedReason: 'DEPOSIT_NOT_RECEIVED' })).label).toBe('รอรับมัดจำก่อน');
    expect(remainingHint(chain({ state: 'TO_CREATE', blockedReason: 'ORDER_NOT_CONFIRMED' }))).toBe('ยืนยันคำสั่งซื้อก่อน');
  });

  // Surviving mutation F18: the goods wait is named as such, on both pages.
  it('goods not yet in: the next step waits on the goods, in these words', () => {
    const next = chainNextStep(chain({ state: 'TO_CREATE', blockedReason: 'GOODS_NOT_READY' }));
    expect(next).toEqual({
      index: 3, waitingOn: 'goods', viewerCanAct: false,
      line: 'ขั้นถัดไป: รอสินค้าถึงขั้นรับ/ส่งมอบ แล้วออกใบแจ้งหนี้ส่วนที่เหลือ',
    });
    expect(remainingHint(chain({ state: 'TO_CREATE', blockedReason: 'GOODS_NOT_READY' }))).toBe('ออกได้เมื่อสินค้าถึงขั้นรับ/ส่งมอบแล้ว');
  });
});

// Surviving mutations F5 / F6: the brief's freshness requirement — a page left open re-reads the chain when the window
// regains focus (or the tab becomes visible), with no polling. Both "focus refetch off" and "staleTime Infinity" fail
// here.
describe('useDocumentChain — refetch on focus', () => {
  it('re-reads the summary when the window regains focus', async () => {
    api.documentChain.get.mockResolvedValue({ documentChain: { ticketId: 18 } });
    function Reader() {
      useDocumentChain(18);
      return null;
    }
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={queryClient}><Reader /></QueryClientProvider>);
    await waitFor(() => expect(api.documentChain.get).toHaveBeenCalledTimes(1));
    act(() => { focusManager.setFocused(false); });
    act(() => { focusManager.setFocused(true); });
    await waitFor(() => expect(api.documentChain.get).toHaveBeenCalledTimes(2));
    act(() => { focusManager.setFocused(undefined); });
  });
});
