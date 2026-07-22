package th.co.glr.hr.payroll.export;

/** A generated statutory export file: its suggested download name and raw CP874 bytes. */
public record PayrollExportFile(PayrollExportKind kind, String fileName, byte[] content) {}
