package th.co.glr.hr.deposit;

import static org.assertj.core.api.Assertions.assertThat;
import static th.co.glr.hr.deposit.PdfRendererTestSupport.*;

import java.util.List;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.junit.jupiter.api.Test;

/**
 * The repo is PUBLIC: the committed deposit/remaining templates must be a clean shell -- exactly one
 * sheet ("Update") and none of the filled-in example cells the owner's sample files carry (the
 * deposit sample's Sheet1 is a real customer list). Pins the cells the design names (0-based).
 */
class PdfTemplateHygieneTest {
    private static final String DEPOSIT = "/templates/deposit_notice_template.xls";
    private static final String REMAINING = "/templates/remaining_invoice_template.xls";
    private static final String CUSTOMER_SHELL = "บริษัท / เลขประจำตัวผู้เสียภาษี :";

    @Test
    void depositTemplateIsOneUpdateSheetWithNoExampleData() throws Exception {
        try (Workbook wb = openTemplate(DEPOSIT)) {
            assertOneUpdateSheet(wb);
            Sheet sh = wb.getSheet("Update");
            assertThat(norm(str(sh, 6, 1))).as("B7 customer line is the empty shell").isEqualTo(CUSTOMER_SHELL);
            assertThat(norm(str(sh, 10, 1))).as("B11 project is the empty shell").isEqualTo("Project :");
            assertBlank(sh, "I8 doc no", 7, 8);
            assertBlank(sh, "I9 reference", 8, 8);
            assertItemExampleCellsBlank(sh);
        }
    }

    @Test
    void remainingTemplateIsOneUpdateSheetWithNoExampleData() throws Exception {
        try (Workbook wb = openTemplate(REMAINING)) {
            assertOneUpdateSheet(wb);
            Sheet sh = wb.getSheet("Update");
            assertThat(norm(str(sh, 6, 1))).as("B7 customer line is the empty shell").isEqualTo(CUSTOMER_SHELL);
            assertThat(norm(str(sh, 10, 1))).as("B11 project is the empty shell").isEqualTo("Project :");
            assertBlank(sh, "H8 doc no", 7, 7);
            assertBlank(sh, "H9 reference", 8, 7);
            assertItemExampleCellsBlank(sh);
            for (int c : new int[] {1, 2, 4}) assertBlank(sh, "row 15 example deposit row", 14, c);   // B15 C15 E15
        }
    }

    private static void assertOneUpdateSheet(Workbook wb) {
        List<String> names = new java.util.ArrayList<>();
        for (int i = 0; i < wb.getNumberOfSheets(); i++) names.add(wb.getSheetName(i));
        assertThat(names).as("sheets in the committed template").containsExactly("Update");
    }

    /** Row 13 (0-based 12): B C E G and the J helper hold the owner's example item in the sample files. */
    private static void assertItemExampleCellsBlank(Sheet sh) {
        for (int c : new int[] {1, 2, 4, 6, 9}) assertBlank(sh, "row 13 example item", 12, c);
    }

    private static void assertBlank(Sheet sh, String what, int r, int c) {
        assertThat(isBlank(sh, r, c)).as("%s (%d,%d) must be blank", what, r, c).isTrue();
    }

    // The sample files carry screenshots of real customer documents as unused pictures, the author names, the
    // printer records and the old sheet names. Only the logo and the signature belong in the committed shell.
    @Test
    void depositTemplateCarriesNoLeftoverMetadata() throws Exception { assertNoLeftovers(DEPOSIT); }

    @Test
    void remainingTemplateCarriesNoLeftoverMetadata() throws Exception { assertNoLeftovers(REMAINING); }

    private static void assertNoLeftovers(String resource) throws Exception {
        try (Workbook wb = openTemplate(resource)) {
            assertThat(((HSSFWorkbook) wb).getAllPictures()).as("pictures in the workbook: logo + signature").hasSize(2);
            var summary = ((HSSFWorkbook) wb).getSummaryInformation();
            if (summary != null) {
                assertThat(summary.getAuthor()).as("author").isNullOrEmpty();
                assertThat(summary.getLastAuthor()).as("last author").isNullOrEmpty();
            }
            Sheet sh = wb.getSheet("Update");
            assertRowsVisible(sh, 0, sh.getLastRowNum());
        }
        var streams = oleStreams(resource);
        assertThat(biffRecordIds(streams.get("Workbook"))).as("no printer-settings (PLS) record").doesNotContain(0x004D);
        for (var e : streams.entrySet())
            assertThat(containsText(e.getValue(), "Sheet1")).as("'Sheet1' left in stream %s", e.getKey()).isFalse();
    }

    /** The committed rebuild's pictures: the signature is cropped (right 35499/65536, bottom 5861, top 1) and
     * both pictures keep the anchors the rebuild copied from the source template. */
    @Test
    void depositPicturesKeepTheOwnersCropAndAnchors() throws Exception {
        assertPictures(DEPOSIT, new int[] {0, 1, 149, 37, 1, 3, 234, 163}, new int[] {1, 45, 164, 61, 1, 46, 297, 123});
    }

    @Test
    void remainingPicturesKeepTheOwnersCropAndAnchors() throws Exception {
        assertPictures(REMAINING, new int[] {0, 0, 178, 171, 1, 3, 238, 57}, new int[] {1, 42, 177, 51, 1, 43, 312, 113});
    }

    private static void assertPictures(String resource, int[] logo, int[] signature) throws Exception {
        try (Workbook wb = openTemplate(resource)) {
            var pics = pictures(wb.getSheet("Update"));
            assertThat(pics).hasSize(2);
            assertThat(anchor(pics.get(0))).as("logo anchor").containsExactly(logo);
            assertThat(anchor(pics.get(1))).as("signature anchor").containsExactly(signature);
            var sig = pictureProps(pics.get(1));
            assertThat(sig.get(256)).as("crop from top").isEqualTo(1);
            assertThat(sig.get(257)).as("crop from bottom").isEqualTo(5861);
            assertThat(sig.get(259)).as("crop from right").isEqualTo(35499);
            assertThat(pictureProps(pics.get(0))).as("the logo is not cropped").doesNotContainKeys(256, 257, 258, 259);
        }
    }

    /** No author/user name of the person who built the file survives in any stream, including WRITEACCESS. */
    @Test
    void noUserNameSurvivesInEitherTemplate() throws Exception {
        for (String resource : List.of(DEPOSIT, REMAINING)) {
            var streams = oleStreams(resource);
            for (var e : streams.entrySet())
                assertThat(containsText(e.getValue(), "ploy")).as("'ploy' in %s of %s", e.getKey(), resource).isFalse();
            assertThat(writeAccessUser(streams.get("Workbook"))).as("WRITEACCESS user of " + resource).isIn("", "GLR");
        }
    }
}
