package th.co.glr.hr.importrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Proves the ใบขอซื้อ actually assembles from a real deal, through the real SQL.
 *
 * <p>The renderer has its own test and the assembler is pure, so the gap this closes is the one
 * neither can: {@link ImportRequestQueryRepository}'s queries had never been executed against
 * Postgres at all. A form that renders beautifully from hand-built data proves nothing about
 * whether the deal's own columns reach it.
 *
 * <p>Authorisation cases are written the wrong way round per CLAUDE.md — each asks whether a caller
 * can reach a document they should not, rather than confirming the happy path.
 */
class ImportRequestServiceIntegrationTest extends AbstractPostgresIntegrationTest {

    private ImportRequestService service;

    private UserPrincipal importUser;
    private UserPrincipal ceoUser;
    private UserPrincipal salesUser;
    private UserPrincipal accountUser;
    private UserPrincipal hrUser;

    private long ticketId;

    @BeforeEach
    void wireRealCollaborators() {
        service = new ImportRequestService(new ImportRequestQueryRepository(jdbc),
                                           new ImportRequestRenderer(),
                                           new ImportRequestRepository(jdbc));

        long employeeId = insertEmployee("IRSVC");
        importUser  = principal(employeeId, "import");
        ceoUser     = principal(employeeId, "ceo");
        salesUser   = principal(employeeId, "sales");
        accountUser = principal(employeeId, "account");
        hrUser      = principal(employeeId, "hr");

        ticketId = insertTicket(employeeId, "IR-SVC-1", "บริษัท ยู่ฮุย อินทีเรีย จำกัด");
        insertItem(ticketId, "Padana", "Lithos Nero Nat", "60x60 cm", "308", "pcs", 0);
        insertItem(ticketId, "Padana", "Terrazzo White Nat", "30x60 cm", "2370", "pcs", 1);
        insertItem(ticketId, "LEA", "Ceppo Grigio", "60x120 cm", "44", "pcs", 2);
    }

    // ── the SQL actually runs and carries the deal's own data ────────────────────────────────

    @Test
    void brandsOnTheDealAreListedOncePerBrand() {
        assertThat(service.brands(ticketId, importUser)).containsExactlyInAnyOrder("Padana", "LEA");
    }

    @Test
    void renderingProducesAFormCarryingOnlyTheRequestedBrandsLines() throws Exception {
        byte[] pdf = service.render(ticketId, "Padana", "IR69068", "Within 21/5/26", importUser);

        String text = textOf(pdf);
        assertThat(text).contains("Lithos Nero Nat", "Terrazzo White Nat");
        // One IR per brand: the LEA line belongs on its own form and must not leak onto this one.
        assertThat(text).doesNotContain("Ceppo Grigio");
        // Values that can only have come through the SQL, not from the caller. Asserted token by
        // token, not as one string: สั่งมาให้ is a 52.5pt column, so a real company name wraps
        // across the rows its merged cell spans and is not contiguous in the text layer. The point
        // of the assertion is that the DEAL's customer reached the form — not that it fitted on one
        // line, which it cannot.
        assertThat(text).contains("บริษัท").contains("ยู่ฮุย").contains("จำกัด");
        // Caller-supplied values still print.
        assertThat(text).contains("IR69068").contains("Within 21/5/26");
    }

    @Test
    void anUnknownBrandIsRefusedRatherThanRenderedEmpty() {
        assertThatThrownBy(() -> service.render(ticketId, "NoSuchBrand", null, null, importUser))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("NoSuchBrand");
    }

    /**
     * F-SM-001 has no unbranded variant and "Brand" is a header field, so a deal carrying a line
     * with no brand must refuse outright — bucketing it under whichever brand was asked for would
     * print the wrong thing on a controlled document.
     *
     * <p>The brand is BLANK, not null, and that is not a stylistic choice:
     * {@code sales.ticket_item.brand} is {@code NOT NULL} (V8), so Postgres rejects a null outright.
     * This test was written with a null first and the insert itself failed — which is how the
     * service's null branch was found to be unreachable. Blank is the reachable case.
     */
    @Test
    void aDealWithABlankBrandedLineIsRefusedEvenForABrandThatIsPresent() {
        insertItem(ticketId, "   ", "Unbranded Filler", "30x30 cm", "10", "pcs", 3);

        assertThatThrownBy(() -> service.render(ticketId, "Padana", null, null, importUser))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("แบรนด์");
    }

    @Test
    void aTicketThatDoesNotExistIsRefused() {
        assertThatThrownBy(() -> service.render(999_999L, "Padana", null, null, importUser))
            .isInstanceOf(ApiException.class);
    }

    // ── authorisation, asked the wrong way round ─────────────────────────────────────────────

    @Test
    void salesCannotRenderAnImportRequest() {
        assertThatThrownBy(() -> service.render(ticketId, "Padana", null, null, salesUser))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("ไม่มีสิทธิ์");
    }

    @Test
    void accountCannotRenderAnImportRequest() {
        assertThatThrownBy(() -> service.render(ticketId, "Padana", null, null, accountUser))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("ไม่มีสิทธิ์");
    }

    @Test
    void hrCannotRenderAnImportRequest() {
        assertThatThrownBy(() -> service.render(ticketId, "Padana", null, null, hrUser))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("ไม่มีสิทธิ์");
    }

    @Test
    void salesCannotEvenListTheBrandsOnADeal() {
        assertThatThrownBy(() -> service.brands(ticketId, salesUser))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("ไม่มีสิทธิ์");
    }

    /**
     * CEO is in the pair deliberately — {@code IR_ROLES} mirrors
     * {@code TicketService.FULFILMENT_ROLES}, which is {import, ceo}. Note mockApi gates the sibling
     * fulfilment endpoints on 'import' alone, so mock-mode testing as CEO diverges from this.
     */
    @Test
    void ceoCanRenderAnImportRequest() throws Exception {
        assertThat(service.render(ticketId, "Padana", null, null, ceoUser)).isNotEmpty();
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────

    private static String textOf(byte[] pdf) throws Exception {
        try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            return new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
        }
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }

    private long insertEmployee(String code) {
        return jdbc.queryForObject(
            "INSERT INTO hr.employee (employee_code, first_name_th, last_name_th) "
                + "VALUES (:c, 'ทดสอบ', 'ใบขอซื้อ') RETURNING employee_id",
            Map.of("c", code), Long.class);
    }

    private long insertTicket(long createdBy, String code, String customerName) {
        return jdbc.queryForObject("""
            INSERT INTO sales.ticket (code, title, created_by, customer_name)
            VALUES (:code, 'ทดสอบใบขอซื้อ', :by, :customer)
            RETURNING ticket_id
            """, new MapSqlParameterSource().addValue("code", code).addValue("by", createdBy)
                .addValue("customer", customerName), Long.class);
    }

    private void insertItem(long ticket, String brand, String model, String size, String qty,
                            String unit, int sortOrder) {
        jdbc.update("""
            INSERT INTO sales.ticket_item (ticket_id, brand, model, size, qty, unit, sort_order)
            VALUES (:t, :brand, :model, :size, :qty, :unit, :sort)
            """, new MapSqlParameterSource().addValue("t", ticket).addValue("brand", brand)
                .addValue("model", model).addValue("size", size)
                .addValue("qty", new BigDecimal(qty)).addValue("unit", unit)
                .addValue("sort", sortOrder));
    }
}
