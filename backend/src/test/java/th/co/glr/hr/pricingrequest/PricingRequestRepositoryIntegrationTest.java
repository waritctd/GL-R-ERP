package th.co.glr.hr.pricingrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Year;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestEventDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestItemDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.CreatePricingRequestRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.PricingRequestItemRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.UpdatePricingRequestRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * Exercises PricingRequestRepository's create/transition/event SQL against a real
 * PostgreSQL database — Mockito cannot cover the CHECK constraints or the
 * compare-and-set transition() semantics this repository relies on.
 */
class PricingRequestRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {
    private PricingRequestRepository requests;
    private TicketRepository tickets;
    private EmployeeRepository employees;
    private long salesActorId;
    private long ticketId;
    private int clientRequestSeq;

    @BeforeEach
    void wireRepositories() {
        requests = new PricingRequestRepository(jdbc);
        tickets = new TicketRepository(jdbc);
        employees = new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        clientRequestSeq = 1000;

        salesActorId = createEmployee("พนักงานขาย ทดสอบ", "sales@glr.co.th");
        ticketId = tickets.create(sampleTicket(), tickets.nextTicketCode(), salesActorId, "พนักงานขาย ทดสอบ");
    }

    @Test
    void nextRequestCode_isFormattedAndUniqueAcrossCalls() {
        String first = requests.nextRequestCode();
        String second = requests.nextRequestCode();

        assertThat(first).matches("PCR-" + Year.now() + "-\\d{4}");
        assertThat(second).matches("PCR-" + Year.now() + "-\\d{4}");
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void create_thenFindSummaryAndItems_roundTripsWithOrderedItemsAndCorrectItemCount() {
        CreatePricingRequestRequest create = new CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", null,
            new BigDecimal("100.00"), " thb ", "note text", clientRequestId("1"),
            List.of(item("Toyota", "1"), item("Honda", "2"), item("Mazda", "3")));

        long id = requests.create(ticketId, requests.nextRequestCode(), create, salesActorId);

        PricingRequestSummaryDto summary = requests.findSummary(id).orElseThrow();
        assertThat(summary.ticketId()).isEqualTo(ticketId);
        assertThat(summary.status()).isEqualTo(PricingRequestStatus.DRAFT);
        assertThat(summary.requestedById()).isEqualTo(salesActorId);
        assertThat(summary.itemCount()).isEqualTo(3);
        // uppercase/trim normalisation
        assertThat(summary.targetCurrency()).isEqualTo("THB");
        assertThat(summary.ticketCreatedById()).isEqualTo(salesActorId);

        List<PricingRequestItemDto> items = requests.findItems(id);
        assertThat(items).hasSize(3);
        assertThat(items).extracting(PricingRequestItemDto::model)
            .containsExactly("1", "2", "3"); // sort_order preserves list order
        assertThat(items).extracting(PricingRequestItemDto::productDescription)
            .containsExactly("Toyota 1", "Honda 2", "Mazda 3");
        assertThat(items).extracting(PricingRequestItemDto::sortOrder).containsExactly(0, 1, 2);
    }

    @Test
    void create_withBlankTargetCurrency_storesNull() {
        CreatePricingRequestRequest create = new CreatePricingRequestRequest(
            PricingRequestRecipient.OWNER, null, null, null, null, "   ", null, clientRequestId("2"),
            List.of(item("Toyota", "1")));

        long id = requests.create(ticketId, requests.nextRequestCode(), create, salesActorId);

        assertThat(requests.findSummary(id).orElseThrow().targetCurrency()).isNull();
    }

    @Test
    void findByClientRequestId_returnsExistingRequestForSameRequestedByAndKey() {
        CreatePricingRequestRequest create = new CreatePricingRequestRequest(
            PricingRequestRecipient.OWNER, null, "Owner Co.", null, null, null, null, clientRequestId("20"),
            List.of(item("Toyota", "1")));
        long id = requests.create(ticketId, requests.nextRequestCode(), create, salesActorId);

        assertThat(requests.findByClientRequestId(salesActorId, clientRequestId("20")))
            .map(PricingRequestSummaryDto::id)
            .contains(id);
    }

