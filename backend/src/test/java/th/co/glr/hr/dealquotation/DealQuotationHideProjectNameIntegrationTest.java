package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ApproveRequest;
import th.co.glr.hr.dealquotation.DealQuotationRequests.UpsertDealQuotationRequest;
import th.co.glr.hr.ticket.QuotationStatus;

/**
 * Sales feedback #5 (2026-10-06, V192.1) — "ไม่แสดงชื่อโครงการในใบเสนอราคา", through the REAL
 * {@link DealQuotationService} against real Postgres (the production classes, hand-wired by
 * {@link AbstractDealDirectQuotationIntegrationTest} — no Mockito anywhere).
 *
 * <p>What only a real database can show, and {@code DealQuotationHideProjectNameRenderTest} (pure,
 * no DB) cannot:
 * <ul>
 *   <li>the flag a rep ticks is actually WRITTEN to {@code sales.quotation.hide_project_name} and
 *       READ back by the same query every other read path uses;</li>
 *   <li>the document the service itself renders from that stored row — {@link
 *       DealQuotationService#renderXlsx}, the same model the PDF is printed from — drops the
 *       "Project : …" line, i.e. the stored choice really reaches paper;</li>
 *   <li>a revision / a reorder clone carries the choice forward.</li>
 * </ul>
 *
 * <p>Written wrong-way-round where it matters: a save that does NOT mention the flag must not
 * un-hide a hidden project, and a document that is no longer a DRAFT must not change what it prints.
 */
class DealQuotationHideProjectNameIntegrationTest extends AbstractDealDirectQuotationIntegrationTest {

    private static final String PROJECT = PROJECT_NAME;

    // ── create ───────────────────────────────────────────────────────────────────────────────

    @Test
    void create_printsTheProjectByDefault_whenTheRequestSaysNothing() {
        DealQuotationDto created = quotationService.create(deal(salesActor), draft(), salesActor);

        assertThat(created.hideProjectName()).isFalse();
        assertThat(storedFlag(created.id())).isFalse();
        assertThat(created.projectName()).isEqualTo(PROJECT);
    }

    @Test
    void create_hidden_isStored_andTheProjectNameItselfIsKept() {
        DealQuotationDto created = quotationService.create(deal(salesActor), hidden(draft(), true), salesActor);

        assertThat(created.hideProjectName()).isTrue();
        assertThat(storedFlag(created.id())).isTrue();
        // "Hide" is not "delete": the deal, the register and the editor still name the project.
        assertThat(created.projectName()).isEqualTo(PROJECT);
        assertThat(storedProjectName(created.id())).isEqualTo(PROJECT);
    }

    // ── update ───────────────────────────────────────────────────────────────────────────────

    /**
     * The wrong-way-round case, and DELIBERATELY the opposite of {@code omitContactHonorific}
     * (whose update "clears when omitted"). A browser tab still running the bundle from before this
     * field existed sends no {@code hideProjectName} at all. Reading that as {@code false} would
     * silently start PRINTING a project name the rep had chosen to hide — on a customer document —
     * the first time anyone saved from the stale tab. A missing value says nothing, so the stored
     * choice stands.
     */
    @Test
    void update_thatDoesNotMentionTheFlag_keepsAHiddenProjectHidden() {
        long ticket = deal(salesActor);
        DealQuotationDto created = quotationService.create(ticket, hidden(draft(), true), salesActor);

        DealQuotationDto updated = quotationService.update(created.id(), edit(null), salesActor);

        assertThat(updated.hideProjectName()).isTrue();
        assertThat(storedFlag(created.id())).isTrue();
        // The name is still stored (so this is the FLAG hiding it, not a cleared name)…
        assertThat(storedProjectName(created.id())).isEqualTo(PROJECT);
        // …and the document still does not print it.
        assertThat(sheetTexts(created.id())).noneMatch(text -> text.contains(PROJECT));
    }

