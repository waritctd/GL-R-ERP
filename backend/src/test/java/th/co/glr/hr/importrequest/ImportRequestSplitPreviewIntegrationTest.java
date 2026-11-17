package th.co.glr.hr.importrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.importrequest.ImportRequestDtos.ImportRequestCoverageResponse;
import th.co.glr.hr.importrequest.ImportRequestDtos.ImportRequestSplitPreview;
import th.co.glr.hr.importrequest.ImportRequestDtos.SplitPreviewFactory;
import th.co.glr.hr.importrequest.ImportRequestDtos.SplitPreviewLine;
import th.co.glr.hr.importrequest.ImportRequestRequests.CreateImportRequestsRequest;
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

    private static final String QUOTATION = SplitPreviewFactory.LEAD_TIME_FROM_QUOTATION;
    private static final String COUNTRY = SplitPreviewFactory.LEAD_TIME_COUNTRY_DEFAULT;

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

    // ── fix round L1: the picks are only what the picker could have offered ─────────────────────

    /** On a deal with no live direct quotation the confirm shows no picker, so a hand-crafted pick there — even to a
     * factory that has a country default — is ignored: the preview is byte-for-byte the no-pick preview and names
     * no factory and no lead time for it. */
    @Test
    void l1_aPickOnADealWhoseConfirmShowsNoPicker_isIgnored_andRevealsNoLeadTime() {
        RichDeal deal = richDeal(); // no direct quotation
        long hidden = insertFactory("Pin Hidden CN", "CN"); // 30–45 if it ever leaked

        ImportRequestSplitPreview withPick = preview(deal, owner,
            List.of(new ItemFactoryInput(deal.d1ItemId(), hidden)));

        assertThat(withPick).isEqualTo(preview(deal, owner, List.of()));
        assertThat(withPick.factories()).extracting(SplitPreviewFactory::factoryName).doesNotContain("Pin Hidden CN");
        assertThat(withPick.linesWithoutFactory()).extracting(SplitPreviewLine::ticketItemId)
            .containsExactly(deal.d1ItemId());
    }

    /** A factory id the picker cannot offer (it lists the factory master — an id not in it) is ignored the same way. */
    @Test
    void l1_aPickForAFactoryIdOutsideTheOfferedSet_isIgnored() {
        RichDeal deal = directDeal();
        long notInMaster = jdbc.queryForObject("SELECT COALESCE(max(factory_id), 0) + 1000 FROM price_catalog.factories",
            Map.of(), Long.class);

        ImportRequestSplitPreview withPick = preview(deal, owner,
            List.of(new ItemFactoryInput(deal.d1ItemId(), notInMaster)));

        assertThat(withPick).isEqualTo(preview(deal, owner, List.of()));
    }

    // ── fix round L4: the create's 409 names the FIRST unplaced line ────────────────────────────

    @Test
    void l4_createDraftsRefusal_namesTheFirstUnplacedLineInDealOrder() {
        long ticket = insertTicket("SPLIT-L4");
        insertFactory("Pin L4 Factory", "IT");
        insertItem(ticket, "Pin L4 Factory", "Placed", "60x60 cm", "5", "0", 0, null);
        insertItem(ticket, null, "First Unplaced", "10x10 cm", "1", "0", 1, null);
        insertItem(ticket, null, "Second Unplaced", "20x20 cm", "1", "0", 2, null);

        assertThatThrownBy(() -> service.createDrafts(ticket, null, owner))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).endsWith(": First Unplaced (10x10 cm)").doesNotContain("Second Unplaced");
            });
    }

    // ── the preview says what the create does ─────────────────────────────────────────────────

    @Test
    void preview_listsEachFactoryCreateWouldIssue_inCreateOrder_withItemCountsLeadTimesAndTheirSource() {
        RichDeal deal = directDeal();

        ImportRequestSplitPreview preview = preview(deal, owner, deltaPick(deal));

        // Echo already has a live IR (skipped); S1 is fully from stock (never ordered); B2's typed "pin beta" joins
        // the catalogue-linked Pin Beta; C1/C2 are one new factory; the picked D1 lands on Pin Delta (PL: no default).
        assertThat(preview.factories()).containsExactly(
            new SplitPreviewFactory("Pin Alpha", false, 2, 100, 120, QUOTATION),
            new SplitPreviewFactory("Pin Beta", false, 2, 30, 45, COUNTRY),
            new SplitPreviewFactory("Pin Delta", false, 1, null, null, null),
            new SplitPreviewFactory("Pin New Co", true, 2, null, null, null));
        assertThat(preview.linesWithoutFactory()).isEmpty();
    }

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

    @Test
    void preview_withoutThePick_namesTheFactorylessLine_andCreateRefusesThatSameLine() {
        RichDeal deal = directDeal();

        ImportRequestSplitPreview preview = preview(deal, owner, List.of());

        assertThat(preview.linesWithoutFactory())
            .containsExactly(new SplitPreviewLine(deal.d1ItemId(), "Free Text One", "10x10 cm"));
        assertThat(preview.factories()).extracting(SplitPreviewFactory::factoryName)
            .containsExactly("Pin Alpha", "Pin Beta", "Pin New Co");
        CreateImportRequestsRequest noPick = new CreateImportRequestsRequest(null,
            fullRequest(deal).newFactoryCountries(), null, fullRequest(deal).leadTimes());
        assertThatThrownBy(() -> service.createDrafts(deal.ticketId(), noPick, owner))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).contains("Free Text One");
            });
    }

    @Test
    void preview_aFactoryCoveredOnlyBySupersededRowsIsBackInIt() {
        RichDeal deal = directDeal();
        jdbc.update("UPDATE sales.import_request SET status = 'SUPERSEDED' WHERE ticket_id = :t",
            Map.of("t", deal.ticketId()));

        assertThat(preview(deal, owner, deltaPick(deal)).factories()).first()
            .isEqualTo(new SplitPreviewFactory("Pin Echo", false, 1, 75, 90, COUNTRY));
    }

    @Test
    void preview_ignoresAPickItCannotApply_ratherThanFailingTheCoverageRead() {
        RichDeal deal = directDeal();
        long betaTwo = jdbc.queryForObject("SELECT item_id FROM sales.ticket_item WHERE model = 'Beta Two'",
            Map.of(), Long.class);

        ImportRequestSplitPreview preview = preview(deal, owner, List.of(
            new ItemFactoryInput(deal.d1ItemId(), 987_654L), // no such factory: createDrafts would 400
            new ItemFactoryInput(betaTwo, deal.deltaFactoryId()), // line already has a factory: never overwritten
            new ItemFactoryInput(987_654L, deal.deltaFactoryId()))); // not a line of this deal

        assertThat(preview.linesWithoutFactory()).extracting(SplitPreviewLine::ticketItemId)
            .containsExactly(deal.d1ItemId());
        assertThat(preview.factories()).extracting(SplitPreviewFactory::factoryName, SplitPreviewFactory::itemCount)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("Pin Alpha", 2),
                org.assertj.core.groups.Tuple.tuple("Pin Beta", 2),
                org.assertj.core.groups.Tuple.tuple("Pin New Co", 2));
    }

    // ── the preview writes nothing ────────────────────────────────────────────────────────────

    @Test
    void preview_writesNothingAnywhere_evenWithANewFactoryNameAndAPickInPlay() {
        RichDeal deal = directDeal();
        Map<String, String> before = databaseFingerprint();

        for (UserPrincipal actor : List.of(owner, ceoUser, importUser, salesManager)) {
            service.coverage(deal.ticketId(), deltaPick(deal), actor);
        }

        assertThat(databaseFingerprint()).isEqualTo(before);
        // The two writes createDrafts makes before it issues anything, named explicitly.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM price_catalog.factories WHERE name ILIKE 'pin new%'",
            Map.of(), Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT factory FROM sales.ticket_item WHERE item_id = :i",
            Map.of("i", deal.d1ItemId()), String.class)).isNull();
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
    void wrongWayRound_aNonOwningRepAndAccount_areRefusedTheRouteItself() {
        RichDeal deal = directDeal();

        for (UserPrincipal outsider : List.of(otherSalesRep, accountUser)) {
            assertThatThrownBy(() -> service.coverage(deal.ticketId(), deltaPick(deal), outsider))
                .as(outsider.role())
                .isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
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

    // ── the route: itemFactory pairs ──────────────────────────────────────────────────────────

    @Test
    void route_passesItemFactoryPairsToThePreview_andRefusesAMalformedPair() {
        RichDeal deal = directDeal();
        SessionContext sessions = mock(SessionContext.class);
        MockHttpSession session = new MockHttpSession();
        when(sessions.requireUser(session)).thenReturn(owner);
        ImportRequestController controller = new ImportRequestController(service, sessions);

        ImportRequestCoverageResponse withPick = controller.coverage(deal.ticketId(),
            List.of(deal.d1ItemId() + ":" + deal.deltaFactoryId()), session);
        assertThat(withPick.splitPreview()).isNotNull();
        assertThat(withPick.splitPreview().linesWithoutFactory()).isEmpty();
        assertThat(withPick.splitPreview().factories()).extracting(SplitPreviewFactory::factoryName)
            .contains("Pin Delta");

        for (String bad : List.of("12", "a:b", "12:", ":3", "1:2:3")) {
            assertThatThrownBy(() -> controller.coverage(deal.ticketId(), List.of(bad), session))
                .as(bad)
                .isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
    }
}
