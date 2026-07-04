package th.co.glr.hr.dashboard;

public record DivisionHeadcountDto(
    Long divisionId,
    String divisionCode,
    String divisionName,
    long active,
    long inactive,
    long total
) {
}
