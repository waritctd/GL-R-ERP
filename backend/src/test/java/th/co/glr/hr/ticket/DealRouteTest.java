package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The pure route definition: which stages are OFF each entry channel's route. The gate that
 * enforces it is proved against a real row in {@code DealRouteGateIntegrationTest}; this class pins
 * the tables themselves, where every channel can be enumerated cheaply.
 *
 * <p>The assertion that matters most is the emptiness one. {@code DESIGNER_LED} (and every stage
 * of {@code UNSPECIFIED}/unknown/null) having NO off-route stage is what makes the gate a no-op for
 * the entire pre-V144 back catalogue (design §13.2) and what stops a deal with no stated channel
 * being bricked (design §13.1c). A test that only checked "owner-direct excludes S4" would stay
 * green if someone "helpfully" gave the default route an opinion.
 */
class DealRouteTest {

    private static final int ALL_STAGES = DealStage.ORDER.size();

    // ── the three real routes ────────────────────────────────────────────────

    /** Full 15-stage spine — the majority route, and what every un-chosen legacy row reads. */
    @Test
    void designerLed_hasNoOffRouteStages() {
        assertThat(DealRoute.offRouteStages(EntryChannel.DESIGNER_LED)).isEmpty();
        assertThat(DealRoute.path(EntryChannel.DESIGNER_LED)).containsExactlyElementsOf(DealStage.ORDER);
        for (String stage : DealStage.ORDER) {
            assertThat(DealRoute.isOnRoute(EntryChannel.DESIGNER_LED, stage))
                .as("%s on the designer-led route", stage).isTrue();
        }
    }

    /** No designer to quote — and nothing else. S3 is KEPT (only its wording differs per party). */
    @Test
    void ownerDirect_excludesExactlyQuoteDesignSide() {
        assertThat(DealRoute.offRouteStages(EntryChannel.OWNER_DIRECT))
            .containsExactlyInAnyOrder(DealStage.QUOTE_DESIGN_SIDE);
        assertThat(DealRoute.isOnRoute(EntryChannel.OWNER_DIRECT, DealStage.QUOTE_DESIGN_SIDE)).isFalse();
        // The reversal of the old "S3 never happens on an owner-direct deal" ruling.
        assertThat(DealRoute.isOnRoute(EntryChannel.OWNER_DIRECT, DealStage.SPEC_APPROVED)).isTrue();
        assertThat(DealRoute.path(EntryChannel.OWNER_DIRECT)).hasSize(ALL_STAGES - 1);
    }

    /** No designer, the project owner is not our counterparty, and S7 is off-path (owner ruling). */
    @Test
    void buyerDirect_excludesExactlyTheFourPartyStages() {
        assertThat(DealRoute.offRouteStages(EntryChannel.BUYER_DIRECT)).containsExactlyInAnyOrder(
            DealStage.QUOTE_DESIGN_SIDE, DealStage.QUOTE_OWNER,
            DealStage.OWNER_SIGNOFF, DealStage.AWAITING_BUYER);
        assertThat(DealRoute.isOnRoute(EntryChannel.BUYER_DIRECT, DealStage.SPEC_APPROVED)).isTrue();
        assertThat(DealRoute.isOnRoute(EntryChannel.BUYER_DIRECT, DealStage.QUOTE_BUYER)).isTrue();
        assertThat(DealRoute.path(EntryChannel.BUYER_DIRECT)).hasSize(ALL_STAGES - 4);
    }

    // ── the absence of a route: everything reachable ─────────────────────────

    /**
     * A safety requirement, not politeness: pre-V144 rows and quotation-first ghosts can carry
     * UNSPECIFIED and GLA-156 removed the in-portal way to correct it, so gating it would brick
     * those deals with no remedy.
     */
    @Test
    void unspecified_unknownAndNull_haveAllFifteenStagesOnRoute() {
        for (String channel : new String[] {EntryChannel.UNSPECIFIED, "SOMETHING_NEW", "", null}) {
            assertThat(DealRoute.offRouteStages(channel)).as("off-route set for %s", channel).isEmpty();
            assertThat(DealRoute.path(channel)).as("path for %s", channel)
                .containsExactlyElementsOf(DealStage.ORDER);
            for (String stage : DealStage.ORDER) {
                assertThat(DealRoute.isOnRoute(channel, stage))
                    .as("%s on the route of channel %s", stage, channel).isTrue();
            }
        }
    }

    // ── table hygiene ────────────────────────────────────────────────────────

    /** Catches a typo'd stage string, which would silently gate nothing. */
    @Test
    void everyOffRouteSet_isASubsetOfDealStageOrder() {
        for (String channel : EntryChannel.VALID) {
            assertThat(DealStage.ORDER).as("stages of %s", channel)
                .containsAll(DealRoute.offRouteStages(channel));
        }
    }

    /** {@code path} is filtered FROM {@link DealStage#ORDER}, never re-ordered. */
    @Test
    void path_preservesDealStageOrder_forEveryChannel() {
        for (String channel : EntryChannel.VALID) {
            List<String> expected = new ArrayList<>(DealStage.ORDER);
            Set<String> off = DealRoute.offRouteStages(channel);
            expected.removeIf(off::contains);

            assertThat(DealRoute.path(channel)).as("path for %s", channel)
                .containsExactlyElementsOf(expected);
        }
    }

