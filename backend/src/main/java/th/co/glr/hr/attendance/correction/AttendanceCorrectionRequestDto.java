package th.co.glr.hr.attendance.correction;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record AttendanceCorrectionRequestDto(
    long id,
    long employeeId,
    String employeeCode,
    String employeeName,
    LocalDate workDate,
    String correctionType,
    OffsetDateTime requestedCheckIn,
    OffsetDateTime requestedCheckOut,
    String reason,
    String status,
    Long requestedById,
    String requestedByName,
    OffsetDateTime requestedAt,
    Long reviewedById,
    String reviewedByName,
    OffsetDateTime reviewedAt,
    String reviewerNote,
    OffsetDateTime cancelledAt,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    // Computed server-side per requesting actor (CEO or the requester themselves), the same
    // "could this actor act on this row right now" flag LeaveRequestDto#canReview carries -- not a
    // bare role check, so the frontend never has to re-derive it and can never disagree with what
    // the server will actually enforce on approve/reject.
    boolean canReview
) {
}
