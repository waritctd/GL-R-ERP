package th.co.glr.hr.importrequest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.importrequest.ImportRequestDtos.ImportRequestSplitPreview;
import th.co.glr.hr.importrequest.ImportRequestDtos.SplitPreviewFactory;
import th.co.glr.hr.importrequest.ImportRequestRequests.CreateImportRequestsRequest;
import th.co.glr.hr.importrequest.ImportRequestRequests.ItemFactoryInput;
import th.co.glr.hr.importrequest.ImportRequestRequests.NewFactoryCountryInput;
import th.co.glr.hr.importrequest.ImportRequestRequests.NewFactoryLeadTimeInput;

/**
 * Slice 6c fix round (review L3): the split preview and the issue that follows AGREE — same factories, same order,
 * same item counts, same lead time wherever the preview states one — on the fixtures the first round skipped. Adapted
 * from the adversarial reviewer's demonstration. Real Postgres, real service.
 *
 * <p>Several run with factory ids moved above 5000 ({@link #factoryIdsAbove127()}): a boxed {@code Long} compared
 * with {@code ==} is only equal inside the JVM's small-value cache (-128..127), so the direct-route lead-time match in
 * {@code ImportRequestService#leadTimeFromQuotation} must use {@code equals} — a regression to {@code ==} turns
 * {@link #directRoute_quotationLeadTime_withFactoryIdsAbove127_agrees()} red.
 */
class ImportRequestSplitAgreementIntegrationTest extends SplitFixtureSupport {

    private void factoryIdsAbove127() {
        jdbc.queryForObject("SELECT setval(pg_get_serial_sequence('price_catalog.factories', 'factory_id'), 5000)",
            Map.of(), Long.class);
    }

    /** A live direct quotation: the deal kind whose confirm shows the factory picker, so whose picks count. */
    private void withPicker(long ticket) {
        insertLiveDirectQuotation(ticket);
    }

    /** Preview, then createDrafts; the rows it creates (id order, new rows only) must read exactly as the preview. */
    private ImportRequestSplitPreview assertAgreement(long ticket, List<ItemFactoryInput> picks,
                                                      CreateImportRequestsRequest request) {
        ImportRequestSplitPreview preview = service.coverage(ticket, picks, owner).splitPreview();
        assertThat(preview).isNotNull();
        long before = jdbc.queryForObject("SELECT COALESCE(max(import_request_id), 0) FROM sales.import_request",
            Map.of(), Long.class);

        service.createDrafts(ticket, request, owner);

        List<Map<String, Object>> created = jdbc.queryForList("""
            SELECT r.factory_name, r.lead_time_min_days, r.lead_time_max_days,
                   (SELECT count(*) FROM sales.import_request_item i WHERE i.import_request_id = r.import_request_id) AS n
              FROM sales.import_request r
             WHERE r.ticket_id = :t AND r.import_request_id > :before
             ORDER BY r.import_request_id
            """, Map.of("t", ticket, "before", before));
        List<String> expected = new ArrayList<>();
        List<String> actual = new ArrayList<>();
        for (int i = 0; i < preview.factories().size(); i++) {
            SplitPreviewFactory f = preview.factories().get(i);
            boolean stated = f.leadTimeMinDays() != null;
            expected.add(f.factoryName() + " | " + f.itemCount() + " | "
                + (stated ? f.leadTimeMinDays() + "-" + f.leadTimeMaxDays() : "asked"));
            if (i < created.size()) {
                Map<String, Object> r = created.get(i);
                actual.add(r.get("factory_name") + " | " + ((Number) r.get("n")).intValue() + " | "
                    + (stated ? r.get("lead_time_min_days") + "-" + r.get("lead_time_max_days") : "asked"));
            }
        }
        assertThat(created).hasSameSizeAs(preview.factories());
        assertThat(actual).isEqualTo(expected);
        return preview;
    }

    @Test
    void directRoute_quotationLeadTime_withFactoryIdsAbove127_agrees() {
        factoryIdsAbove127();
        long price = insertCatalogProduct("Agree Direct", "CN", "AGR-D", new BigDecimal("10"), "THB", "per_piece");
        long ticket = insertTicket("AGREE-DIR");
        insertItem(ticket, null, "Direct One", "60x60 cm", "5", "0", 0, price);
        long q = insertQuotation(ticket, "DEAL_DIRECT", "APPROVED", null);
        insertQuotationItem(q, null, price, 50, 60); // beats CN's 30–45 only if the factory id matches

        ImportRequestSplitPreview preview = assertAgreement(ticket, List.of(), null);

        assertThat(preview.factories()).containsExactly(new SplitPreviewFactory("Agree Direct", false, 1, 50, 60,
            SplitPreviewFactory.LEAD_TIME_FROM_QUOTATION));
    }

