package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customer.ProjectDto;
import th.co.glr.hr.customer.ProjectRepository;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricing.PriceCalcService;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * V144: {@code sales.ticket.entry_channel} defaults to {@code UNSPECIFIED} ("not stated") instead
 * of asserting {@code DESIGNER_LED}, and {@code UNSPECIFIED} is rejected as an INPUT so a stated
 * channel can never be un-stated.
 *
 * <p>Mockito cannot reach any of this. The default lives in {@link TicketRepository}'s real INSERT
 * <i>and</i> in the column default the migration sets, and the vocabulary lives in the
 * {@code chk_ticket_entry_channel} CHECK constraint — a mocked repository would happily "pass"
 * while the SQL stored something else, and would say nothing at all about whether V144 applied.
 *
 * <p>The rejection tests are written wrong-way-round on purpose: they assert the caller CANNOT
 * un-state a channel and that the stored value genuinely did not move, not merely that the happy
 * path works.
 *
 * <p>No {@code @Transactional} behaviour is asserted anywhere here: this suite hand-wires services
 * with {@code new}, so the annotation is inert and no rollback is ever exercised (see
 * {@link AbstractPostgresIntegrationTest}).
 */
class EntryChannelIntegrationTest extends AbstractPostgresIntegrationTest {

    private TicketRepository tickets;
    private TicketService ticketService;

    private long salesRepId;
    private UserPrincipal salesRep;
    private long projectId;
    private String customerName;

    @BeforeEach
    void wireRealCollaborators() {
        tickets = new TicketRepository(jdbc);
        CustomerRepository customers = new CustomerRepository(jdbc);
        // PriceCalcService/PricingRequestService are never reached by create() or
        // setEntryChannel(); they exist only to satisfy the constructor.
        ticketService = new TicketService(tickets, new NotificationRepository(jdbc),
            mock(PriceCalcService.class), new ObjectMapper(), customers, new QuotationRenderer(),
            mock(PricingRequestService.class));

        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        salesRepId = employees.create(new UpsertEmployeeRequest(
            null, null, "พนักงานขาย ทดสอบ", null, null, null, null, null, null, null,
            "sales-entry-channel@glr.co.th", null, "SALES", "Sales Division", "แผนกขาย",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
        salesRep = new UserPrincipal(salesRepId, "sales@glr.co.th", "sales", "sales", salesRepId,
            true, LocalDate.of(2020, 1, 1), false, null, false);

        // TicketService.create() refuses a project-less deal (V50), so a real project is required.
        CustomerDto customer = customers.create(
            "บริษัท ทดสอบ จำกัด", "0100000000000", "123 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0000");
        customerName = customer.name();
        ProjectDto project = new ProjectRepository(jdbc).create(customer.id(), "โครงการทดสอบ");
        projectId = project.id();
    }

    // ── the honest default ───────────────────────────────────────────────────

    /**
     * Evidence 1. The whole point of V144: silence must read as silence. Before it, an omitted
     * channel was written as {@code DESIGNER_LED} by both the repository and the column default,
     * so a deliberate designer-led deal and an unattended one were byte-identical in the table.
     */
    @Test
    void createTicket_withNoEntryChannel_landsUnspecifiedNotDesignerLed() {
        long ticketId = ticketService.create(request(null), salesRep).summary().id();

        assertThat(storedChannel(ticketId)).isEqualTo(EntryChannel.UNSPECIFIED);
        assertThat(storedChannel(ticketId)).isNotEqualTo(EntryChannel.DESIGNER_LED);
    }

    /** A blank string is "nobody said" too, not a value to push at the CHECK constraint. */
    @Test
    void createTicket_withBlankEntryChannel_landsUnspecified() {
        long ticketId = ticketService.create(request("   "), salesRep).summary().id();

        assertThat(storedChannel(ticketId)).isEqualTo(EntryChannel.UNSPECIFIED);
    }

    /**
     * Evidence 2. The success path is unchanged for callers who DO state a channel — including
     * {@code DESIGNER_LED}, which must still be storable now that it is no longer the default.
     * (The real create-deal modal always sends one of these three.)
     */
    @Test
    void createTicket_withAnExplicitChannel_storesExactlyThatChannel() {
        for (String channel : List.of(
                EntryChannel.DESIGNER_LED, EntryChannel.OWNER_DIRECT, EntryChannel.BUYER_DIRECT)) {
            long ticketId = ticketService.create(request(channel), salesRep).summary().id();

            assertThat(storedChannel(ticketId)).as("stored channel for %s", channel).isEqualTo(channel);
        }
    }

    // ── UNSPECIFIED is valid as stored, invalid as input ─────────────────────

    /**
     * Evidence 3, wrong-way-round: the caller must NOT be able to un-state a channel, and the
     * stored value must genuinely not move. Mirrors generateQuotation's
     * {@code QuotationRecipient.UNSPECIFIED} rejection.
     */
    @Test
    void setEntryChannel_rejectsUnspecified_andLeavesTheStatedChannelUntouched() {
        long ticketId = ticketService.create(request(null), salesRep).summary().id();
        ticketService.setEntryChannel(ticketId, EntryChannel.OWNER_DIRECT, null, salesRep);
        assertThat(storedChannel(ticketId)).isEqualTo(EntryChannel.OWNER_DIRECT);

        assertThatThrownBy(() ->
            ticketService.setEntryChannel(ticketId, EntryChannel.UNSPECIFIED, "เปลี่ยนกลับ", salesRep))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus().value()).isEqualTo(400));

        assertThat(storedChannel(ticketId)).isEqualTo(EntryChannel.OWNER_DIRECT);
    }

