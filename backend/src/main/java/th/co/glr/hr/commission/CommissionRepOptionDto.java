package th.co.glr.hr.commission;

/** One selectable option in the manual-commission rep picker (issue #737) — an employee's id plus a display
 * name only, deliberately NOT the hr.employee record: no salary, no contact details, no national ID, no
 * division/position. */
public record CommissionRepOptionDto(long id, String name) {}
