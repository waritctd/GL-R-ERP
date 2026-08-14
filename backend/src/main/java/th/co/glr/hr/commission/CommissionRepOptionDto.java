package th.co.glr.hr.commission;

/**
 * One selectable option in the manual-commission rep picker (issue #737) — an employee's id plus
 * a display name only, deliberately NOT the {@code hr.employee} record: no salary, no contact
 * details, no national ID, no division/position.
 *
 * <p><b>Who is enumerated: ฝ่ายขาย, for BOTH roles</b> — per an explicit owner ruling (2026-08-14,
 * the second on this endpoint; it superseded a brief per-caller-division-scoped design in between).
 * Ploy's own framing: {@code sales_manager} and {@code ceo} should see "every sales who get their
 * commission" — i.e. the picker lists people who actually earn commission, not merely whichever
 * ids happen to already have an invoice on file. Confirmed explicitly as division MEMBERSHIP, not
 * "has a commission record": a brand-new rep, or a sales manager never yet paid one, still appears.
 * {@code ceo} and {@code sales_manager} get the IDENTICAL list — no per-role narrowing left. See
 * {@link CommissionService#listManualCommissionRepOptions} for the one call this now is, and
 * {@link CommissionRepository#findActiveSalesRepOptions(String)} for how ฝ่ายขาย is identified
 * (via {@code DivisionAccessPolicy.SALES_DIVISION_CODE}, never a re-typed literal).
 *
 * <p>This is still narrower than the write path: {@code createManualCommission} accepts any
 * {@code hr.employee} id, because the {@code sales_rep_id} foreign key is its only real
 * restriction — someone outside ฝ่ายขาย (e.g. an inactive account, or a genuinely different
 * division) can still be paid a manual entry by id, just not enumerated by name here. That is a
 * deliberate, accepted gap, not an oversight, and not this DTO's problem to close: it is why the
 * numeric Employee-ID field on {@code ManualCommissionForm} stays the authoritative input for
 * anyone the picker excludes — a narrower list is a visible, explained limit (the form's label
 * says so) rather than issue #737's original SILENT one.
 *
 * <p><b>Deliberately two fields, not three.</b> An earlier version of this DTO also carried
 * {@code employeeCode} so the option text could show it next to the name. That was reverted: the
 * numeric picker field beside this one is labelled "รหัสพนักงาน (Employee ID)" and takes {@code
 * hr.employee.employee_id} — but {@code employee_code} (whose Thai name in the V1 schema comment
 * is literally "รหัสพนักงาน") is a DIFFERENT column, and most production codes are bare numbers
 * from the legacy import, so they read as ids. Showing both next to each other invited exactly
 * that confusion: a user reading {@code employee_code} off the option text and typing it into the
 * id field, which passes {@code createManualCommission}'s null check and then fails the {@code
 * sales_rep_id} foreign key — surfacing as a raw 500 rather than a validation message. Selecting
 * an option now writes {@code id} (the real {@code employee_id}) into that field directly, which
 * is the correct and sufficient behaviour; there is nothing left to cross-check by eye.
 */
public record CommissionRepOptionDto(long id, String name) {}
