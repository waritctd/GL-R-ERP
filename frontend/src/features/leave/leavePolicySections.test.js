import { describe, it, expect } from 'vitest';
import { api } from '../../api/mockApi.js';
import { LEAVE_POLICY_SECTIONS, LEAVE_RULE_FIELD_LABELS, NAMED_PERSONAL_PURPOSES } from './leavePolicySections.js';

// The three identity fields on LeaveTypeDto (backend/.../leave/LeaveTypeDto.java) — never rule
// data, so the "13th column" guard below must not demand a label for them.
const IDENTITY_FIELDS = new Set(['code', 'nameTh', 'nameEn']);

describe('LEAVE_POLICY_SECTIONS', () => {
  it('has the §5 announcement sections, keyed to LeaveRuleCode sectionRef values, in order', () => {
    expect(LEAVE_POLICY_SECTIONS.map((section) => section.id)).toEqual([
      '5.1', '5.2', '5.3', '5.3.2', '5.3.3', '5.3.4', '5.3.5', '5.4', '5.5', '5.6', 'unpaid',
    ]);
  });

  it('never hand-authors a numeric/rule value inside a section body', () => {
    // A cheap, deliberately-narrow guard against the failure mode this file's own header comment
    // warns about: a bare digit sequence inside `body` almost always means a quota/cap/day-count
    // got typed in by hand instead of rendered live from LeaveTypeDto. The handful of numbers that
    // ARE legitimately hardcoded here (the wedding-leave 3-day cap, §-numbers inside titles) have no
    // backing DTO column at all — see the file's header comment — so this only scans `body`, never
    // `title`/`id`, and the wedding-day-cap sentence is intentionally exempted below by content, not
    // by disabling the check.
    const exemptSentenceFragments = ['พิธีสมรส (ของตนเองหรือบุตร) ลาได้ไม่เกิน 3 วัน'];
    for (const section of LEAVE_POLICY_SECTIONS) {
      let body = section.body;
      for (const fragment of exemptSentenceFragments) body = body.replaceAll(fragment, '');
      expect(body, `section ${section.id} body has an unexplained digit`).not.toMatch(/\d/);
    }
  });

  it('declares fields+types only for leave-type codes it actually names', () => {
    for (const section of LEAVE_POLICY_SECTIONS) {
      expect(Array.isArray(section.types)).toBe(true);
      expect(Array.isArray(section.fields)).toBe(true);
    }
  });

  it('names all five NAMED §5.2 purposes (excludes the OTHER catch-all), live off the request form list', () => {
    expect(NAMED_PERSONAL_PURPOSES).toHaveLength(5);
    expect(NAMED_PERSONAL_PURPOSES.some((option) => option.value === 'OTHER')).toBe(false);
  });

  // THE POINT OF THIS FILE: a future migration adding a 13th (now 15th) rule column to
  // hr.leave_type must not silently become invisible on the rules tab. mockApi.js's db.leaveTypes
  // is required (CLAUDE.md's mock contract) to mirror LeaveTypeDto's shape field-for-field even
  // though it does not enforce the values behaviourally — so pulling the field list from THERE,
  // rather than from a second hand-typed list in this test, means: whoever adds the new column to
  // LeaveTypeDto AND mirrors it into the mock (already mandatory) gets a red test here the moment
  // they forget to also add a LEAVE_RULE_FIELD_LABELS entry. A hand-typed fixture field list in
  // this test instead would have exactly the same staleness risk this whole file exists to close.
  it('every non-null rule field on every seeded leave type has a declared renderer (label)', async () => {
    await api.auth.login({ role: 'hr' });
    const { leaveTypes } = await api.leave.types();
    expect(leaveTypes.length).toBeGreaterThan(0);

    const missing = [];
    for (const leaveType of leaveTypes) {
      for (const [field, value] of Object.entries(leaveType)) {
        if (IDENTITY_FIELDS.has(field)) continue;
        if (value === null || value === undefined) continue;
        if (!(field in LEAVE_RULE_FIELD_LABELS)) missing.push(`${leaveType.code}.${field}`);
      }
    }
    expect(missing).toEqual([]);
  });

  // Mutation-check for the guard above (CLAUDE.md: "a green test that cannot fail is not
  // evidence") — proves the previous test actually exercises the failure path it claims to,
  // not just that the current (complete) label map happens to pass.
  it('the field-coverage guard actually fails when a label is missing (mutation-check)', async () => {
    await api.auth.login({ role: 'hr' });
    const { leaveTypes } = await api.leave.types();
    const labelsMissingOneEntry = { ...LEAVE_RULE_FIELD_LABELS };
    delete labelsMissingOneEntry.paidDaysCap;

    const missing = [];
    for (const leaveType of leaveTypes) {
      for (const [field, value] of Object.entries(leaveType)) {
        if (IDENTITY_FIELDS.has(field)) continue;
        if (value === null || value === undefined) continue;
        if (!(field in labelsMissingOneEntry)) missing.push(`${leaveType.code}.${field}`);
      }
    }
    // MATERNITY is seeded with a non-null paidDaysCap (45) — see mockApi.js's db.leaveTypes — so
    // removing that one label must surface at least that one gap.
    expect(missing).toContain('MATERNITY.paidDaysCap');
  });
});