    @Test
    void uniqueIndex_rejectsSameRequestedByAndClientRequestIdButAllowsDifferentRequestedBy() {
        String clientRequestId = clientRequestId("21");
        CreatePricingRequestRequest create = new CreatePricingRequestRequest(
            PricingRequestRecipient.OWNER, null, "Owner Co.", null, null, null, null, clientRequestId,
            List.of(item("Toyota", "1")));
        requests.create(ticketId, requests.nextRequestCode(), create, salesActorId);

        assertThatThrownBy(() -> requests.create(ticketId, requests.nextRequestCode(), create, salesActorId))
            .isInstanceOf(DataIntegrityViolationException.class);

        long otherSalesActorId = createEmployee("พนักงานขาย คนที่สาม", "sales3@glr.co.th");
        long otherTicketId = tickets.create(sampleTicket(), tickets.nextTicketCode(), otherSalesActorId, "พนักงานขาย คนที่สาม");
        long otherId = requests.create(otherTicketId, requests.nextRequestCode(), create, otherSalesActorId);

        assertThat(requests.findSummary(otherId)).isPresent();
    }

    @Test
    void transition_returnsOneFromExpectedStatusAndZeroFromStaleStatus_leavingRowUnchanged() {
        long id = createDraft();

        int firstAttempt = requests.transition(id, PricingRequestStatus.DRAFT, PricingRequestStatus.SUBMITTED, null, null);
        assertThat(firstAttempt).isEqualTo(1);
        assertThat(requests.findSummary(id).orElseThrow().status()).isEqualTo(PricingRequestStatus.SUBMITTED);

        // Stale: the row is no longer DRAFT, so this compare-and-set must no-op.
        int staleAttempt = requests.transition(id, PricingRequestStatus.DRAFT, PricingRequestStatus.CANCELLED, null, salesActorId);
        assertThat(staleAttempt).isEqualTo(0);

        PricingRequestSummaryDto unchanged = requests.findSummary(id).orElseThrow();
        assertThat(unchanged.status()).isEqualTo(PricingRequestStatus.SUBMITTED);
        assertThat(unchanged.cancelledAt()).isNull();
    }

    @Test
    void transition_pickedUpAt_isPreservedByteIdenticalAcrossReviewingMoreInfoReviewingRoundTrip() throws InterruptedException {
        long importId = createEmployee("นำเข้า ทดสอบ", "import@glr.co.th");
        long id = createDraft();
        requests.transition(id, PricingRequestStatus.DRAFT, PricingRequestStatus.SUBMITTED, null, null);
        requests.transition(id, PricingRequestStatus.SUBMITTED, PricingRequestStatus.IMPORT_REVIEWING, importId, null);

        var firstPickedUpAt = requests.findSummary(id).orElseThrow().pickedUpAt();
        assertThat(firstPickedUpAt).isNotNull();

        // Force a real clock tick so a bug that resets picked_up_at to now() would be
        // observable (not masked by two now() calls landing in the same microsecond).
        Thread.sleep(5);

        requests.transition(id, PricingRequestStatus.IMPORT_REVIEWING, PricingRequestStatus.MORE_INFO_REQUIRED, null, null);
        requests.transition(id, PricingRequestStatus.MORE_INFO_REQUIRED, PricingRequestStatus.IMPORT_REVIEWING, null, null);

        var pickedUpAtAfterRoundTrip = requests.findSummary(id).orElseThrow().pickedUpAt();
        assertThat(pickedUpAtAfterRoundTrip).isEqualTo(firstPickedUpAt);
    }

    @Test
    void transition_assignedImportId_firstWriterWins_notOverwrittenByLaterPickupAttempt() {
        long importA = createEmployee("นำเข้า เอ", "importa@glr.co.th");
        long importB = createEmployee("นำเข้า บี", "importb@glr.co.th");
        long id = createDraft();
        requests.transition(id, PricingRequestStatus.DRAFT, PricingRequestStatus.SUBMITTED, null, null);
        requests.transition(id, PricingRequestStatus.SUBMITTED, PricingRequestStatus.IMPORT_REVIEWING, importA, null);

        assertThat(requests.findSummary(id).orElseThrow().assignedImportId()).isEqualTo(importA);

        // Simulate a second Import user attempting to pick the request back up after a
        // MORE_INFO_REQUIRED round-trip; assignedImportId must NOT change to importB.
        requests.transition(id, PricingRequestStatus.IMPORT_REVIEWING, PricingRequestStatus.MORE_INFO_REQUIRED, null, null);
        requests.transition(id, PricingRequestStatus.MORE_INFO_REQUIRED, PricingRequestStatus.IMPORT_REVIEWING, importB, null);

        assertThat(requests.findSummary(id).orElseThrow().assignedImportId()).isEqualTo(importA);
    }

