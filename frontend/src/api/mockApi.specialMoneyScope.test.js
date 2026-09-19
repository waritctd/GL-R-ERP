import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// Mock-plumbing coverage for the mock's specialMoney namespace, modelled on the backend's own
// SpecialMoneyScopeIntegrationTest (backend/src/test/java/th/co/glr/hr/specialmoney/
// SpecialMoneyScopeIntegrationTest.java, real Postgres, real SpecialMoneyService — read in full
// for this change). Per CLAUDE.md "Mock API contract" / "Permission changes must ship evidence",
// this file is NOT itself authz evidence — the Java test above is; this one only pins that the
// MOCK does not drift back to the pre-2026-08-10 model (a ฝ่าย manager could read/file for their
// division).
//
// What IS mirrored, section by section (comments on each describe() below cite the match):
//   1  list() division/self scoping         9  usage() quota scoping
//   2,5,6,7 approve()/reject() CEO-only     10 cancel() scoping
//   3  list() employee scoping              11 in-division confidentiality (the 2026-08-10 leak)
//   4  submit() on-behalf refusal              -- employees(), list(a)/(b), usage(c),
//                                                 attachments/download(d), submit-on-behalf,
//                                                 employeeOptions, self-still-works
//   `aLegacyOnBehalfFilerCanNoLongerCancelOrAttachToTheRow` (IT ~line 440) -- a row whose
//     requestedById differs from employeeId, exercised through the mock's own seed (see below),
//     not read out of the code -- the "legacy filer()" describe block.
//
// What is NOT mirrored, and why:
//   - Section 8 (ceoApprovesStraightFromSubmittedForEveryEmployee /
//     ceoCannotApproveAnEvidenceRequiredTypeWithNothingAttached / ceoCanStillClearALegacyManagerApprovedRow)
//     is authz-adjacent but not itself a scope/role-gate case for THIS slice (GLA-46 is the authz
//     divergence, not the evidence-requirement or legacy-status-transition logic) -- the evidence
//     gate already has its own coverage via specialMoneyUsage.test.js's approve() call, and the
//     MANAGER_APPROVED legacy-status branch is unchanged by this fix.
//   - `evidenceCanOnlyBeAttachedByTheRequesterAndOnlyBeforeADecision`'s "not after a decision" half
//     still isn't mirrored here: it needs a row already past a decision AND requestedById !==
//     employeeId at once, which is a second, more specific fixture than the one below buys.
//
// Fixture, all from frontend/src/data/demoData.js's fixed seed (not random):
//   - warehouse.manager@glr.co.th -> employees[5] (id 6), positionTh 'ผู้จัดการฝ่าย' for WHL, so
//     dashboardManager() is true for them via the org chart (not the login role, which is plainly
//     'employee' -- see demoData.js's own comment on why there is no 'supervisor' role). Their own
//     seed request is id 9 (AID_ORDINATION, APPROVED) -- used to prove the manager's list is
//     "own rows", not "empty", once the report's row is scoped out.
//   - role:'employee' login -> employees[8] (id 9), a WHL staff member whose managerId is
//     employees[5] (divAssign puts indices 5-11 in WHL, employees[5] is first-in-division). This
//     is the exact "salesStaff reports to salesManager" shape the Java test builds by hand.
//   - buildDemoSpecialMoneyRequests's request id 1 is already employeeId: employees[8].id,
//     status SUBMITTED, type MEDICAL, requestedById: employees[8].id (self-filed, no legacy
//     on-behalf row exists anywhere in the ORIGINAL seed) -- used directly rather than re-created.
//   - divAssign's status rule marks index 24 and 29 RSG (resigned/inactive): employee ids 25 and
//     30 (codes GLR-1025 / GLR-1030) are the only two inactive employees in the whole seed --
//     used to confirm employees() excludes them for hr/ceo too.
//   - GLA-46 added request id 13 (buildDemoSpecialMoneyRequests, demoHr.js): employeeId
//     employees[9] (id 10, WHL, same manager as employees[8] but NOT employees[8] itself),
//     requestedById employees[5].id (the warehouse manager, id 6) -- a row filed before the
//     2026-08-10 ruling, when a ฝ่าย manager could submit special-money on a report's behalf.
//     Mirrors the Java IT's parkAsLegacyOnBehalfRow without a raw-SQL seam: `db` is still not
//     exported, but the seed itself can now hold a row shaped exactly like that UPDATE produces.
//     Its owner (employees[9]) has no login of their own in demoData.js's original users list, so
//     GLA-46 also added one (`users` id 13, email employees[9].email, role 'employee', placed
//     LAST so it can never win `db.users.find` for `role: 'employee'` over employees[8]'s entry) --
//     see that file's comment on the new entry. `loginLegacyRowOwner()` below logs in as them.
//
// Ordering matters in this file: cancel() terminally mutates request id 1's status, so every
// read-only assertion against it runs BEFORE the cancel section, and the cancel section is last.
// approve()/reject() are asserted to reject before cancel() runs, so request id 1 is still
// SUBMITTED for them. The new "legacy filer()" describe block mutates request id 13 (cancel) and
// so likewise runs last, after the id-1 cancel section.

