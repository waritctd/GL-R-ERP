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
//   8  ceoCannotApproveAnEvidenceRequiredTypeWithNothingAttached -- mirrored directly below, in
//     "approve() -- requireEvidence gate". CORRECTION: this comment used to claim
//     specialMoneyUsage.test.js's approve() call already covered this, which was false --
//     that file's approve() call is on a request with an attachment already in place (it exists to
//     pin the payrollMonth-keyed usage accounting, not the evidence gate), so disabling the check
//     in mockApi.js's specialMoney.approve() (~line 8803) left BOTH files green. See the new
//     describe block for the real coverage, plus a cap-override-reason guard
//     (SpecialMoneyService.ceoApproveFrom's ~line 272-278) that had no mock equivalent at all.
//   -- `ceoApprovesStraightFromSubmittedForEveryEmployee` / `ceoCanStillClearALegacyManagerApprovedRow`
//     remain not mirrored here: not a scope/role-gate case for THIS slice (GLA-46 is the authz
//     divergence being pinned, not the plain-approve happy path or the legacy-status transition).
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
//
// The requireEvidence-gate and cap-override-reason describe blocks (added alongside this comment)
// each create their OWN fresh row via specialMoney.create() rather than reusing SEEDED_REQUEST_ID
// or LEGACY_ROW_ID, specifically so they can sit anywhere in this file without caring about the
// ordering above. The employees()-sort test similarly creates its own employee via
// api.employees.create() (an ordinary HR onboarding call, unrelated to specialMoney) rather than
// touching the seed; it only needs to run after the other three employees() tests, which it does.

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

    // The three tests above assert `codes` equals `[...codes].sort()`, but demoData.js's seed
    // builds `code: GLR-${1001 + index}` directly from array index, so `db.employees` is ALREADY
    // in code order before specialMoney.employees() ever runs -- that assertion could never go red,
    // .sort() call or not. This test manufactures a genuine out-of-order pair instead of touching
    // the seed: employees.create() (the ordinary HR onboarding endpoint) assigns the new hire the
    // HIGHEST code so far (createEmployeeRecord: `code: GLR-${1000 + id}`, id = max existing + 1)
    // but UNSHIFTS it to the FRONT of db.employees -- so the raw array now holds its highest-coded
    // row first. Mutation-check: delete the `.sort((a, b) => pgAsc(a.code, b.code))` call in
    // specialMoney.employees() and this test goes red (the other three do not, which is exactly the
    // gap this closes).
    it('is genuinely sorted by employeeCode -- not merely already-ordered in the seed', async () => {
      await loginHr();
      const { employee: created } = await api.employees.create({
        nameTh: 'ทดสอบ ลำดับรหัส',
        email: 'sort-order-check@glr.co.th',
      });

      const { employees } = await api.specialMoney.employees();
      const createdOption = employees.find((option) => option.employeeId === created.id);
      expect(createdOption).toBeDefined();
      // The new hire has the HIGHEST code of anyone in the roster, so a correctly sorted result
      // must place it LAST -- not first, which is where db.employees now literally has it.
      expect(employees[employees.length - 1].employeeId).toBe(created.id);

      const codes = employees.map((option) => option.employeeCode);
      expect(codes).toEqual([...codes].sort());
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

  // --- approve(): requireEvidence gate (mirrors the IT's
  // ceoCannotApproveAnEvidenceRequiredTypeWithNothingAttached, SpecialMoneyScopeIntegrationTest
  // ~line 221 -- see the header comment's correction). Each test uses a FRESH row created here,
  // rather than any shared fixture id, so this block can run anywhere relative to the ordering
  // notes above without disturbing them.
  describe('approve() — requireEvidence gate', () => {
    it('the CEO cannot approve an evidence-required type with nothing attached, and the row stays SUBMITTED', async () => {
      await loginReport();
      const { request: created } = await api.specialMoney.create({
        requestType: 'AID_FUNERAL',
        eventDate: '2026-05-20',
        requestedAmount: 5000,
        reason: 'เงินช่วยเหลืองานศพบิดา (evidence-gate test)',
      });

      await loginCeo();
      // Asserted verbatim against SpecialMoneyService.requireEvidence's message:
      //   "คำขอประเภท " + type.thaiLabel() + " ต้องแนบเอกสารหลักฐานก่อนจึงจะอนุมัติได้"
      await expect(api.specialMoney.approve(created.id, {}))
        .rejects.toThrow('คำขอประเภท เงินช่วยเหลืองานศพ ต้องแนบเอกสารหลักฐานก่อนจึงจะอนุมัติได้');

      await loginHr();
      const { requests } = await api.specialMoney.list({ ...WIDE_WINDOW, employeeId: REPORT_EMPLOYEE_ID });
      const row = requests.find((request) => request.id === created.id);
      expect(row.status).toBe('SUBMITTED');
      expect(row.approvedAmount).toBeNull();
    });

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

  // --- approve(): cap-override-reason guard (mirrors SpecialMoneyService.ceoApproveFrom's
  // ~line 272-278 `approvedAmount > recheck.eligibleAmount()` check; see mockApi.js's specialMoney
  // header comment for exactly how -- and where -- the mock's `requestedAmount` approximation of
  // `eligibleAmount` diverges from Java). TRAVEL_PER_DIEM is the one type with evidenceRequired:
  // false, so these rows reach the cap check without also needing an attachment. Each test uses
  // its own fresh row: the guard mutates the row to APPROVED on success, and a shared row would
  // let one test's approval hide the next test's guard check entirely.
  describe('approve() — cap-override-reason guard', () => {
    // One day as a domestic driver: Java's eligibleAmount is rate_driver (฿400, V66) x 1 day, so
    // requesting exactly ฿400 keeps every case below one Java would decide the same way. (A ฿500
    // request would be over the cap, and Java would refuse even "approve exactly what was asked".)
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

    it('refuses a whitespace-only reason the same way -- blank is not a reason', async () => {
      const created = await freshTravelRequest();
      await loginCeo();
      await expect(api.specialMoney.approve(created.id, { approvedAmount: 600, capOverrideReason: '   ' }))
        .rejects.toThrow('ต้องระบุเหตุผลเมื่อจำนวนเงินที่อนุมัติเกินเพดานตามนโยบายหรือเกินจำนวนที่พนักงานขอเบิก');
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

    it('approving exactly the requested amount (here also the per-diem cap) needs no reason', async () => {
      const created = await freshTravelRequest();
      await loginCeo();
      const { request: approved } = await api.specialMoney.approve(created.id, { approvedAmount: 400 });
      expect(approved.status).toBe('APPROVED');
      expect(approved.approvedAmount).toBe(400);
      expect(approved.capOverrideReason).toBeNull();
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