    @Test
    void addEvent_nullMetadataStoresEmptyObject_jsonStringRoundTrips_eventsOrderedByCreatedAt() {
        long id = createDraft();

        requests.addEvent(id, ticketId, salesActorId, "พนักงานขาย ทดสอบ",
            PricingRequestEventKind.PRICING_REQUEST_CREATED, null, PricingRequestStatus.DRAFT, "created", null);
        requests.addEvent(id, ticketId, salesActorId, "พนักงานขาย ทดสอบ",
            PricingRequestEventKind.PRICING_REQUEST_SUBMITTED, PricingRequestStatus.DRAFT, PricingRequestStatus.SUBMITTED,
            "submitted", "{\"reason\":\"urgent\"}");

        List<PricingRequestEventDto> events = requests.findEvents(id);
        assertThat(events).hasSize(2);
        assertThat(events).extracting(PricingRequestEventDto::eventKind)
            .containsExactly(PricingRequestEventKind.PRICING_REQUEST_CREATED, PricingRequestEventKind.PRICING_REQUEST_SUBMITTED);
        assertThat(events.get(0).metadata()).isEqualTo("{}");
        assertThat(events.get(1).metadata()).contains("urgent");
    }

    @Test
    void findSummaries_filtersByStatusDefaultCreatedByAndActiveDealsOnly() {
        long otherSalesActorId = createEmployee("พนักงานขาย คนที่สอง", "sales2@glr.co.th");
        long otherTicketId = tickets.create(sampleTicket(), tickets.nextTicketCode(), otherSalesActorId, "พนักงานขาย คนที่สอง");

        long draftId = createDraft();
        long cancelledId = createDraft();
        requests.transition(cancelledId, PricingRequestStatus.DRAFT, PricingRequestStatus.CANCELLED, null, salesActorId);
        long otherActorRequestId = requests.create(otherTicketId, requests.nextRequestCode(),
            new CreatePricingRequestRequest(PricingRequestRecipient.OWNER, null, null, null, null, null, null, clientRequestId("3"),
                List.of(item("Toyota", "1"))),
            otherSalesActorId);

        // draftOversight=true / draftOwnerId=null throughout this test: it is
        // exercising status/createdBy/activeDealsOnly filtering, not the DRAFT
        // privacy clause (see findSummaries_hidesDraftUnlessOwnerOrOversight for
        // that), so oversight=true keeps every row visible regardless of who
        // created it, same as before that clause existed.

        // Default (status == null) excludes CANCELLED.
        List<PricingRequestSummaryDto> defaultQueue = requests.findSummaries(null, null, null, false, true, null);
        assertThat(defaultQueue).extracting(PricingRequestSummaryDto::id).contains(draftId, otherActorRequestId);
        assertThat(defaultQueue).extracting(PricingRequestSummaryDto::id).doesNotContain(cancelledId);

        // Explicit status filter.
        List<PricingRequestSummaryDto> cancelledOnly =
            requests.findSummaries(PricingRequestStatus.CANCELLED, null, null, false, true, null);
        assertThat(cancelledOnly).extracting(PricingRequestSummaryDto::id).containsExactly(cancelledId);

        // createdByFilter hides the other sales rep's deal.
        List<PricingRequestSummaryDto> scopedToSelf = requests.findSummaries(null, null, salesActorId, false, true, null);
        assertThat(scopedToSelf).extracting(PricingRequestSummaryDto::id).contains(draftId);
        assertThat(scopedToSelf).extracting(PricingRequestSummaryDto::id).doesNotContain(otherActorRequestId);

        // activeDealsOnly drops requests once their ticket leaves ACTIVE lifecycle.
        assertThat(requests.findSummaries(null, null, null, true, true, null))
            .extracting(PricingRequestSummaryDto::id).contains(draftId);
        jdbc.update("UPDATE sales.ticket SET lifecycle = 'ON_HOLD' WHERE ticket_id = :id", Map.of("id", ticketId));
        assertThat(requests.findSummaries(null, null, null, true, true, null))
            .extracting(PricingRequestSummaryDto::id).doesNotContain(draftId);
    }