    /** Un-stating is refused even on a deal that was never stated — no "no-op" loophole. */
    @Test
    void setEntryChannel_rejectsUnspecified_evenOnAnAlreadyUnspecifiedDeal() {
        long ticketId = ticketService.create(request(null), salesRep).summary().id();

        assertThatThrownBy(() ->
            ticketService.setEntryChannel(ticketId, EntryChannel.UNSPECIFIED, null, salesRep))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus().value()).isEqualTo(400));

        assertThat(storedChannel(ticketId)).isEqualTo(EntryChannel.UNSPECIFIED);
    }

    /** Evidence 3, other half: the three real channels are still accepted and still persisted. */
    @Test
    void setEntryChannel_stillAcceptsTheThreeRealChannels() {
        for (String channel : List.of(
                EntryChannel.DESIGNER_LED, EntryChannel.OWNER_DIRECT, EntryChannel.BUYER_DIRECT)) {
            long ticketId = ticketService.create(request(null), salesRep).summary().id();

            ticketService.setEntryChannel(ticketId, channel, null, salesRep);

            assertThat(storedChannel(ticketId)).as("channel %s", channel).isEqualTo(channel);
        }
    }

    /** And an unknown value is still a 400, not a 500 from the CHECK constraint. */
    @Test
    void setEntryChannel_stillRejectsAnUnknownChannel() {
        long ticketId = ticketService.create(request(null), salesRep).summary().id();

        assertThatThrownBy(() -> ticketService.setEntryChannel(ticketId, "WALK_IN", null, salesRep))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus().value()).isEqualTo(400));

        assertThat(storedChannel(ticketId)).isEqualTo(EntryChannel.UNSPECIFIED);
    }

    // ── the "changing a stated channel needs a reason" guard, across the new default ──

    /**
     * Regression guard for the note rule. {@code UNSPECIFIED} counts as unstated, so the FIRST
     * statement of a channel on a new deal takes no note — exactly as it did when the default was
     * {@code DESIGNER_LED}. Drop {@code UNSPECIFIED} from that list and every new deal starts
     * demanding a reason before its channel can be recorded at all.
     */
    @Test
    void setEntryChannel_firstStatementOnANewDealNeedsNoNote() {
        long ticketId = ticketService.create(request(null), salesRep).summary().id();

        assertThatCode(() -> ticketService.setEntryChannel(ticketId, EntryChannel.BUYER_DIRECT, null, salesRep))
            .doesNotThrowAnyException();
        assertThat(storedChannel(ticketId)).isEqualTo(EntryChannel.BUYER_DIRECT);
    }

    /** The pre-V144 default is still treated as unstated too — those rows were never backfilled. */
    @Test
    void setEntryChannel_firstStatementOnALegacyDesignerLedDealNeedsNoNote() {
        long ticketId = ticketService.create(request(EntryChannel.DESIGNER_LED), salesRep).summary().id();

        assertThatCode(() -> ticketService.setEntryChannel(ticketId, EntryChannel.OWNER_DIRECT, null, salesRep))
            .doesNotThrowAnyException();
        assertThat(storedChannel(ticketId)).isEqualTo(EntryChannel.OWNER_DIRECT);
    }

    /** But overwriting an already-STATED channel still requires a reason, as before. */
    @Test
    void setEntryChannel_changingAnAlreadyStatedChannelStillRequiresANote() {
        long ticketId = ticketService.create(request(EntryChannel.OWNER_DIRECT), salesRep).summary().id();

        assertThatThrownBy(() -> ticketService.setEntryChannel(ticketId, EntryChannel.BUYER_DIRECT, null, salesRep))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus().value()).isEqualTo(400));
        assertThat(storedChannel(ticketId)).isEqualTo(EntryChannel.OWNER_DIRECT);

        ticketService.setEntryChannel(ticketId, EntryChannel.BUYER_DIRECT, "ลูกค้าติดต่อเองภายหลัง", salesRep);
        assertThat(storedChannel(ticketId)).isEqualTo(EntryChannel.BUYER_DIRECT);
    }

    // ── the migration itself ─────────────────────────────────────────────────

    /**
     * Evidence 5. Proves V144 actually applied, independently of any Java: the widened
     * {@code chk_ticket_entry_channel} accepts {@code UNSPECIFIED} and still refuses junk. Without
     * the migration the first UPDATE would be a constraint violation.
     */
    @Test
    void checkConstraint_acceptsUnspecifiedAndStillRejectsJunk() {
        long ticketId = ticketService.create(request(EntryChannel.OWNER_DIRECT), salesRep).summary().id();

        assertThatCode(() -> updateChannelDirectly(ticketId, EntryChannel.UNSPECIFIED))
            .doesNotThrowAnyException();
        assertThat(storedChannel(ticketId)).isEqualTo(EntryChannel.UNSPECIFIED);

        assertThatThrownBy(() -> updateChannelDirectly(ticketId, "WALK_IN"))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(storedChannel(ticketId)).isEqualTo(EntryChannel.UNSPECIFIED);
    }

    /**
     * The column default is the second half of the fix: any INSERT that does not name
     * {@code entry_channel} — a future code path, a manual fix-up, a data load — must land
     * {@code UNSPECIFIED} rather than the V51 {@code DESIGNER_LED}. TicketRepository's own default
     * is asserted separately above; this one is the schema's.
     */
    @Test
    void columnDefault_isUnspecifiedForAnInsertThatDoesNotNameTheColumn() {
        String channel = jdbc.queryForObject("""
            INSERT INTO sales.ticket (code, title, created_by)
            VALUES (:code, 'ดีลที่ไม่ระบุช่องทาง', :createdBy)
            RETURNING entry_channel
            """,
            new MapSqlParameterSource()
                .addValue("code", tickets.nextTicketCode())
                .addValue("createdBy", salesRepId),
            String.class);

        assertThat(channel).isEqualTo(EntryChannel.UNSPECIFIED);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private CreateTicketRequest request(String entryChannel) {
        return new CreateTicketRequest("ดีลทดสอบ entry channel", "NORMAL", customerName,
            null, projectId, null, null, entryChannel, List.of());
    }

    private String storedChannel(long ticketId) {
        return tickets.findById(ticketId).orElseThrow().summary().entryChannel();
    }

    private void updateChannelDirectly(long ticketId, String value) {
        jdbc.update("UPDATE sales.ticket SET entry_channel = :value WHERE ticket_id = :id",
            new MapSqlParameterSource().addValue("value", value).addValue("id", ticketId));
    }
}