    @Test
    void update_explicitFalse_printsTheProjectAgain() {
        long ticket = deal(salesActor);
        DealQuotationDto created = quotationService.create(ticket, hidden(draft(), true), salesActor);

        DealQuotationDto updated = quotationService.update(created.id(), edit(false), salesActor);

        assertThat(updated.hideProjectName()).isFalse();
        assertThat(storedFlag(created.id())).isFalse();
        assertThat(sheetTexts(created.id())).anyMatch(text -> text.contains("Project") && text.contains(PROJECT));
    }

    @Test
    void update_explicitTrue_hidesAProjectThatUsedToPrint() {
        long ticket = deal(salesActor);
        DealQuotationDto created = quotationService.create(ticket, draft(), salesActor);

        DealQuotationDto updated = quotationService.update(created.id(), edit(true), salesActor);

        assertThat(updated.hideProjectName()).isTrue();
        assertThat(storedFlag(created.id())).isTrue();
        assertThat(updated.projectName()).isEqualTo(PROJECT);
        assertThat(storedProjectName(created.id())).isEqualTo(PROJECT);
    }

    /** An APPROVED document is frozen at what was approved. Trying to hide its project afterwards
     * is refused like any other edit, and what it prints does not change. */
    @Test
    void anApprovedDocument_cannotHaveItsProjectHiddenAfterwards() {
        DealQuotationDto approved = approved(deal(salesActor), salesActor);

        refusal(() -> quotationService.update(approved.id(), edit(true), salesActor), HttpStatus.CONFLICT);

        assertThat(storedFlag(approved.id())).isFalse();
        assertThat(sheetTexts(approved.id())).anyMatch(text -> text.contains(PROJECT));
    }

    // ── the document the service renders from the stored row ─────────────────────────────────

    /** Regression guard for the next test: this deal's ordinary document really does print the
     * line, so "no cell mentions it" cannot pass merely because nothing was ever printed. */
    @Test
    void renderedDocument_printsTheProjectLine_whenNotHidden() {
        DealQuotationDto created = quotationService.create(deal(salesActor), draft(), salesActor);

        assertThat(sheetTexts(created.id())).anyMatch(text -> text.contains("Project") && text.contains(PROJECT));
    }

    @Test
    void renderedDocument_hasNoProjectLine_whenHidden() {
        DealQuotationDto created = quotationService.create(deal(salesActor), hidden(draft(), true), salesActor);

        List<String> cells = sheetTexts(created.id());
        assertThat(cells).noneMatch(text -> text.contains(PROJECT));
        assertThat(cells).noneMatch(text -> text.contains("Project"));
    }

    /** Ticking the box on an existing draft changes the very next render — there is no cached
     * document to go stale. */
    @Test
    void renderedDocument_followsTheFlag_acrossAnUpdate() {
        long ticket = deal(salesActor);
        DealQuotationDto created = quotationService.create(ticket, draft(), salesActor);
        assertThat(sheetTexts(created.id())).anyMatch(text -> text.contains(PROJECT));

        quotationService.update(created.id(), edit(true), salesActor);
        assertThat(storedProjectName(created.id())).as("still stored — it is the flag that hides it").isEqualTo(PROJECT);
        assertThat(sheetTexts(created.id())).noneMatch(text -> text.contains(PROJECT));

        quotationService.update(created.id(), edit(false), salesActor);
        assertThat(sheetTexts(created.id())).anyMatch(text -> text.contains(PROJECT));
    }

    // ── copies ───────────────────────────────────────────────────────────────────────────────

    /** A re-issue of a document that hid its project must not print it: the revision and the
     * reorder clone both inherit the choice, exactly as they inherit every other header field. */
    @Test
    void revisionAndReorder_bothInheritAHiddenProject() {
        long ticket = deal(salesActor);
        DealQuotationDto created = quotationService.create(ticket, hidden(draft(), true), salesActor);
        quotationService.submit(created.id(), salesActor);
        DealQuotationDto approved = quotationService.approve(created.id(), new ApproveRequest(null), salesManagerActor);
        assertThat(approved.docStatus()).isEqualTo(QuotationStatus.APPROVED);
        assertThat(approved.hideProjectName()).as("approval itself never touches the flag").isTrue();

        DealQuotationDto reorder = quotationService.createReorder(approved.id(), salesActor);
        assertThat(reorder.hideProjectName()).isTrue();
        assertThat(sheetTexts(reorder.id())).noneMatch(text -> text.contains(PROJECT));

        DealQuotationDto revision = quotationService.createRevision(approved.id(), salesActor);
        assertThat(revision.hideProjectName()).isTrue();
        assertThat(sheetTexts(revision.id())).noneMatch(text -> text.contains(PROJECT));
    }

