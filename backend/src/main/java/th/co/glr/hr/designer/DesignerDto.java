package th.co.glr.hr.designer;

/** One row of sales.designer (V173). name is CONFIDENTIAL per the owner ("เป็นความลับ") -- it is returned here
 * ONLY so the quotation-editor picker can show a rep the name to search by. */
public record DesignerDto(String code, String name, boolean active) {}
