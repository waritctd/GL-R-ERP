import React from 'react';
import { describe, expect, it, vi } from 'vitest';
import { remainingBadge, remainingHint } from './documentChainText.js';

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
});

