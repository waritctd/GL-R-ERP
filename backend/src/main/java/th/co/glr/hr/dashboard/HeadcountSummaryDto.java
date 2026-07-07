package th.co.glr.hr.dashboard;

import java.util.List;

public record HeadcountSummaryDto(
    String scope,
    Long active,
    Long inactive,
    Long total,
    List<DivisionHeadcountDto> byDivision
) {
    public static HeadcountSummaryDto empty(String scope) {
        return new HeadcountSummaryDto(scope, null, null, null, List.of());
    }
}