    @Test
    void findSummaries_hidesDraftUnlessOwnerOrOversight() {
        long draftId = createDraft(); // owned by salesActorId

        // Non-owner, non-oversight caller (e.g. import/account reading the queue):
        // the draft is hidden, both with no status filter and with an explicit
        // status='DRAFT' filter — the new clause applies regardless of how the
        // caller arrived at the row.
        assertThat(requests.findSummaries(null, null, null, false, false, 999L))
            .extracting(PricingRequestSummaryDto::id).doesNotContain(draftId);
        assertThat(requests.findSummaries(PricingRequestStatus.DRAFT, null, null, false, false, 999L))
            .extracting(PricingRequestSummaryDto::id).doesNotContain(draftId);

        // The owner sees their own draft (draftOwnerId = salesActorId matches
        // t.created_by), even with no oversight.
        assertThat(requests.findSummaries(null, null, null, false, false, salesActorId))
            .extracting(PricingRequestSummaryDto::id).contains(draftId);

        // Oversight (ceo/sales_manager) sees it regardless of ownership.
        assertThat(requests.findSummaries(null, null, null, false, true, 999L))
            .extracting(PricingRequestSummaryDto::id).contains(draftId);

        // Once the request leaves DRAFT, the clause no longer applies — a
        // non-owner, non-oversight caller can see it like any other status.
        requests.transition(draftId, PricingRequestStatus.DRAFT, PricingRequestStatus.SUBMITTED, null, null);
        assertThat(requests.findSummaries(null, null, null, false, false, 999L))
            .extracting(PricingRequestSummaryDto::id).contains(draftId);
    }