    // ── the structural invariant: a route may only ever vary the PARTY axis ───

    /**
     * {@code autoAdvanceStage} returns silently on an off-route target. That is right for a
     * quotation recipient pointing at a stage the deal's route does not visit, and catastrophic for
     * an OPERATIONAL advance: were any auto-advanced stage off-route, {@code confirmCustomer} would
     * set {@code payment_status=CUSTOMER_CONFIRMED} and leave the stage behind with no error at all.
     * So the set of stages a route may ever exclude is pinned, explicitly, to the five party stages.
     */
    @Test
    void routeVariable_isExactlyTheFivePartyStages() {
        assertThat(DealRoute.ROUTE_VARIABLE).containsExactlyInAnyOrder(
            DealStage.QUOTE_DESIGN_SIDE, DealStage.QUOTE_OWNER, DealStage.OWNER_SIGNOFF,
            DealStage.AWAITING_BUYER, DealStage.QUOTE_BUYER);
    }

    @Test
    void everyOffRouteSet_isInsideRouteVariable() {
        for (String channel : EntryChannel.VALID) {
            assertThat(DealRoute.ROUTE_VARIABLE).as("off-route stages of %s", channel)
                .containsAll(DealRoute.offRouteStages(channel));
        }
    }

    /**
     * Enumerated explicitly rather than derived: the test must still fail if someone edits a table
     * (or {@code DealStage.MANDATORY}) in a way that puts an operational stage off a route.
     * "On-route" means PERMITTED, not visited — Case C never visits S1-S3 and must still be allowed.
     */
    @Test
    void everyOperationalAndMandatoryStage_isOnRouteForEveryChannel() {
        List<String> alwaysOnRoute = List.of(
            DealStage.LEAD_APPROACH, DealStage.PRESENTATION, DealStage.SPEC_APPROVED,
            // the auto-advanced targets
            DealStage.NEGOTIATION, DealStage.ORDER_RECEIVED, DealStage.DEPOSIT_RECEIVED,
            DealStage.PROCUREMENT, DealStage.DELIVERY_SCHEDULING, DealStage.DELIVERED,
            DealStage.CLOSED_PAID);
        for (String channel : new String[] {EntryChannel.DESIGNER_LED, EntryChannel.OWNER_DIRECT,
                EntryChannel.BUYER_DIRECT, EntryChannel.UNSPECIFIED, "SOMETHING_NEW", null}) {
            for (String stage : alwaysOnRoute) {
                assertThat(DealRoute.isOnRoute(channel, stage))
                    .as("%s must be on the route of channel %s", stage, channel).isTrue();
            }
        }
    }

    /** The static guard: a table that excludes anything outside ROUTE_VARIABLE is a programming error. */
    @Test
    void validate_rejectsATableThatExcludesANonRouteVariableStage() {
        for (String forbidden : List.of(DealStage.CLOSED_PAID, DealStage.ORDER_RECEIVED,
                DealStage.PROCUREMENT, DealStage.SPEC_APPROVED, DealStage.LEAD_APPROACH)) {
            assertThatThrownBy(() ->
                DealRoute.validate(Map.of(EntryChannel.OWNER_DIRECT, Set.of(forbidden))))
                .as("off-route %s", forbidden)
                .isInstanceOf(IllegalStateException.class);
        }
        // and a legitimate table passes
        DealRoute.validate(Map.of(EntryChannel.OWNER_DIRECT, Set.of(DealStage.QUOTE_DESIGN_SIDE)));
    }

    // ── the refusal message ──────────────────────────────────────────────────

    /** Names the deal's channel, the stage, and the remedy — and derives the channel from input. */
    @Test
    void refusalMessage_namesChannelStageAndRemedy_perChannel() {
        String owner = DealRoute.refusalMessage(EntryChannel.OWNER_DIRECT, DealStage.QUOTE_DESIGN_SIDE);
        // The stage is named by DISPLAY NUMBER, never by its code: this string is shown verbatim to
        // a Thai-speaking rep, so a raw Java constant must never appear in it.
        assertThat(owner).contains("เจ้าของติดต่อโดยตรง")
            .contains(DealRoute.stagePhrase(DealStage.QUOTE_DESIGN_SIDE))
            .doesNotContain(DealStage.QUOTE_DESIGN_SIDE)
            .contains("แก้ช่องทางดีล");

        String buyer = DealRoute.refusalMessage(EntryChannel.BUYER_DIRECT, DealStage.AWAITING_BUYER);
        assertThat(buyer).contains("ผู้ซื้อ/ผู้รับเหมาติดต่อโดยตรง")
            .contains(DealRoute.stagePhrase(DealStage.AWAITING_BUYER))
            .doesNotContain(DealStage.AWAITING_BUYER)
            .contains("แก้ช่องทางดีล");

        assertThat(owner).isNotEqualTo(buyer);
        assertThat(buyer).doesNotContain("เจ้าของติดต่อโดยตรง");
    }
}
