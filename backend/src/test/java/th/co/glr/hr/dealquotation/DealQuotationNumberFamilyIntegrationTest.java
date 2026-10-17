package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.ticket.EntryChannel;

/**
 * <b>The base number belongs to the DEAL</b> — owner ruling 2026-10-17,
 * {@code .design/quote-stage-gates/VERSIONING.md}.
 *
 * <p>The first quotation on a deal mints the base; every later one continues it with the next
 * suffix, in creation order. One family per deal — not per recipient, not per chain.
 *
 * <p>What this replaces: each create minting its own base from its own {@code nextQuotationCode()}
 * call, which is what {@code DealQuotationRepository#nextRevisionNo}'s Javadoc still describes as
 * the premise. Only {@code insertCopyOf} (revision / reorder) continued a family, which is why
 * versioning worked through the revise button and nowhere else.
 *
 * <p><b>Why "cancel, then create" is the shape of nearly every test here, rather than an
 * artificial one.</b> N6 allows a deal only ONE live direct quotation, so a second {@code create}
 * is reachable only once the first is out of the way. That is not a workaround — it is the real
 * flow: a rep abandons a document and writes another, and the family is what records that both
 * existed. The suffix must advance across it, never be reused.
 *
 * <p>Prod audit 2026-10-17: 198 of 199 deals already hold exactly one family, so this ruling is
 * almost entirely a statement about what happens NEXT, not a change to existing rows. The one
 * exception is pinned by {@link #create_onADealAlreadyHoldingTwoFamilies_continuesTheNewest}.
 */
class DealQuotationNumberFamilyIntegrationTest extends AbstractDealQuotationSlice2IntegrationTest {

    /** The ruling itself: the family is the DEAL's, so a different party does not start a new one. */
    @Test
    void create_addressedToADifferentRecipient_continuesTheSameFamily() {
        long ticket = deal(salesActor, EntryChannel.DESIGNER_LED);
        DealQuotationDto first = quotationService.create(ticket, draft("DESIGNER"), salesActor);
        assertThat(first.revisionNo()).isEqualTo(1);
        cancel(first);

        DealQuotationDto second = quotationService.create(ticket, draft("OWNER"), salesActor);

        assertThat(baseOf(second)).as("same family as the designer's document").isEqualTo(baseOf(first));
        assertThat(second.revisionNo()).isEqualTo(2);
        assertThat(second.number()).isEqualTo(baseOf(first) + "-2");
    }

    /** Same party, same conclusion — the suffix tracks the deal, not the recipient. */
    @Test
    void create_afterCancellingTheFirst_continuesTheFamily_andNeverReusesTheSuffix() {
        long ticket = deal(salesActor, EntryChannel.DESIGNER_LED);
        DealQuotationDto first = quotationService.create(ticket, draft("OWNER"), salesActor);
        cancel(first);
        DealQuotationDto second = quotationService.create(ticket, draft("OWNER"), salesActor);
        cancel(second);

        DealQuotationDto third = quotationService.create(ticket, draft("OWNER"), salesActor);

        assertThat(third.number()).isEqualTo(baseOf(first) + "-3");
        // The cancelled rows keep the numbers they were given. A suffix a customer may have seen is
        // never handed to a second document.
        assertThat(quotationService.get(first.id(), salesActor).number()).isEqualTo(baseOf(first) + "-1");
        assertThat(quotationService.get(second.id(), salesActor).number()).isEqualTo(baseOf(first) + "-2");
    }

    /** A fresh deal has no family to continue, so it mints one — and not another deal's. */
    @Test
    void create_onADealWithNoQuotation_mintsItsOwnFreshBase() {
        long ticketA = deal(salesActor, EntryChannel.DESIGNER_LED);
        long ticketB = deal(salesActor, EntryChannel.DESIGNER_LED);

        DealQuotationDto a = quotationService.create(ticketA, draft("OWNER"), salesActor);
        DealQuotationDto b = quotationService.create(ticketB, draft("OWNER"), salesActor);

        assertThat(a.revisionNo()).isEqualTo(1);
        assertThat(b.revisionNo()).isEqualTo(1);
        assertThat(baseOf(b)).as("two deals never share a family").isNotEqualTo(baseOf(a));
    }

