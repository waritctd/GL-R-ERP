import { describe, expect, it } from 'vitest';
import { canPerformFinanceStep } from './financeActionPerformers.js';

// Who the SERVER lets perform each nextAccountAction step (read from the Java, see the helper's comment).
// Wrong way round first: every role that is not on the server's list must get false.
const ROLES = ['account', 'ceo', 'sales_manager', 'sales', 'import', 'hr', 'employee', 'warehouse', 'qc', undefined, null];

describe('canPerformFinanceStep — action × role, mirrored from the Java services', () => {
  it.each([
    ['confirmDeposit', ['account']],
    ['confirmFinalPayment', ['account']],
    ['confirmCloseReady', ['account']],
    ['recordInvoiceCommission', ['account']],
    ['chaseOverdue', []],
  ])('%s is performable only by %j', (key, allowed) => {
    ROLES.forEach((role) => {
      expect([key, role, canPerformFinanceStep(key, role)]).toEqual([key, role, allowed.includes(role)]);
    });
  });

  it('the CEO (who reaches /finance) can perform none of them', () => {
    ['confirmDeposit', 'confirmFinalPayment', 'confirmCloseReady', 'recordInvoiceCommission', 'chaseOverdue']
      .forEach((key) => expect(canPerformFinanceStep(key, 'ceo')).toBe(false));
  });
});