    @Test
    void checkConstraints_rejectZeroQuantityAndUnknownQuantityType() {
        long id = createDraft();

        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO sales.pricing_request_item
                (pricing_request_id, requested_qty, requested_unit, quantity_type)
            VALUES (:id, 0, 'PIECE', 'REFERENCE')
            """, Map.of("id", id)))
            .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO sales.pricing_request_item
                (pricing_request_id, requested_qty, requested_unit, quantity_type)
            VALUES (:id, 1, 'PIECE', 'NOT_A_REAL_TYPE')
            """, Map.of("id", id)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    // NOTE: this test used to be named
    // updateDraft_leavesNullFieldsUnchangedAndOnlyAppliesToDraftRows and asserted
    // the OPPOSITE of what it asserts now: that a null field in the request left
    // the existing column value in place (COALESCE(:x, col)). That was a
    // deliberate product bug — there was no way for a client to ever clear a
    // field via PUT (e.g. remove a target price or a note) — fixed by the
    // review-remediation plan's Fix 2. PricingRequestRepository.updateDraft is
    // now a full replacement of every editable scalar column, so this rewrite
    // asserts the inverse: a null in the request DOES clear the column.
    @Test
    void updateDraft_fullyReplacesEditableFields_includingClearingAFieldToNull_andOnlyAppliesToDraftRows() {
        long id = requests.create(ticketId, requests.nextRequestCode(),
            new CreatePricingRequestRequest(PricingRequestRecipient.DESIGNER, null, "Designer Co.", null,
                new BigDecimal("100.00"), "thb", "first note", clientRequestId("4"), List.of(item("Toyota", "1"))),
            salesActorId);

        // Full replacement: recipientType actually changes (DESIGNER -> OWNER,
        // no longer "untouched, left as-is by COALESCE"), recipientLabel is
        // explicitly cleared to null even though the request also didn't touch
        // it, and customerTargetPrice/note/items are overwritten as before.
        // recipient_type is NOT NULL in the DB, so every UpdatePricingRequestRequest
        // in this test supplies a real value for it (repository-level test — the
        // "must not be blank" 400 guard lives in PricingRequestService, not here).
        requests.updateDraft(id, new UpdatePricingRequestRequest(
            PricingRequestRecipient.OWNER, null, null, null, new BigDecimal("200.00"), null, "updated note",
            List.of(item("Honda", "9"))));

        PricingRequestSummaryDto afterFirstUpdate = requests.findSummary(id).orElseThrow();
        assertThat(afterFirstUpdate.recipientType()).isEqualTo(PricingRequestRecipient.OWNER);
        assertThat(afterFirstUpdate.recipientLabel()).isNull(); // cleared, not left as "Designer Co."
        assertThat(afterFirstUpdate.targetCurrency()).isNull(); // cleared, not left as "THB"
        assertThat(afterFirstUpdate.customerTargetPrice()).isEqualByComparingTo("200.00");
        assertThat(afterFirstUpdate.note()).isEqualTo("updated note");
        assertThat(requests.findItems(id)).extracting(PricingRequestItemDto::model).containsExactly("9");

        // A second update that clears customerTargetPrice and note to null (the
        // exact "remove a target price / note" case the old COALESCE semantics
        // could never express) — items omitted this time (null), proving items
        // is the one field that keeps "omit to leave untouched" behavior.
        requests.updateDraft(id, new UpdatePricingRequestRequest(
            PricingRequestRecipient.OWNER, null, null, null, null, null, null, null));
        PricingRequestSummaryDto afterClear = requests.findSummary(id).orElseThrow();
        assertThat(afterClear.customerTargetPrice()).isNull();
        assertThat(afterClear.note()).isNull();
        assertThat(requests.findItems(id)).extracting(PricingRequestItemDto::model).containsExactly("9");

        requests.transition(id, PricingRequestStatus.DRAFT, PricingRequestStatus.SUBMITTED, null, null);
        boolean appliedAfterSubmit = requests.updateDraft(id, new UpdatePricingRequestRequest(
            PricingRequestRecipient.OWNER, null, null, null, null, null, "should not apply", null));
        assertThat(appliedAfterSubmit).isFalse();
        assertThat(requests.findSummary(id).orElseThrow().note()).isNull();
    }

    @Test
    void transition_pickup_neverWritesSalesTicketAssignedTo() {
        // The landmine guard (see PricingRequestRepository's and TicketRepository's
        // class-level Javadoc): TicketRepository.addEventInternal sets
        // sales.ticket.assigned_to as a side-effect of any PICKED_UP event ON THE
        // TICKET. PricingRequestRepository must never call TicketRepository, so a
        // real pickup here (SUBMITTED -> IMPORT_REVIEWING with an assignImportId)
        // must leave sales.ticket.assigned_to untouched — proven against a real
        // Postgres row, not a mock that could silently miss a future regression.
        long importId = createEmployee("นำเข้า ทดสอบ", "import-landmine@glr.co.th");
        long id = createDraft();
        requests.transition(id, PricingRequestStatus.DRAFT, PricingRequestStatus.SUBMITTED, null, null);

        requests.transition(id, PricingRequestStatus.SUBMITTED, PricingRequestStatus.IMPORT_REVIEWING, importId, null);

        assertThat(requests.findSummary(id).orElseThrow().assignedImportId()).isEqualTo(importId);
        Long ticketAssignedTo = jdbc.queryForObject(
            "SELECT assigned_to FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticketId), Long.class);
        assertThat(ticketAssignedTo).isNull();
    }

    @Test
    void findOpenIdsForTicket_excludesCancelled() {
        long openId = createDraft();
        long cancelledId = createDraft();
        requests.transition(cancelledId, PricingRequestStatus.DRAFT, PricingRequestStatus.CANCELLED, null, salesActorId);

        List<Long> openIds = requests.findOpenIdsForTicket(ticketId);

        assertThat(openIds).contains(openId);
        assertThat(openIds).doesNotContain(cancelledId);
    }

    // --- helpers ---

    private long createDraft() {
        CreatePricingRequestRequest create = new CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, null, null, null, null, null, clientRequestId(String.valueOf(++clientRequestSeq)),
            List.of(item("Toyota", "1")));
        return requests.create(ticketId, requests.nextRequestCode(), create, salesActorId);
    }

    private long createEmployee(String nameTh, String email) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, "SALES", "Sales Division", "แผนกขาย",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null));
    }

    private CreateTicketRequest sampleTicket() {
        return new CreateTicketRequest("ใบเสนอราคา", "NORMAL", "ลูกค้าทดสอบ", null, null, null, null, null, List.of());
    }

    private PricingRequestItemRequest item(String brand, String model) {
        return new PricingRequestItemRequest(null, null, null, brand, model, brand + " " + model, null, null, null, null,
            new BigDecimal("1"), null, "PIECE", QuantityType.REFERENCE, null, null, null);
    }

    private String clientRequestId(String suffix) {
        return "22222222-2222-2222-2222-" + String.format("%012d", Long.parseLong(suffix));
    }
}
