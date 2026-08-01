package th.co.glr.hr.payroll;

/**
 * NOT IMPLEMENTED, DELIBERATELY. Marker/documentation only -- issue #376 is explicit that the
 * correct ordering when pay cannot satisfy every deduction "needs a legal answer, not an
 * engineering preference," and instructs against guessing one. This class exists so that question
 * has a single, discoverable home in the code rather than being silently absent, and so the
 * DE-FACTO behaviour of the current engine -- which is real and worth knowing even though it was
 * never a deliberate policy decision -- is written down for whoever answers the open question.
 *
 * <h2>Open question (quoted from issue #376)</h2>
 * "What is the correct ordering when pay cannot satisfy all obligations -- statutory first, then
 * court order, then consented? This needs a legal answer, not an engineering preference."
 *
 * <h2>The de-facto order today (found by reading {@code PayrollCalculator#calculateClassified})</h2>
 * There is no allocation/ordering logic in the engine at all -- every deduction below is taken in
 * FULL, unconditionally, regardless of what is left. {@code netPay} is floored at zero at the very
 * end (after every deduction has already been subtracted in full), so an employee whose deductions
 * exceed their pay this period is not protected by any ordering; the shortfall simply lands
 * silently in a netPay of exactly ฿0.00 with no record of which deduction(s) would have needed to
 * yield. The one exception is {@link PayrollGarnishmentType} garnishment, which is the ONLY
 * deduction sensitive to what remains -- see below.
 *
 * <p>The subtraction order that produces {@code netBeforeGarnishment} (used only as the ป.วิ.พ.
 * ม.302 SALARY floor's basis, not as a real allocation) is:
 * <ol>
 *   <li>Social security
 *   <li>Withholding tax
 *   <li>Student loan (กยศ.)
 *   <li>Other post-tax deductions
 *   <li>Warning-letter deduction
 *   <li>Customer-return deduction
 *   <li><b>Legal execution / garnishment</b> -- computed LAST, against whatever remains after 1-6
 *       are already subtracted in full, and clamped by {@link PayrollGarnishmentType}'s cap +
 *       ฿20,000 minimum-net floor.
 * </ol>
 *
 * <p>This is an artefact of expression order in {@code PayrollCalculator}, not a considered policy
 * -- it happens to put every {@code STATUTORY_AUTHORITY_DIRECTED} deduction (see {@link
 * PayrollDeductionClassification}) ahead of the one {@code COURT_OR_LED_ORDER} deduction, which
 * coincidentally matches one plausible answer to the open question above ("statutory first, then
 * court order"). Coincidence is not confirmation: this must not be read as the intended policy
 * until the open question is actually answered, and it says nothing about where a future
 * {@code LABOUR_ACT_CAPPED} or {@code VOLUNTARY} deduction would sit.
 *
 * <h2>Also NOT implemented, same reason</h2>
 * <ul>
 *   <li>ม.76's 10% per-item / 20% aggregate cap -- see {@link DeductionCapGroup#LABOUR_ACT_SECTION_76}'s
 *       javadoc; the per-row mapping (which recorded deductions are genuinely items (2)-(5)) is
 *       unconfirmed for {@link PayrollDeductionKind#WARNING_LETTER}/{@link
 *       PayrollDeductionKind#CUSTOMER_RETURN}, and OTHER_PRETAX/OTHER_POST_TAX carry no sub-typing
 *       at all.
 *   <li>A protected-minimum-net floor applied ACROSS every deduction. Open question (issue #376):
 *       does such a floor apply generally, or only inside the garnishment calculation where a
 *       ฿20,000/฿300,000 floor already exists ({@code PayrollCalculator}'s {@code
 *       MIN_NET_AFTER_LEGAL_EXECUTION}/{@code MIN_NET_AFTER_SEVERANCE_GARNISHMENT})? Not
 *       generalised here.
 * </ul>
 */
public final class PayrollDeductionOrderingPolicy {
    private PayrollDeductionOrderingPolicy() {
    }
}
