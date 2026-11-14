package th.co.glr.hr.ticket;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** The PARTY route a deal follows through S1–S9, decided by its EntryChannel. Stage LABELS (including the
 * per-party wording of S3) are a frontend concern and do not live here. This REVERSES the old
 * SalesRouteWalkIntegrationTest Case B assertion that S3 "never happens" on an owner-direct deal. */
public final class DealRoute {

    /** The only stages an off-route set may ever contain: S4, S5, S6, S7, S8 — each skipped when a party is absent.
     * Everything else (LEAD_APPROACH, PRESENTATION, SPEC_APPROVED, and the whole shared tail NEGOTIATION …
     * CLOSED_PAID) is on EVERY route, always. See the class Javadoc for why this is structural. */
    public static final Set<String> ROUTE_VARIABLE = Set.of(
        DealStage.QUOTE_DESIGN_SIDE, DealStage.QUOTE_OWNER, DealStage.OWNER_SIGNOFF,
        DealStage.AWAITING_BUYER, DealStage.QUOTE_BUYER);

    /** Off-route stages per channel. A channel absent from this map (UNSPECIFIED, unknown, null) has an EMPTY set —
     * see the class Javadoc. DESIGNER_LED is listed with an empty set so that the table reads as the full
     * statement of the three routes. */
    private static final Map<String, Set<String>> OFF_ROUTE = Map.of(
        EntryChannel.DESIGNER_LED, Set.of(),
        EntryChannel.OWNER_DIRECT, Set.of(DealStage.QUOTE_DESIGN_SIDE),
        EntryChannel.BUYER_DIRECT, Set.of(
            DealStage.QUOTE_DESIGN_SIDE, DealStage.QUOTE_OWNER,
            DealStage.OWNER_SIGNOFF, DealStage.AWAITING_BUYER));

    static {
        validate(OFF_ROUTE);
    }

    /** Throws if any channel's off-route set contains a stage outside #ROUTE_VARIABLE. A programming error, not a
     * runtime condition — package-private so the test can hand it a bad table and prove it refuses. */
    static void validate(Map<String, Set<String>> table) {
        table.forEach((channel, off) -> {
            for (String stage : off) {
                if (!ROUTE_VARIABLE.contains(stage)) {
                    throw new IllegalStateException("DealRoute: " + stage + " cannot be off-route for "
                        + channel + " — only party stages may vary by route (see ROUTE_VARIABLE)");
                }
            }
        });
    }

    /** Stages this channel's route does NOT visit. Empty for UNSPECIFIED, unknown values and null. */
    public static Set<String> offRouteStages(String entryChannel) {
        if (entryChannel == null) {
            return Set.of();
        }
        return OFF_ROUTE.getOrDefault(entryChannel, Set.of());
    }

    /** Whether {@code stage} is permitted on this channel's route. Never true-to-false for the shared tail. */
    public static boolean isOnRoute(String entryChannel, String stage) {
        return !offRouteStages(entryChannel).contains(stage);
    }

    /** The ordered on-route stages, filtered from DealStage#ORDER (never re-ordered). Mirrors the shape of
     * PaymentTrack.path(policy), the precedent the design cites: the backend SERVES the path so a client renders
     * it instead of re-deriving it. */
    public static List<String> path(String entryChannel) {
        Set<String> off = offRouteStages(entryChannel);
        return DealStage.ORDER.stream().filter(stage -> !off.contains(stage)).toList();
    }

    /** The Thai refusal for a manual move into an off-route stage — names the deal's channel, the stage and the
     * remedy: ดีลนี้เป็นเจ้าของติดต่อโดยตรง — ขั้นที่ 4 ไม่อยู่ในเส้นทางของดีลนี้ — แก้ช่องทางดีลก่อน. The channel
     * phrase is derived from the channel, never hardcoded to one route. */
    public static String refusalMessage(String entryChannel, String stage) {
        return "ดีลนี้เป็น" + channelPhrase(entryChannel) + " — ขั้นที่ " + DealStage.displayNoOf(stage)
            + " ไม่อยู่ในเส้นทางของดีลนี้ — แก้ช่องทางดีลก่อน";
    }

    /** How {@link #refusalMessage} names a stage: {@code "ขั้นที่ 4"}. Tests assert against this. */
    static String stagePhrase(String stage) {
        return "ขั้นที่ " + DealStage.displayNoOf(stage);
    }

    /** Same phrases as the frontend's entryChannelLabel (R8: long labels everywhere). Only reachable for a channel
     * with a non-empty off-route set, so the fallback is defensive. */
    private static String channelPhrase(String entryChannel) {
        if (EntryChannel.OWNER_DIRECT.equals(entryChannel)) {
            return "เจ้าของติดต่อโดยตรง";
        }
        if (EntryChannel.BUYER_DIRECT.equals(entryChannel)) {
            return "ผู้ซื้อ/ผู้รับเหมาติดต่อโดยตรง";
        }
        if (EntryChannel.DESIGNER_LED.equals(entryChannel)) {
            return "ผู้ออกแบบนำดีล";
        }
        return "ดีลที่ระบุช่องทางไว้";
    }

    private DealRoute() {}
}
