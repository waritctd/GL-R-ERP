import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// Mock mirror of the backend's SpecialMoneyScopeIntegrationTest (2026-08-10 ruling: special money is
// confidential to the employee; a ฝ่าย manager no longer reads or files for their division; approve is
// CEO-only). The Java test is the authz evidence; this guards the mock against drifting back.
// Fixture ids come from demoData.js: warehouse.manager (id 6) manages the `employee` login (id 9).
const REPORT_EMPLOYEE_ID = 9; // employees[8].id
const MANAGER_EMPLOYEE_ID = 6; // employees[5].id
const SEEDED_REQUEST_ID = 1; // employeeId 9, MEDICAL, SUBMITTED
const MANAGER_OWN_REQUEST_ID = 9; // employeeId 6 (the manager themselves), AID_ORDINATION, APPROVED
const INACTIVE_EMPLOYEE_IDS = [25, 30]; // divAssign index 24 & 29 -> statuses[2] (RSG)
const WIDE_WINDOW = { from: '2000-01-01', to: '2999-12-31' };

async function loginManager() {
  return api.auth.login({ email: 'warehouse.manager@glr.co.th', password: 'demo1234' });
}
async function loginReport() {
  return api.auth.login({ role: 'employee' });
}
async function loginHr() {
  return api.auth.login({ role: 'hr' });
}
async function loginCeo() {
  return api.auth.login({ role: 'ceo' });
}

