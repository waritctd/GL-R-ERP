package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.support.WorkbookTexts;
import th.co.glr.hr.ticket.QuotationRenderModel;
import th.co.glr.hr.ticket.QuotationRenderer;

/**
 * Sales feedback #5 (2026-10-06, V192.1) — "ไม่แสดงชื่อโครงการในใบเสนอราคา".
 *
 * <p>Sales asked for โครงการ to be optional. The ruling was that it stays REQUIRED on the deal
 * ("กรณียังไม่ทราบชัดเจน เป็นชื่อกลางๆได้ เช่น บ้านเอกมัย ซอย 5") and the rep decides, per
 * document, whether it PRINTS ("สร้างทางเลือก แสดง หรือไม่แสดง").
 *
 * <p>Pure render-layer coverage — no Spring, no DB. Three levels on purpose, because a decision can
 * be right at one and still not reach the next:
 * <ol>
 *   <li>the adapter: does {@link DealQuotationRenderAdapter#toRenderModel} hand the renderer a
 *       project name at all?</li>
 *   <li>the workbook: does any cell of the sheet {@link QuotationRenderer#toXls} writes mention
 *       the project — including text the TEMPLATE might already carry in that cell?</li>
 *   <li>the HTML: {@link QuotationRenderer#toHtml} is what headless Chromium prints the PDF from,
 *       so this is the customer's copy.</li>
 * </ol>
 * The real-DB round trip (the flag stored by the service is the flag the service's own renderer
 * reads) is {@code DealQuotationHideProjectNameIntegrationTest}.
 */
class DealQuotationHideProjectNameRenderTest {

    private static final String PROJECT = "บ้านเอกมัย ซอย 5";
    private static final String PROJECT_LABEL = "Project";

    // ── 1. adapter ───────────────────────────────────────────────────────────────────────────

    @Test
    void byDefault_theRenderModelCarriesTheProjectName() {
        QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(quotation(false, null), null, null);
        assertThat(model.projectName()).isEqualTo(PROJECT);
    }

    @Test
    void hidden_theRenderModelCarriesNoProjectName() {
        QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(quotation(true, null), null, null);
        assertThat(model.projectName()).isNull();
    }

    /** "Hide" must not mean "delete": the DTO — what the editor, the register and the deal read —
     * still names the project. Only what is handed to the RENDERER loses it. */
    @Test
    void hidden_theQuotationItselfStillNamesItsProject() {
        DealQuotationDto hidden = quotation(true, null);
        assertThat(hidden.hideProjectName()).isTrue();
        assertThat(hidden.projectName()).isEqualTo(PROJECT);
    }

    /** The flag touches ONE line. Everything else the adapter builds — the เรียน line, the
     * address/phone line, the remarks, the signature block's names — is identical either way. */
    @Test
    void hiding_changesNothingElseOnTheDocument() {
        QuotationRenderModel shown = DealQuotationRenderAdapter.toRenderModel(quotation(false, null), null, null);
        QuotationRenderModel hidden = DealQuotationRenderAdapter.toRenderModel(quotation(true, null), null, null);
        assertThat(hidden.attnLine()).isEqualTo(shown.attnLine());
        assertThat(hidden.phoneLine()).isEqualTo(shown.phoneLine());
        assertThat(hidden.salesLine()).isEqualTo(shown.salesLine());
        assertThat(hidden.number()).isEqualTo(shown.number());
        assertThat(hidden.deptCode()).isEqualTo(shown.deptCode());
        assertThat(hidden.unitCode()).isEqualTo(shown.unitCode());
        assertThat(hidden.remarkLines()).isEqualTo(shown.remarkLines());
        assertThat(hidden.items()).isEqualTo(shown.items());
    }

    // ── 2. workbook ──────────────────────────────────────────────────────────────────────────

    /** Regression guard for the pair below: the shown document really does print the line these
     * tests look for, so "no cell mentions it" cannot pass merely because the fixture never
     * printed one. */
    @Test
    void shown_theSheetPrintsTheProjectLine() throws Exception {
        List<String> cells = sheetTexts(quotation(false, null));
        assertThat(cells).anyMatch(text -> text.contains(PROJECT_LABEL) && text.contains(PROJECT));
    }

    /** Wrong-way-round, and deliberately broader than "the name is absent": no cell may carry the
     * "Project" label either, so a hidden project does not print as a dangling "Project  : " with
     * nothing after it — including any placeholder the template itself might hold in that cell. */
    @Test
    void hidden_noCellOfTheSheetMentionsTheProject() throws Exception {
        List<String> cells = sheetTexts(quotation(true, null));
        assertThat(cells).noneMatch(text -> text.contains(PROJECT));
        assertThat(cells).noneMatch(text -> text.contains(PROJECT_LABEL));
    }

