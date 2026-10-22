package th.co.glr.hr.ticket;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The PARTY route a deal follows through S1–S9, decided by its {@link EntryChannel}.
 *
 * <p>Follows {@link DealStage}'s style: a {@code final class} of constants and static helpers, no
 * Java enum. Deliberately Thai-free EXCEPT {@link #refusalMessage}, which is a refusal and so — like
 * every other {@code ApiException} message in this codebase — is Thai. Stage LABELS (including the
 * per-party wording of S3) are a frontend concern and do not live here.
 *
 * <h2>The three routes</h2>
 * Business numbers are the owner's S1–S20 sheet ({@link DealStage#businessCodeOf}).
 *
 * <pre>
 * DESIGNER_LED  S1 S2 S3 S4 S5 S6 S7 S8 S9 …   off-route: none        (the full spine)
 * OWNER_DIRECT  S1 S2 S3    S5 S6 S7 S8 S9 …   off-route: S4          (no designer to quote)
 * BUYER_DIRECT  S1 S2 S3          S8 S9 …      off-route: S4 S5 S6 S7 (no designer; the project
 *                                              owner is not our counterparty; S7 off-path by
 *                                              owner ruling — the buyer is already in hand)
 * </pre>
 *
 * <p>{@link EntryChannel#UNSPECIFIED}, an unknown value and {@code null} have NO route: every stage
 * is reachable. See "Why the default is empty" below.
 *
 * <h2>S3 is kept on all three routes</h2>
 * {@link DealStage#SPEC_APPROVED} is <strong>not</strong> a designer stage. It means <em>"the party
 * who specifies has agreed the spec"</em>, and only its WORDING differs per party (designer /
 * owner / buyer-or-contractor) — that wording is a frontend concern. It is therefore on-route for
 * every channel. This REVERSES the old {@code SalesRouteWalkIntegrationTest} Case B assertion that
 * S3 "never happens" on an owner-direct deal.
 *
 * <h2>This class reverses V144's "descriptive-only" ruling</h2>
 * V144 states that {@code entry_channel} is "NOT a behavioural flag: nothing reads entry_channel to
 * decide anything (DealStage in particular does not consult it)". That was the owner's ruling then.
 * By owner ruling on 2026-09-30 (design {@code .design/deal-route-staging}, R10) the channel now
 * IS behavioural: {@code TicketService.requireStageMoveAllowed} refuses a MANUAL move whose TARGET
 * is off the deal's route (409), with the remedy of correcting ช่องทางดีล.
 *
 * <h2>Why the default is empty (design §13.1c / §13.2) — load-bearing</h2>
 * <ul>
 *   <li>Pre-V144 rows were never backfilled, so every one reads {@code DESIGNER_LED} whether a human
 *       chose it or the column defaulted it. {@code DESIGNER_LED}'s off-route set being EMPTY is
 *       what makes the gate a no-op for the entire back catalogue and for the majority route.
 *   <li>{@code UNSPECIFIED} (and every quotation-first ghost) must gate NOTHING: GLA-156 removed
 *       the in-portal way to correct a channel, so gating it would brick those deals with no
 *       remedy. This is a safety requirement, not politeness.
 * </ul>
 *
 * <h2>{@link #ROUTE_VARIABLE} — a route may only ever vary the PARTY axis</h2>
 * {@code TicketService.autoAdvanceStage} returns SILENTLY when its target is off-route. That is the
 * right behaviour for a quotation recipient pointing at a stage this deal's route does not visit,
 * and the worst possible one for an OPERATIONAL advance: were any auto-advanced stage off-route,
 * {@code confirmCustomer} would write {@code payment_status=CUSTOMER_CONFIRMED} and leave the stage
 * behind with no error anywhere. So the stages a route may EVER exclude are pinned to the five
 * party stages below, and {@link #validate} throws at class-load if a table strays outside them.
 *
 * <p>This is exactly the distinction {@link DealStage}'s own Javadoc (on {@code MANDATORY}) already
 * draws: stages skipped because a <em>party</em> is not involved — route-dependent, this class's
 * axis — versus stages skipped because of the <em>shape</em> of the deal ({@code DEPOSIT_RECEIVED}
 * only when a deposit is required, {@code PROCUREMENT} only when the goods must be imported) —
 * fact-driven, never route-gated. Note "on-route" means <em>permitted</em>, not <em>visited</em>:
 * Case C legitimately never visits S1–S3 and must still be allowed to.
 */
public final class DealRoute {

    /**
     * The only stages an off-route set may ever contain: S4, S5, S6, S7, S8 — each skipped when a
     * party is absent. Everything else ({@code LEAD_APPROACH}, {@code PRESENTATION},
     * {@code SPEC_APPROVED}, and the whole shared tail {@code NEGOTIATION} … {@code CLOSED_PAID})
     * is on EVERY route, always. See the class Javadoc for why this is structural.
     */
    public static final Set<String> ROUTE_VARIABLE = Set.of(
        DealStage.QUOTE_DESIGN_SIDE, DealStage.QUOTE_OWNER, DealStage.OWNER_SIGNOFF,
        DealStage.AWAITING_BUYER, DealStage.QUOTE_BUYER);

    /**
     * Off-route stages per channel. A channel absent from this map (UNSPECIFIED, unknown, null)
     * has an EMPTY set — see the class Javadoc. {@code DESIGNER_LED} is listed with an empty set
     * so that the table reads as the full statement of the three routes.
     */
    private static final Map<String, Set<String>> OFF_ROUTE = Map.of(
        EntryChannel.DESIGNER_LED, Set.of(),
        EntryChannel.OWNER_DIRECT, Set.of(DealStage.QUOTE_DESIGN_SIDE),
        EntryChannel.BUYER_DIRECT, Set.of(
            DealStage.QUOTE_DESIGN_SIDE, DealStage.QUOTE_OWNER,
            DealStage.OWNER_SIGNOFF, DealStage.AWAITING_BUYER));

    static {
        validate(OFF_ROUTE);
    }

    /**
     * Throws if any channel's off-route set contains a stage outside {@link #ROUTE_VARIABLE}. A
     * programming error, not a runtime condition — package-private so the test can hand it a bad
     * table and prove it refuses.
     */
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

    /**
     * The ordered on-route stages, filtered from {@link DealStage#ORDER} (never re-ordered). Mirrors
     * the shape of {@code PaymentTrack.path(policy)}, the precedent the design cites: the backend
     * SERVES the path so a client renders it instead of re-deriving it.
     */
    public static List<String> path(String entryChannel) {
        Set<String> off = offRouteStages(entryChannel);
        return DealStage.ORDER.stream().filter(stage -> !off.contains(stage)).toList();
    }

    /**
     * The Thai refusal for a manual move into an off-route stage — names the deal's channel, the
     * stage and the remedy:
     * {@code ดีลนี้เป็นเจ้าของติดต่อโดยตรง — ขั้นที่ 4 ไม่อยู่ในเส้นทางของดีลนี้ — แก้ช่องทางดีลก่อน}.
     * The channel phrase is derived from the channel, never hardcoded to one route.
     *
     * <p><strong>The stage is named by its DISPLAY NUMBER, never by its code.</strong> This string is
     * rendered VERBATIM to a Thai-speaking rep — {@code UpdateStageModal} prints {@code blockedReason}
     * as-is in its blocked-stage list — so {@code "ขั้นตอน QUOTE_DESIGN_SIDE ไปไม่ได้"} would put a raw
     * Java constant in front of a salesperson. The number also matches what the rep already sees:
     * that same modal row is labelled {@code "4. เสนอราคาผู้ออกแบบ"} from {@code DealStage.displayNoOf}
     * plus the frontend's Thai label, so repeating the code would be both untranslated and redundant.
     * (The nearby {@code "ดีลนี้อยู่ในขั้นตอน " + targetStage} refusal does interpolate a code, but it
     * is unreachable from that list — the modal filters the deal's CURRENT stage out — so it is not a
     * precedent for a rep-visible message.)
     */
    public static String refusalMessage(String entryChannel, String stage) {
        return "ดีลนี้เป็น" + channelPhrase(entryChannel) + " — ขั้นที่ " + DealStage.displayNoOf(stage)
            + " ไม่อยู่ในเส้นทางของดีลนี้ — แก้ช่องทางดีลก่อน";
    }

    /** How {@link #refusalMessage} names a stage: {@code "ขั้นที่ 4"}. Tests assert against this. */
    static String stagePhrase(String stage) {
        return "ขั้นที่ " + DealStage.displayNoOf(stage);
    }

    /**
     * Same phrases as the frontend's {@code entryChannelLabel} (R8: long labels everywhere). Only
     * reachable for a channel with a non-empty off-route set, so the fallback is defensive.
     */
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
