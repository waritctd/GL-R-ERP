import { describe, expect, it } from 'vitest';
import { orderActions } from './financeDealActions.js';

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
});
