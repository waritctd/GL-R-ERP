package th.co.glr.hr.importrequest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.importrequest.ImportRequestDtos.ImportRequestCoverageResponse;
import th.co.glr.hr.importrequest.ImportRequestDtos.ImportRequestSplitPreview;
import th.co.glr.hr.importrequest.ImportRequestDtos.SplitPreviewFactory;
import th.co.glr.hr.importrequest.ImportRequestRequests.ItemFactoryInput;

/**
 * Slice 6c: the coverage route's split preview — the dry run of {@link ImportRequestService#createDrafts}'s
 * per-factory split shown in the "ยืนยันออกใบขอซื้อ" confirm — against real Postgres through the real service.
 *
 * <p>Three claims, each pinned here: the preview says what the create then does (same factories, same order,
 * same item counts, same lead times); it writes nothing anywhere; and only a caller who may press the create
 * button (the CEO, the deal's owning or keying rep) gets it — import and sales_manager read the route for the
 * covered flag and get {@code null}, a non-owning rep and account are refused the route as before.
 */
class ImportRequestSplitPreviewIntegrationTest extends SplitFixtureSupport {

    private ImportRequestSplitPreview preview(RichDeal deal, UserPrincipal actor, List<ItemFactoryInput> picks) {
        ImportRequestSplitPreview preview = service.coverage(deal.ticketId(), picks, actor).splitPreview();
        assertThat(preview).as(actor.role() + " gets the split preview").isNotNull();
        return preview;
    }

    private static List<ItemFactoryInput> deltaPick(RichDeal deal) {
        return List.of(new ItemFactoryInput(deal.d1ItemId(), deal.deltaFactoryId()));
    }

    /** {@link #richDeal()} plus a live (APPROVED, empty) direct quotation: the only kind of deal whose confirm step
     * shows the factory picker (DealFulfilmentPanel's factorylessLines), so the only kind whose picks the preview
     * honours (fix round, L1). The quotation has no items, so it adds no quotation lead time. */
    private RichDeal directDeal() {
        RichDeal deal = richDeal();
        insertLiveDirectQuotation(deal.ticketId());
        return deal;
    }

    // ── the preview says what the create does ─────────────────────────────────────────────────

    @Test
    void preview_matchesWhatCreateThenIssues_factoryForFactory() {
        RichDeal deal = directDeal();
        ImportRequestSplitPreview preview = preview(deal, owner, deltaPick(deal));

        service.createDrafts(deal.ticketId(), fullRequest(deal), owner);

        // In creation order (ids ascend as createDrafts inserts), minus the row that already existed.
        List<Map<String, Object>> created = jdbc.queryForList("""
            SELECT r.factory_name, r.lead_time_min_days, r.lead_time_max_days, count(i.*) AS items
              FROM sales.import_request r JOIN sales.import_request_item i ON i.import_request_id = r.import_request_id
             WHERE r.ticket_id = :t AND r.factory_name <> 'Pin Echo'
             GROUP BY r.import_request_id ORDER BY r.import_request_id
            """, Map.of("t", deal.ticketId()));
        assertThat(created).extracting(r -> r.get("factory_name"))
            .containsExactlyElementsOf(preview.factories().stream().map(SplitPreviewFactory::factoryName).toList());
        for (int i = 0; i < created.size(); i++) {
            SplitPreviewFactory f = preview.factories().get(i);
            assertThat(((Number) created.get(i).get("items")).intValue()).as(f.factoryName()).isEqualTo(f.itemCount());
            if (f.leadTimeSource() != null) {
                assertThat(((Number) created.get(i).get("lead_time_min_days")).intValue()).as(f.factoryName())
                    .isEqualTo(f.leadTimeMinDays());
                assertThat(((Number) created.get(i).get("lead_time_max_days")).intValue()).as(f.factoryName())
                    .isEqualTo(f.leadTimeMaxDays());
            }
        }
    }

    // ── who sees it: exactly who may issue (wrong-way-round first) ──────────────────────────────

    @Test
    void wrongWayRound_importAndSalesManager_readTheCoveredFlag_butNeverThePreview() {
        RichDeal deal = directDeal();

        for (UserPrincipal reader : List.of(importUser, salesManager)) {
            ImportRequestCoverageResponse coverage = service.coverage(deal.ticketId(), deltaPick(deal), reader);
            assertThat(coverage.hasUncoveredFactories()).as(reader.role()).isTrue();
            assertThat(coverage.splitPreview()).as(reader.role() + " must not get the split preview").isNull();
        }
    }

    @Test
    void theCeoAndTheKeyingRep_getTheSamePreviewAsTheOwner() {
        RichDeal deal = directDeal();
        ImportRequestSplitPreview owners = preview(deal, owner, deltaPick(deal));
        jdbc.update("UPDATE sales.ticket SET keyed_by = :k WHERE ticket_id = :t",
            Map.of("k", otherSalesRep.id(), "t", deal.ticketId()));

        assertThat(owners).isNotNull();
        assertThat(preview(deal, ceoUser, deltaPick(deal))).isEqualTo(owners);
        assertThat(preview(deal, otherSalesRep, deltaPick(deal))).isEqualTo(owners);
    }
}