describe('mockApi specialMoney — confidential-to-self scoping (2026-08-10 ruling)', () => {
  describe('employees()', () => {
    it('a ฝ่าย manager is offered only themselves, not the division roster', async () => {
      await loginManager();
      const { employees } = await api.specialMoney.employees();

      expect(employees).toHaveLength(1);
      expect(employees[0]).toMatchObject({
        employeeId: MANAGER_EMPLOYEE_ID, self: true, directReport: false,
      });
    });

    it('hr and ceo get the full active roster, ordered by employeeCode, excluding inactive employees, directReport always false', async () => {
      for (const login of [loginHr, loginCeo]) {
        await login();
        const { employees } = await api.specialMoney.employees();

        expect(employees.length).toBeGreaterThan(1);
        expect(employees.some((option) => option.employeeId === REPORT_EMPLOYEE_ID)).toBe(true);
        employees.forEach((option) => expect(option.directReport).toBe(false));

        // Mirrors findEmployeeOptions' `ORDER BY e.employee_code`.
        const codes = employees.map((option) => option.employeeCode);
        expect(codes).toEqual([...codes].sort());

        // Mirrors the repository's `WHERE e.is_active = TRUE` -- resigned employees never appear, even to hr/ceo's
        // includeAll roster.
        INACTIVE_EMPLOYEE_IDS.forEach((inactiveId) => {
          expect(employees.some((option) => option.employeeId === inactiveId)).toBe(false);
        });
      }
    });

  });

  describe('usage()', () => {
    it('a ฝ่าย manager cannot read an in-division report\'s usage quota', async () => {
      await loginManager();
      await expect(api.specialMoney.usage({ employeeId: REPORT_EMPLOYEE_ID, year: 2026 }))
        .rejects.toThrow('ไม่มีสิทธิ์เข้าถึงรายการนี้');
    });

    it('hr and ceo can read any employee\'s usage quota', async () => {
      for (const login of [loginHr, loginCeo]) {
        await login();
        const { usage } = await api.specialMoney.usage({ employeeId: REPORT_EMPLOYEE_ID, year: 2026 });
        expect(usage.employeeId).toBe(REPORT_EMPLOYEE_ID);
      }
    });
  });

  describe('list()', () => {
    it('a ฝ่าย manager\'s own list never contains an in-division report\'s request', async () => {
      await loginManager();
      const { requests } = await api.specialMoney.list(WIDE_WINDOW);

      // Own rows only -- not "empty". Without this positive assertion, a bug that scoped the manager out of
      // EVERYTHING (not just their report) would pass the `every()` check below vacuously.
      expect(requests.some((request) => request.id === MANAGER_OWN_REQUEST_ID)).toBe(true);
      expect(requests.every((request) => request.employeeId === MANAGER_EMPLOYEE_ID)).toBe(true);
      expect(requests.some((request) => request.id === SEEDED_REQUEST_ID)).toBe(false);
    });

    it('hr and ceo still see every employee\'s request', async () => {
      for (const login of [loginHr, loginCeo]) {
        await login();
        const { requests } = await api.specialMoney.list(WIDE_WINDOW);
        expect(requests.some((request) => request.id === SEEDED_REQUEST_ID)).toBe(true);
      }
    });
  });

  describe('create() — no submit-on-behalf for anyone', () => {
    async function countForReport() {
      await loginHr();
      const { requests } = await api.specialMoney.list({ ...WIDE_WINDOW, employeeId: REPORT_EMPLOYEE_ID });
      return requests.length;
    }

    it('a ฝ่าย manager cannot submit on behalf of an in-division report', async () => {
      const before = await countForReport();
      await loginManager();

      await expect(api.specialMoney.create({
        employeeId: REPORT_EMPLOYEE_ID,
        requestType: 'MEDICAL',
        eventDate: '2026-08-01',
        requestedAmount: 500,
        reason: 'on-behalf attempt',
      })).rejects.toThrow('ตนเอง');

      expect(await countForReport()).toBe(before);
    });

    it('the employee can still file for themselves', async () => {
      await loginReport();
      const { request } = await api.specialMoney.create({
        requestType: 'MEDICAL',
        eventDate: '2026-08-01',
        requestedAmount: 500,
        reason: 'self-filed',
      });
      expect(request.employeeId).toBe(REPORT_EMPLOYEE_ID);
    });
  });

  describe('approve() / reject() — CEO only, no manager/hr/self exception', () => {
    it('a ฝ่าย manager cannot approve, not even their own in-division report\'s request', async () => {
      await loginManager();
      await expect(api.specialMoney.approve(SEEDED_REQUEST_ID, {})).rejects.toThrow('CEO');
    });
  });

  describe('approve() — requireEvidence gate', () => {
    it('goes through once evidence exists, so the gate is about evidence and not a blanket refusal', async () => {
      await loginReport();
      const { request: created } = await api.specialMoney.create({
        requestType: 'AID_FUNERAL',
        eventDate: '2026-05-21',
        requestedAmount: 5000,
        reason: 'เงินช่วยเหลืองานศพบิดา (evidence-gate positive control)',
      });
      await api.specialMoney.addAttachment(
        created.id, { name: 'mor-ana-bat.pdf', type: 'application/pdf', size: 1024 });

      await loginCeo();
      const { request: approved } = await api.specialMoney.approve(created.id, {});
      expect(approved.status).toBe('APPROVED');
    });
  });

  describe('approve() — cap-override-reason guard', () => {
    // One day as a domestic driver: Java's eligibleAmount is rate_driver (฿400, V66) x 1 day, so requesting
    // exactly ฿400 keeps every case below one Java would decide the same way. (A ฿500 request would be over the
    // cap, and Java would refuse even "approve exactly what was asked".)
    async function freshTravelRequest(requestedAmount = 400) {
      await loginReport();
      const { request } = await api.specialMoney.create({
        requestType: 'TRAVEL_PER_DIEM',
        eventDate: '2026-05-22',
        requestedAmount,
        reason: 'เบี้ยเลี้ยงเดินทาง (cap-override-reason test)',
        detail: { destination: 'DOMESTIC', province: 'เชียงใหม่', role: 'driver' },
      });
      return request;
    }

    it('refuses to approve above the requested amount with no reason given', async () => {
      const created = await freshTravelRequest();
      await loginCeo();
      await expect(api.specialMoney.approve(created.id, { approvedAmount: 600 }))
        .rejects.toThrow('ต้องระบุเหตุผลเมื่อจำนวนเงินที่อนุมัติเกินเพดานตามนโยบายหรือเกินจำนวนที่พนักงานขอเบิก');

      await loginHr();
      const { requests } = await api.specialMoney.list({ ...WIDE_WINDOW, employeeId: REPORT_EMPLOYEE_ID });
      const row = requests.find((request) => request.id === created.id);
      expect(row.status).toBe('SUBMITTED');
      expect(row.approvedAmount).toBeNull();
    });

    it('approves above the requested amount when a reason is given, and stores it trimmed', async () => {
      const created = await freshTravelRequest();
      await loginCeo();
      const { request: approved } = await api.specialMoney.approve(created.id, {
        approvedAmount: 600,
        capOverrideReason: '  ผู้บริหารอนุมัติเพิ่มเติมเป็นกรณีพิเศษ  ',
      });
      expect(approved.status).toBe('APPROVED');
      expect(approved.approvedAmount).toBe(600);
      // Mirrors Java's blankToNull: stored TRIMMED, not the raw payload string.
      expect(approved.capOverrideReason).toBe('ผู้บริหารอนุมัติเพิ่มเติมเป็นกรณีพิเศษ');
    });
  });
});