    @Test
    void aPickToAFactoryWhoseNameNormalizesLikeAnother_agrees() {
        factoryIdsAbove127();
        insertFactory("Dup Co", "CN");
        long lower = insertFactory("dup co", "IT");
        long ticket = insertTicket("AGREE-DUP");
        withPicker(ticket);
        long line = insertItem(ticket, null, "Free", "10x10 cm", "3", "0", 0, null);
        List<ItemFactoryInput> picks = List.of(new ItemFactoryInput(line, lower));

        // Both paths send the pick to the FIRST master row with that normalized name (pre-existing behaviour).
        assertAgreement(ticket, picks, new CreateImportRequestsRequest(null, null, picks, null));
    }

    @Test
    void aPickToAFactoryWithAPaddedMasterName_agrees() {
        long padded = insertFactory("  Padded Co  ", "IT");
        long ticket = insertTicket("AGREE-PAD");
        withPicker(ticket);
        long line = insertItem(ticket, null, "Free", "10x10 cm", "3", "0", 0, null);
        List<ItemFactoryInput> picks = List.of(new ItemFactoryInput(line, padded));

        assertAgreement(ticket, picks, new CreateImportRequestsRequest(null, null, picks, null));
    }

    @Test
    void twoPicksForOneLine_theFirstWins_inBoth() {
        long first = insertFactory("Agree First", "IT");
        long second = insertFactory("Agree Second", "CN");
        long ticket = insertTicket("AGREE-TWO");
        withPicker(ticket);
        long line = insertItem(ticket, null, "Free", "10x10 cm", "3", "0", 0, null);
        List<ItemFactoryInput> picks = List.of(new ItemFactoryInput(line, first), new ItemFactoryInput(line, second));

        ImportRequestSplitPreview preview =
            assertAgreement(ticket, picks, new CreateImportRequestsRequest(null, null, picks, null));
        assertThat(preview.factories()).extracting(SplitPreviewFactory::factoryName).containsExactly("Agree First");
    }

    @Test
    void thaiTypedNames_mergeOnce_andNewFactoriesFollowTheExistingOne_inBoth() {
        insertFactory("Agree Exist", "CN");
        long ticket = insertTicket("AGREE-THAI");
        insertItem(ticket, " โรงงานใหม่ ", "T1", "10x10 cm", "3", "0", 0, null);
        insertItem(ticket, "Agree Exist", "T2", "10x10 cm", "3", "0", 1, null);
        insertItem(ticket, "โรงงานใหม่", "T3", "10x10 cm", "3", "0", 2, null);
        insertItem(ticket, "Zeta New", "T4", "10x10 cm", "3", "0", 3, null);

        ImportRequestSplitPreview preview = assertAgreement(ticket, List.of(), new CreateImportRequestsRequest(null,
            List.of(new NewFactoryCountryInput("โรงงานใหม่", "CN", null), new NewFactoryCountryInput("Zeta New", "IT", null)),
            null, null));
        assertThat(preview.factories()).extracting(SplitPreviewFactory::factoryName)
            .containsExactly("Agree Exist", "โรงงานใหม่", "Zeta New");
    }

    /** INFO (i) in the review: a new factory's country is unknown at preview time, so the preview states no lead
     * time and says it will be asked; the create then takes that country's default once the country is chosen. */
    @Test
    void aNewFactoryInACountryWithADefault_previewStatesNone_createTakesTheDefault() {
        long ticket = insertTicket("AGREE-NEWLEAD");
        insertItem(ticket, "Brand New Agree Co", "N1", "10x10 cm", "3", "0", 0, null);

        ImportRequestSplitPreview preview = assertAgreement(ticket, List.of(), new CreateImportRequestsRequest(null,
            List.of(new NewFactoryCountryInput("Brand New Agree Co", "CN", null)), null,
            List.of(new NewFactoryLeadTimeInput("Brand New Agree Co", 1, 2))));

        assertThat(preview.factories()).containsExactly(
            new SplitPreviewFactory("Brand New Agree Co", true, 1, null, null, null));
        assertThat(jdbc.queryForMap("""
            SELECT lead_time_min_days AS lo, lead_time_max_days AS hi FROM sales.import_request WHERE ticket_id = :t
            """, Map.of("t", ticket))).containsEntry("lo", 30).containsEntry("hi", 45);
    }
}
