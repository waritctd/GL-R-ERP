package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The pure route definition: which stages are OFF each entry channel's route. A test that only checked
 * "owner-direct excludes S4" would stay green if someone "helpfully" gave the default route an opinion. */
class DealRouteTest {

    private static final int ALL_STAGES = DealStage.ORDER.size();

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

}