    /**
     * The one shape that exists in production (ticket 114, audited 2026-10-17): a deal that already
     * holds two base families, because every create used to mint its own.
     *
     * <p>It must continue the NEWEST and must not throw. An earlier draft of the design proposed a
     * loud exception here on the belief that such deals "do not exist yet" — they do, and that
     * exception would have turned the next create on this deal into a 500.
     */
    @Test
    void create_onADealAlreadyHoldingTwoFamilies_continuesTheNewest() {
        long ticket = deal(salesActor, EntryChannel.DESIGNER_LED);
        DealQuotationDto older = quotationService.create(ticket, draft("OWNER"), salesActor);
        cancel(older);
        // A second family on the same deal, exactly as the pre-ruling code produced it.
        DealQuotationDto newer = quotationService.create(ticket, draft("OWNER"), salesActor);
        String secondBase = forceSeparateFamily(newer);
        cancel(newer);

        DealQuotationDto next = quotationService.create(ticket, draft("OWNER"), salesActor);

        assertThat(baseOf(next)).as("continues the NEWEST family").isEqualTo(secondBase);
        assertThat(next.number()).isEqualTo(secondBase + "-2");
    }

    /**
     * A legacy row ({@code origin IS NULL}) does NOT seed the family. It is outside
     * {@code nextRevisionNo}'s own origin scope, so letting it seed would hand a deal a family its
     * counter cannot see — and the next document would collide or restart.
     */
    @Test
    void create_onADealWhoseOnlyQuotationIsLegacy_mintsAFreshBase() {
        long ticket = deal(salesActor, EntryChannel.DESIGNER_LED);
        insertLegacyQuotation(ticket, "QN-LEGACY-FAMILY");

        DealQuotationDto created = quotationService.create(ticket, draft("OWNER"), salesActor);

        assertThat(baseOf(created)).isNotEqualTo("QN-LEGACY-FAMILY");
        assertThat(created.revisionNo()).isEqualTo(1);
    }

    /** A revision still continues the family — unchanged behaviour, pinned so this slice cannot break it. */
    @Test
    void createRevision_stillContinuesTheFamily() {
        long ticket = deal(salesActor, EntryChannel.DESIGNER_LED);
        DealQuotationDto approved = approved(ticket, salesActor, "OWNER");

        DealQuotationDto revision = quotationService.createRevision(approved.id(), salesActor);

        assertThat(baseOf(revision)).isEqualTo(baseOf(approved));
        assertThat(revision.revisionNo()).isEqualTo(approved.revisionNo() + 1);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private void cancel(DealQuotationDto quotation) {
        quotationService.cancel(quotation.id(), new DealQuotationRequests.CancelRequest(null), salesActor);
    }

    private static String baseOf(DealQuotationDto quotation) {
        return DealQuotationRepository.baseNumber(quotation.number(), quotation.revisionNo());
    }

    /**
     * Rewrites {@code quotation} onto a BRAND-NEW base, reproducing the pre-ruling state where each
     * create minted its own. Raw SQL on purpose: once the ruling is implemented the service can no
     * longer produce this, and the point of the test is the row shape that already exists in prod.
     * Returns the new base.
     */
    private String forceSeparateFamily(DealQuotationDto quotation) {
        String base = jdbc.queryForObject("SELECT 'QT-FAMILY-' || nextval('sales.quotation_code_seq')",
            new MapSqlParameterSource(), String.class);
        jdbc.update("UPDATE sales.quotation SET number = :number, quotation_revision_no = 1 WHERE quotation_id = :id",
            Map.of("number", base + "-1", "id", quotation.id()));
        return base;
    }

    /** A pre-engine row: {@code origin IS NULL}, bare number, outside nextRevisionNo's scope. */
    private void insertLegacyQuotation(long ticketId, String number) {
        jdbc.update("""
            INSERT INTO sales.quotation (ticket_id, number, issued_by, doc_status, origin, total_amount,
                                         quotation_revision_no)
            VALUES (:ticketId, :number, :issuedBy, 'APPROVED', NULL, :total, 1)
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("number", number)
                .addValue("issuedBy", salesActor.id())
                .addValue("total", new BigDecimal("1000.00")));
    }
}
