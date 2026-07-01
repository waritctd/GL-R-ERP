package th.co.glr.hr.overtime;

import java.util.List;

public final class OvertimeResponses {
    private OvertimeResponses() {
    }

    public record OvertimeListResponse(List<OvertimeRequestDto> requests) {
    }

    public record OvertimeDetailResponse(OvertimeRequestDto request) {
    }

    public record OvertimeEmployeeOptionsResponse(List<OvertimeEmployeeOption> employees) {
    }
}
