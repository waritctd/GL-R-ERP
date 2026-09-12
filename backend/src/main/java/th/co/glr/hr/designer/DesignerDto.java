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
 */
public record DesignerDto(String code, String name, boolean active) {}