const REPORT_EMPLOYEE_ID = 9; // employees[8].id
const MANAGER_EMPLOYEE_ID = 6; // employees[5].id
const SEEDED_REQUEST_ID = 1; // employeeId 9, MEDICAL, SUBMITTED
const MANAGER_OWN_REQUEST_ID = 9; // employeeId 6 (the manager themselves), AID_ORDINATION, APPROVED
const INACTIVE_EMPLOYEE_IDS = [25, 30]; // divAssign index 24 & 29 -> statuses[2] (RSG)
const WIDE_WINDOW = { from: '2000-01-01', to: '2999-12-31' };

// GLA-46 legacy on-behalf fixture -- see the header comment and demoHr.js's row-13 comment.
const LEGACY_ROW_OWNER_EMPLOYEE_ID = 10; // employees[9].id
const LEGACY_ROW_OWNER_EMAIL = 'chutima.s@glr.co.th'; // employees[9].email, demoData.js users id 13
const LEGACY_ROW_ID = 13; // employeeId 10, AID_FUNERAL, SUBMITTED, requestedById 6 (the manager)

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
async function loginLegacyRowOwner() {
  return api.auth.login({ email: LEGACY_ROW_OWNER_EMAIL, password: 'demo1234' });
}

describe('mockApi specialMoney — confidential-to-self scoping (2026-08-10 ruling)', () => {
  it('fixture sanity: the report actually reports to the manager under test', async () => {
    const { user: report } = await loginReport();
    expect(report.employeeId).toBe(REPORT_EMPLOYEE_ID);
    const { user: manager } = await loginManager();
    expect(manager.employeeId).toBe(MANAGER_EMPLOYEE_ID);
    expect(manager.manager).toBe(true);
  });

  it('fixture sanity: the legacy row\'s owner can log in and is not the existing report fixture', async () => {
    const { user: owner } = await loginLegacyRowOwner();
    expect(owner.employeeId).toBe(LEGACY_ROW_OWNER_EMPLOYEE_ID);
    expect(owner.employeeId).not.toBe(REPORT_EMPLOYEE_ID);
  });

  // --- employees() picker: section "The submit form's employee picker" -------------------------
  describe('employees()', () => {
    it('a ฝ่าย manager is offered only themselves, not the division roster', async () => {
      await loginManager();
      const { employees } = await api.specialMoney.employees();

      expect(employees).toHaveLength(1);
      expect(employees[0]).toMatchObject({
        employeeId: MANAGER_EMPLOYEE_ID, self: true, directReport: false,
      });
    });

    it('a plain employee is offered only themselves', async () => {
      await loginReport();
      const { employees } = await api.specialMoney.employees();

      expect(employees).toHaveLength(1);
      expect(employees[0]).toMatchObject({
        employeeId: REPORT_EMPLOYEE_ID, self: true, directReport: false,
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

        // Mirrors the repository's `WHERE e.is_active = TRUE` -- resigned employees never appear,
        // even to hr/ceo's includeAll roster.
        INACTIVE_EMPLOYEE_IDS.forEach((inactiveId) => {
          expect(employees.some((option) => option.employeeId === inactiveId)).toBe(false);
        });
      }
    });
  });

  // --- usage(): section 9 + section 11(c) --------------------------------------------------------
  describe('usage()', () => {
    it('a ฝ่าย manager cannot read an in-division report\'s usage quota', async () => {
      await loginManager();
      await expect(api.specialMoney.usage({ employeeId: REPORT_EMPLOYEE_ID, year: 2026 }))
        .rejects.toThrow('ไม่มีสิทธิ์เข้าถึงรายการนี้');
    });

    it('the employee can read their own usage quota', async () => {
      await loginReport();
      const { usage } = await api.specialMoney.usage({ employeeId: REPORT_EMPLOYEE_ID, year: 2026 });
      expect(usage.employeeId).toBe(REPORT_EMPLOYEE_ID);
    });

    it('hr and ceo can read any employee\'s usage quota', async () => {
      for (const login of [loginHr, loginCeo]) {
        await login();
        const { usage } = await api.specialMoney.usage({ employeeId: REPORT_EMPLOYEE_ID, year: 2026 });
        expect(usage.employeeId).toBe(REPORT_EMPLOYEE_ID);
      }
    });
  });

  // --- list(): section 1 (division scoping) + section 11(a)/(b) (in-division leak) ---------------
  describe('list()', () => {
    it('a ฝ่าย manager\'s own list never contains an in-division report\'s request', async () => {
      await loginManager();
      const { requests } = await api.specialMoney.list(WIDE_WINDOW);

      // Own rows only -- not "empty". Without this positive assertion, a bug that scoped the
      // manager out of EVERYTHING (not just their report) would pass the `every()` check below
      // vacuously.
      expect(requests.some((request) => request.id === MANAGER_OWN_REQUEST_ID)).toBe(true);
      expect(requests.every((request) => request.employeeId === MANAGER_EMPLOYEE_ID)).toBe(true);
      expect(requests.some((request) => request.id === SEEDED_REQUEST_ID)).toBe(false);
    });

    it('a ฝ่าย manager asking for that employee explicitly gets a 403, not a filtered-empty 200', async () => {
      await loginManager();
      await expect(api.specialMoney.list({ ...WIDE_WINDOW, employeeId: REPORT_EMPLOYEE_ID }))
        .rejects.toThrow('ไม่มีสิทธิ์เข้าถึงรายการนี้');
    });

    it('the employee still sees their own request', async () => {
      await loginReport();
      const { requests } = await api.specialMoney.list(WIDE_WINDOW);
      expect(requests.some((request) => request.id === SEEDED_REQUEST_ID)).toBe(true);
    });

    it('hr and ceo still see every employee\'s request', async () => {
      for (const login of [loginHr, loginCeo]) {
        await login();
        const { requests } = await api.specialMoney.list(WIDE_WINDOW);
        expect(requests.some((request) => request.id === SEEDED_REQUEST_ID)).toBe(true);
      }
    });
  });

  // --- create(): section 4 + section 11 (submit-on-behalf is gone, no hr/ceo bypass) -------------
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

    it('hr has no on-behalf bypass either', async () => {
      const before = await countForReport();
      await loginHr();

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

  // --- attachments()/addAttachment(): section 11(d) + evidenceCanOnlyBeAttachedByTheRequester ----
  describe('attachments() / addAttachment()', () => {
    it('a ฝ่าย manager cannot list an in-division report\'s evidence', async () => {
      await loginManager();
      await expect(api.specialMoney.attachments(SEEDED_REQUEST_ID))
        .rejects.toThrow('ไม่มีสิทธิ์เข้าถึงรายการนี้');
    });

    it('a ฝ่าย manager cannot attach evidence to an in-division report\'s request', async () => {
      await loginManager();
      await expect(api.specialMoney.addAttachment(SEEDED_REQUEST_ID, { name: 'x.pdf', type: 'application/pdf', size: 10 }))
        .rejects.toThrow('เฉพาะผู้ยื่นคำขอเท่านั้นที่แนบเอกสารได้');
    });

    it('hr and ceo can list the evidence (empty is a valid, authorized read)', async () => {
      for (const login of [loginHr, loginCeo]) {
        await login();
        await expect(api.specialMoney.attachments(SEEDED_REQUEST_ID)).resolves.toBeDefined();
      }
    });

    it('the employee can attach evidence to their own request', async () => {
      await loginReport();
      const { attachment } = await api.specialMoney.addAttachment(
        SEEDED_REQUEST_ID, { name: 'mor-ana-bat.pdf', type: 'application/pdf', size: 1024 });
      expect(attachment.specialMoneyRequestId).toBe(SEEDED_REQUEST_ID);

      const { attachments } = await api.specialMoney.attachments(SEEDED_REQUEST_ID);
      expect(attachments.some((item) => item.id === attachment.id)).toBe(true);
    });
  });

  // --- approve()/reject(): sections 2, 5, 6, 7 (CEO-only role gate, not a scope gate) -------------
  // Must run before the cancel() section below, while request id 1 is still SUBMITTED.
  describe('approve() / reject() — CEO only, no manager/hr/self exception', () => {
    it('a ฝ่าย manager cannot approve, not even their own in-division report\'s request', async () => {
      await loginManager();
      await expect(api.specialMoney.approve(SEEDED_REQUEST_ID, {})).rejects.toThrow('CEO');
    });

    it('hr cannot approve (issue #199 shape)', async () => {
      await loginHr();
      await expect(api.specialMoney.approve(SEEDED_REQUEST_ID, {})).rejects.toThrow('CEO');
    });

    it('the employee cannot approve their own request', async () => {
      await loginReport();
      await expect(api.specialMoney.approve(SEEDED_REQUEST_ID, {})).rejects.toThrow('CEO');
    });

    it('a ฝ่าย manager cannot reject either', async () => {
      await loginManager();
      await expect(api.specialMoney.reject(SEEDED_REQUEST_ID, {})).rejects.toThrow('CEO');
    });

    it('hr cannot reject either', async () => {
      await loginHr();
      await expect(api.specialMoney.reject(SEEDED_REQUEST_ID, {})).rejects.toThrow('CEO');
    });

    it('the request is untouched by every rejected attempt above', async () => {
      await loginHr();
      const { requests } = await api.specialMoney.list({ ...WIDE_WINDOW, employeeId: REPORT_EMPLOYEE_ID });
      const seeded = requests.find((request) => request.id === SEEDED_REQUEST_ID);
      expect(seeded.status).toBe('SUBMITTED');
      expect(seeded.approvedAmount).toBeNull();
    });
  });

  // --- cancel(): section 10 + section 11 (must run LAST -- terminally mutates request id 1) ------
  describe('cancel() (runs last: mutates request id 1 to CANCELLED)', () => {
    it('a ฝ่าย manager cannot cancel an in-division report\'s request', async () => {
      await loginManager();
      await expect(api.specialMoney.cancel(SEEDED_REQUEST_ID, {}))
        .rejects.toThrow('ไม่มีสิทธิ์เข้าถึงรายการนี้');

      // ...and the row is still there, so this is scoping and not an accidental cancel.
      await loginHr();
      const { requests } = await api.specialMoney.list({ ...WIDE_WINDOW, employeeId: REPORT_EMPLOYEE_ID });
      expect(requests.find((request) => request.id === SEEDED_REQUEST_ID).status).toBe('SUBMITTED');
    });

    it('the employee can cancel their own request', async () => {
      await loginReport();
      const { request } = await api.specialMoney.cancel(SEEDED_REQUEST_ID, {});
      expect(request.status).toBe('CANCELLED');
    });
  });

  // --- legacy on-behalf filer (row 13): mirrors the Java IT's
  // aLegacyOnBehalfFilerCanNoLongerCancelOrAttachToTheRow, exercised through the seed instead of a
  // raw-SQL UPDATE (see the header comment). Must run LAST -- the positive-control case
  // terminally mutates request id 13 to CANCELLED, same reason the id-1 cancel() block above is
  // last in its own section.
  describe('legacy on-behalf filer (row 13, requestedById !== employeeId)', () => {
    it('the recorded filer cannot see the row in their own list()', async () => {
      await loginManager();
      const { requests } = await api.specialMoney.list(WIDE_WINDOW);
      expect(requests.some((request) => request.id === LEGACY_ROW_ID)).toBe(false);
    });

    it('the recorded filer cannot read the row\'s attachments', async () => {
      await loginManager();
      await expect(api.specialMoney.attachments(LEGACY_ROW_ID))
        .rejects.toThrow('ไม่มีสิทธิ์เข้าถึงรายการนี้');
    });

    it('the recorded filer cannot attach evidence to the row -- no requestedById disjunct left', async () => {
      await loginManager();
      await expect(api.specialMoney.addAttachment(LEGACY_ROW_ID, { name: 'x.pdf', type: 'application/pdf', size: 10 }))
        .rejects.toThrow('เฉพาะผู้ยื่นคำขอเท่านั้นที่แนบเอกสารได้');
    });

    it('the recorded filer cannot cancel the row -- no requestedById disjunct left', async () => {
      await loginManager();
      await expect(api.specialMoney.cancel(LEGACY_ROW_ID, {}))
        .rejects.toThrow('ไม่มีสิทธิ์เข้าถึงรายการนี้');
    });

    // View-all is a READ grant only. hr/ceo can list this row, but cancel/attach are the owner's
    // alone (SpecialMoneyService.cancel()/requireCanAttach() have no view-all branch).
    it('hr and ceo, who can read the row, still cannot attach evidence to it or cancel it', async () => {
      for (const login of [loginHr, loginCeo]) {
        await login();
        await expect(api.specialMoney.addAttachment(LEGACY_ROW_ID, { name: 'x.pdf', type: 'application/pdf', size: 10 }))
          .rejects.toThrow('เฉพาะผู้ยื่นคำขอเท่านั้นที่แนบเอกสารได้');
        await expect(api.specialMoney.cancel(LEGACY_ROW_ID, {}))
          .rejects.toThrow('ไม่มีสิทธิ์เข้าถึงรายการนี้');
      }
    });

    it('the row is untouched by every refusal above', async () => {
      await loginHr();
      const { requests } = await api.specialMoney.list({ ...WIDE_WINDOW, employeeId: LEGACY_ROW_OWNER_EMPLOYEE_ID });
      const row = requests.find((request) => request.id === LEGACY_ROW_ID);
      expect(row.status).toBe('SUBMITTED');
    });

    it('positive control: the owning employee can still attach to and then cancel their own legacy row', async () => {
      await loginLegacyRowOwner();
      const { attachment } = await api.specialMoney.addAttachment(
        LEGACY_ROW_ID, { name: 'mor-kor-1.pdf', type: 'application/pdf', size: 2048 });
      expect(attachment.specialMoneyRequestId).toBe(LEGACY_ROW_ID);

      const { attachments } = await api.specialMoney.attachments(LEGACY_ROW_ID);
      expect(attachments.some((item) => item.id === attachment.id)).toBe(true);

      const { request } = await api.specialMoney.cancel(LEGACY_ROW_ID, {});
      expect(request.status).toBe('CANCELLED');
    });
  });
});