    /** …and the reverse: copying a document that prints its project never invents the flag. */
    @Test
    void revision_ofADocumentThatPrintsItsProject_stillPrintsIt() {
        DealQuotationDto approved = approved(deal(salesActor), salesActor);

        DealQuotationDto revision = quotationService.createRevision(approved.id(), salesActor);

        assertThat(revision.hideProjectName()).isFalse();
        assertThat(sheetTexts(revision.id())).anyMatch(text -> text.contains(PROJECT));
    }

    // ── the column ───────────────────────────────────────────────────────────────────────────

    /** V192.1's own promise: a row written by a path that knows nothing of the column — the legacy
     * customer-quotation engine, a pricing-request create, anything that predates it — reads as
     * "print the project", which is what every such document did before. */
    @Test
    void aRowThatNeverSetTheColumn_readsAsPrintTheProject() {
        Map<String, Object> column = jdbc.queryForMap("""
            SELECT is_nullable, column_default
              FROM information_schema.columns
             WHERE table_schema = 'sales' AND table_name = 'quotation' AND column_name = 'hide_project_name'
            """, Map.of());
        assertThat(column.get("is_nullable")).isEqualTo("NO");
        assertThat(String.valueOf(column.get("column_default"))).isEqualToIgnoringCase("false");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private static UpsertDealQuotationRequest hidden(UpsertDealQuotationRequest base, Boolean hideProjectName) {
        return base.withHideProjectName(hideProjectName);
    }

    /**
     * An UPDATE body, as the editor sends one: it re-states the project name on every save.
     *
     * <p>{@code draft()} names no project. That is right for a CREATE (the deal's own project is
     * used), but on UPDATE a null {@code projectName} is a real request to CLEAR the stored name
     * ({@code UpsertDealQuotationRequest#projectName}'s own Javadoc) — and a cleared name prints no
     * "Project : …" line either. Updating with {@code draft()} here would therefore make every
     * "the hidden project is not printed" assertion pass for the wrong reason, with the flag
     * playing no part. The first draft of this class did exactly that; this helper is the fix.
     *
     * @param hideProjectName {@code null} = the request does not mention the flag at all.
     */
    private static UpsertDealQuotationRequest edit(Boolean hideProjectName) {
        UpsertDealQuotationRequest base = draft();
        return new UpsertDealQuotationRequest(base.contactId(), base.deptCode(), base.unitCode(), base.offerDate(),
            base.depositPercent(), base.remainderMode(), base.creditDays(), base.validityDays(),
            base.validityMode(), base.validityUntil(), base.customerNotes(), base.priceMode(),
            base.documentLanguage(), base.currency(), base.printedByDisplayId(), base.salesRepDisplayId(),
            PROJECT, base.omitContactHonorific(), base.fullPaymentTerm(), base.orderedByName(),
            hideProjectName, base.items());
    }

    private boolean storedFlag(long quotationId) {
        return jdbc.queryForObject(
            "SELECT hide_project_name FROM sales.quotation WHERE quotation_id = :id",
            Map.of("id", quotationId), Boolean.class);
    }

    private String storedProjectName(long quotationId) {
        return jdbc.queryForObject(
            "SELECT project_name FROM sales.quotation WHERE quotation_id = :id",
            Map.of("id", quotationId), String.class);
    }

    /** Every text cell of the workbook the SERVICE renders for the stored quotation — the same
     * render model the PDF is printed from. */
    private List<String> sheetTexts(long quotationId) {
        byte[] xls = quotationService.renderXlsx(quotationId, salesActor);
        List<String> texts = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(xls))) {
            for (Sheet sheet : workbook) {
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        if (cell.getCellType() == CellType.STRING && !cell.getStringCellValue().isBlank()) {
                            texts.add(cell.getStringCellValue());
                        }
                    }
                }
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return texts;
    }
}
