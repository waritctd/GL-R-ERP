package th.co.glr.hr.importrequest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.importrequest.ImportRequestDtos.ImportRequestDto;

/**
 * Plain unit test (no DB) for {@link ImportRequestFormAssembler#fromStored(ImportRequestDto,
 * boolean)} — OWNER DECISION 09-19 #2, extended by REVIEW ROUND 3 item 5: the FACTORY copy of a
 * stored ใบขอซื้อ must not show the customer name ("สั่งมาให้"), the deposit-received date
 * ("วันที่ได้รับมัดจำ"), OR the project name — a project name often identifies the customer just
 * as surely as their name would. The INTERNAL copy (the pre-existing default, {@code
 * factoryCopy=false}) keeps all three. Every OTHER field is identical between the two copies —
 * pinned here so a future field addition to {@code ImportRequestDto} does not silently start
 * leaking through the factory copy without a test noticing.
 */
class ImportRequestFormAssemblerFactoryCopyTest {

    @Test
    void internalCopy_keepsCustomerNameDepositDateAndProjectName() {
        ImportRequestFormData data = ImportRequestFormAssembler.fromStored(sampleRow(), false);
        assertThat(data.customerName()).isEqualTo("บริษัท ลูกค้า จำกัด");
        assertThat(data.depositReceivedDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(data.projectName()).isEqualTo("โครงการตัวอย่าง");
    }

    @Test
    void factoryCopy_blanksCustomerNameDepositDateAndProjectName_keepsEverythingElse() {
        ImportRequestFormData internal = ImportRequestFormAssembler.fromStored(sampleRow(), false);
        ImportRequestFormData factory = ImportRequestFormAssembler.fromStored(sampleRow(), true);

        assertThat(factory.customerName()).isNull();
        assertThat(factory.depositReceivedDate()).isNull();
        assertThat(factory.projectName()).isNull(); // REVIEW ROUND 3, item 5

        // Everything else is IDENTICAL between the two copies.
        assertThat(factory.docNumber()).isEqualTo(internal.docNumber());
        assertThat(factory.brand()).isEqualTo(internal.brand()); // factory name (+ brand) header, S-B
        assertThat(factory.requestDate()).isEqualTo(internal.requestDate());
        assertThat(factory.requestedByName()).isEqualTo(internal.requestedByName());
        assertThat(factory.requiredByNote()).isEqualTo(internal.requiredByNote());
        assertThat(factory.vesselEtaNote()).isEqualTo(internal.vesselEtaNote());
        assertThat(factory.checkedByName()).isEqualTo(internal.checkedByName());
        assertThat(factory.checkedDate()).isEqualTo(internal.checkedDate());
        assertThat(factory.approvedByName()).isEqualTo(internal.approvedByName());
        assertThat(factory.approvedDate()).isEqualTo(internal.approvedDate());
        assertThat(factory.issuedByName()).isEqualTo(internal.issuedByName());
        assertThat(factory.lines()).isEqualTo(internal.lines());
    }

    /** Fictional data only (repo is public) — {@code sales.import_request}'s own snapshot shape. */
    private static ImportRequestDto sampleRow() {
        return new ImportRequestDto(
            1L, 10L, "TCK-1",
            "Living Ceramics", 5L, "Padana Sample Factory",
            1, ImportRequestStatus.ISSUED,
            "IR69068", LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 20),
            "บริษัท ลูกค้า จำกัด", "โครงการตัวอย่าง", "สมชาย ทดสอบ",
            "Within 21/5/26", LocalDate.of(2026, 8, 1),
            "ประมาณ 15/10/26 – 30/10/26 (75–90 วัน)",
            null, null, null, null,
            ImportRequestStep.CONTACTED, LocalDate.of(2026, 8, 20), 99L, "จินตนา นำเข้า", null,
            75, 90, LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 18),
            "sales@samplefactory.example", "Purchase order IR69068 - GL&R", "Dear team,\n...",
            null, null, null,
            2L, "เจ้าของดีล", 3L, "จินตนา นำเข้า", "jintana@glr.co.th",
            null, Instant.parse("2026-08-19T00:00:00Z"), Instant.parse("2026-08-20T00:00:00Z"),
            Instant.parse("2026-08-20T00:00:00Z"), 1,
            List.of());
    }
}