    /**
     * What this feature must NOT change. {@code quotation_template.xls} carries the literal label
     * "Project  : " in its project cell, so a document whose name is merely BLANK has always
     * printed that bare label. Documents are rendered fresh from the database on every request —
     * an already-approved one included — so "blank the cell whenever there is no name" would
     * quietly alter documents this feature has nothing to do with. Only the tick box blanks it.
     */
    @Test
    void aMerelyBlankProjectName_stillPrintsTheTemplatesBareLabel_exactlyAsBefore() throws Exception {
        List<String> cells = sheetTexts(quotation(false, null, null));
        assertThat(cells).anyMatch(text -> text.strip().equals("Project  :"));
    }

    @Test
    void hidden_englishDocument_hasNoProjectLineEither() throws Exception {
        List<String> shown = sheetTexts(quotation(false, WastageCalculator.DOCUMENT_LANGUAGE_EN));
        List<String> hidden = sheetTexts(quotation(true, WastageCalculator.DOCUMENT_LANGUAGE_EN));
        assertThat(shown).anyMatch(text -> text.contains(PROJECT));
        assertThat(hidden).noneMatch(text -> text.contains(PROJECT));
    }

    // ── 3. the HTML the PDF is printed from ──────────────────────────────────────────────────

    @Test
    void shown_theHtmlThePdfIsPrintedFromNamesTheProject() {
        String html = new QuotationRenderer().toHtml(
            DealQuotationRenderAdapter.toRenderModel(quotation(false, null), null, null));
        assertThat(html).contains(PROJECT);
    }

    @Test
    void hidden_theHtmlThePdfIsPrintedFromDoesNotNameTheProject() {
        String html = new QuotationRenderer().toHtml(
            DealQuotationRenderAdapter.toRenderModel(quotation(true, null), null, null));
        assertThat(html).doesNotContain(PROJECT);
    }

    // ── the flag survives every copy of the DTO ──────────────────────────────────────────────

    /** {@link DealQuotationDto} is rebuilt field-by-field by its copy methods, and this record's
     * older constructor shapes default any field they do not know about. A copy that silently went
     * through one of those would turn a hidden project back into a printed one with no error
     * anywhere — which is exactly what {@code withRemovedCeoItems} (every PRICING_REQUEST read)
     * would do on a real request. */
    @Test
    void everyCopyOfTheQuotationKeepsTheFlag() {
        DealQuotationDto hidden = quotation(true, null);
        assertThat(hidden.withRemovedCeoItems(List.of()).hideProjectName()).isTrue();
        assertThat(hidden.withOrderedByName("คุณวิชัย มั่นคง").hideProjectName()).isTrue();
        // …and the reverse: none of them invents the flag on a document that prints its project.
        DealQuotationDto shown = quotation(false, null);
        assertThat(shown.withRemovedCeoItems(List.of()).hideProjectName()).isFalse();
        assertThat(shown.withOrderedByName("คุณวิชัย มั่นคง").hideProjectName()).isFalse();
    }

    /** Setting the flag is itself a copy — it must leave every other field alone. */
    @Test
    void withHideProjectName_changesOnlyThatField() {
        DealQuotationDto base = quotation(false, null);
        DealQuotationDto hidden = base.withHideProjectName(true);
        assertThat(hidden.hideProjectName()).isTrue();
        assertThat(hidden.withHideProjectName(false)).isEqualTo(base);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────

    /** A DRAFT with no item lines — the header block is all these tests read. Built through one of
     * the record's older constructor shapes (which know nothing of this field) plus the wither,
     * the same device the V180 tests in {@code DealQuotationRenderAdapterV3Test} use. */
    private static DealQuotationDto quotation(boolean hideProjectName, String documentLanguage) {
        return quotation(hideProjectName, documentLanguage, PROJECT);
    }

    private static DealQuotationDto quotation(boolean hideProjectName, String documentLanguage, String projectName) {
        return new DealQuotationDto(1L, "QT-2026-0001", 1L, "DRAFT", 1, null,
            1L, "ผู้พิมพ์", null, 1L, "พนักงานขาย", null, "081-000-0000",
            null, null, null, null, null, null,
            LocalDate.of(2026, 10, 6), "บริษัท ทดสอบ จำกัด", "99 ถนนทดสอบ กรุงเทพมหานคร 10110", null, "02-000-0000",
            9L, "สมชาย ใจดี", null, null, projectName,
            "P003", "D002", LocalDate.of(2026, 10, 6), 30, "CREDIT", 30, 30, null, null,
            WastageCalculator.PRICE_MODE_NET,
            documentLanguage != null ? documentLanguage : WastageCalculator.DOCUMENT_LANGUAGE_TH,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            WastageCalculator.defaultCurrencyFor(documentLanguage),
            false, List.of(), Instant.parse("2026-10-06T00:00:00Z"), null)
            .withHideProjectName(hideProjectName);
    }

    /** Every text cell of every sheet the XLS engine wrote for this quotation. */
    private static List<String> sheetTexts(DealQuotationDto quotation) throws Exception {
        QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(quotation, null, null);
        return WorkbookTexts.of(new QuotationRenderer().toXls(model));
    }
}
