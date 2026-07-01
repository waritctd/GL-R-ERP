package th.co.glr.hr.attendance;

/**
 * Authentication view of an attendance device: the site it is bound to, whether it is active, and
 * the SHA-256 hash of its per-device agent token ({@code null} until one has been provisioned).
 */
public record AttendanceDeviceCredential(String siteCode, boolean active, String tokenHash) {
}
