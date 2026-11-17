package th.co.glr.hr.leave;

import java.time.LocalTime;

/** Company break used for leave arithmetic, 12:30–13:30. */
public final class LeaveBreakWindow {
    public static final LocalTime START = LocalTime.of(12, 30);
    public static final LocalTime END = LocalTime.of(13, 30);

    private LeaveBreakWindow() {
    }
}
