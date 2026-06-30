package th.co.glr.hr.attendance;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import th.co.glr.hr.common.ApiException;

@Component
public class AttendanceDatParser {
    private static final DateTimeFormatter DAT_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    // A single device's daily export is a few thousand rows at most; cap defensively.
    private static final int MAX_ROWS = 100_000;

    public DatParseResult parse(AttendanceDatImportRequest request) {
        String siteCode = request.siteCode().trim().toUpperCase();
        String deviceCode = request.deviceCode().trim().toUpperCase();
        ArrayList<NormalizedAttendancePunch> punches = new ArrayList<>();
        ArrayList<AttendanceImportErrorRecord> errors = new ArrayList<>();
        int rowCount = 0;

        String[] lines = request.content().split("\\R", -1);
        if (lines.length > MAX_ROWS) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,
                "DAT file exceeds the maximum of " + MAX_ROWS + " rows");
        }
        for (int index = 0; index < lines.length; index++) {
            int lineNo = index + 1;
            String rawLine = lines[index];
            if (rawLine == null || rawLine.isBlank()) {
                continue;
            }
            rowCount++;
            try {
                punches.add(parseLine(siteCode, deviceCode, lineNo, rawLine));
            } catch (IllegalArgumentException exception) {
                errors.add(new AttendanceImportErrorRecord(lineNo, rawLine, "INVALID_ROW", exception.getMessage()));
            }
        }

        return new DatParseResult(punches, errors, rowCount);
    }

    private NormalizedAttendancePunch parseLine(String siteCode, String deviceCode, int lineNo, String rawLine) {
        String[] fields = rawLine.strip().split("\\t", -1);
        if (fields.length != 6) {
            throw new IllegalArgumentException("Expected 6 tab-separated fields, got " + fields.length);
        }

        String badgeCode = fields[0].trim();
        if (badgeCode.isBlank()) {
            throw new IllegalArgumentException("Badge code is blank");
        }

        OffsetDateTime punchTime = parseTimestamp(fields[1].trim());
        short deviceStatus = parseSmallInt(fields[2], "device status");
        short punchState = parseSmallInt(fields[3], "punch state");
        String workCode = blankDefault(fields[4], "0");
        String reservedValue = blankDefault(fields[5], "0");

        return new NormalizedAttendancePunch(
            siteCode,
            deviceCode,
            badgeCode,
            punchTime,
            punchTime.toLocalDate(),
            deviceStatus,
            punchState,
            workCode,
            reservedValue,
            "BIOMETRIC",
            "USB_DAT_IMPORT",
            Map.of(
                "line_no", lineNo,
                "badge_code", badgeCode,
                "punch_time", punchTime.toString(),
                "device_status", deviceStatus,
                "punch_state", punchState,
                "work_code", workCode,
                "reserved_value", reservedValue
            )
        );
    }

    private OffsetDateTime parseTimestamp(String value) {
        try {
            return LocalDateTime.parse(value, DAT_TIMESTAMP).atZone(BANGKOK).toOffsetDateTime();
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Invalid punch timestamp: " + value);
        }
    }

    private short parseSmallInt(String value, String label) {
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 0 || parsed > 255) {
                throw new IllegalArgumentException(label + " must be between 0 and 255");
            }
            return (short) parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid " + label + ": " + value);
        }
    }

    private String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
