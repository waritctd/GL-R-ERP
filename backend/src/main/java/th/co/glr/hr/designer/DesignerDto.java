package th.co.glr.hr.designer;

/**
 * One row of {@code sales.designer} (V173). {@code name} is CONFIDENTIAL per the owner
 * ("เป็นความลับ") -- it is returned here ONLY so the quotation-editor picker can show a rep the
 * name to search by. Nothing in this codebase is permitted to carry a {@code DesignerDto} (or its
 * {@code name}) into {@code QuotationRenderModel}, {@code DealQuotationRenderAdapter}, or any DTO
 * that reaches a rendered document -- only the bare {@code code} String ever flows into
 * {@code sales.quotation.unit_code}. See {@code DealQuotationRenderAdapter} for how little of the
 * quotation aggregate that render path already touches: it reads {@code quotation.unitCode()} as
 * a plain String and never looks anywhere near this table.
 *
 * <p>⚠️ REVERSAL (owner ask relayed 2026-09-26, task "designer-add-from-ui"): {@code sales.designer}
 * is no longer strictly read-only from the application -- see {@link DesignerRepository#create} and
 * {@link DesignerController}'s own class Javadoc for the new write path and its authz gate. This
 * confidentiality guarantee is unaffected by that reversal: a row created through the new endpoint
 * is a {@code DesignerDto} exactly like an Excel-imported one, and the "never reaches a rendered
 * document" rule above governs it identically.
 */
public record DesignerDto(String code, String name, boolean active) {}
