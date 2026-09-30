import { describe, expect, it } from 'vitest';
import { orderActions, pickPrimaryAction } from './financeDealActions.js';

const a = (action, extra = {}) => ({ action, label: action, targetStage: null, requiredFields: [], ...extra });

describe('financeDealActions', () => {
  it('orders money actions DEPOSIT_PAID > FINAL_PAYMENT > RECORD_PAYMENT > CONFIRM_CLOSE > SET_BILLING > stage actions', () => {
    const shuffled = [
      a('UPDATE_STAGE'), a('SET_BILLING'), a('ADVANCE_STAGE', { targetStage: 'CLOSED_PAID' }),
      a('CONFIRM_CLOSE'), a('RECORD_PAYMENT'), a('FINAL_PAYMENT'), a('REVOKE_CLOSE_CONFIRM'), a('DEPOSIT_PAID'),
    ];
    expect(orderActions(shuffled).map((x) => x.action)).toEqual([
      'DEPOSIT_PAID', 'FINAL_PAYMENT', 'RECORD_PAYMENT', 'CONFIRM_CLOSE', 'SET_BILLING',
      'ADVANCE_STAGE', 'UPDATE_STAGE', 'REVOKE_CLOSE_CONFIRM',
    ]);
  });

  it('the primary action is the first ordered one, and null when there are none', () => {
    expect(pickPrimaryAction([a('SET_BILLING'), a('FINAL_PAYMENT')])?.action).toBe('FINAL_PAYMENT');
    expect(pickPrimaryAction([])).toBeNull();
    // Reversing a close confirmation is never the headline action — it lives in the overflow menu.
    expect(pickPrimaryAction([a('REVOKE_CLOSE_CONFIRM')])).toBeNull();
    expect(pickPrimaryAction(undefined)).toBeNull();
  });

  it('an unrecognised action sorts last rather than being dropped', () => {
    expect(orderActions([a('MYSTERY'), a('SET_BILLING')]).map((x) => x.action)).toEqual(['SET_BILLING', 'MYSTERY']);
  });
});
