package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.dealquotation.DealQuotationAttachmentService.AttachmentFile;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationAttachmentDto;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ApproveRequest;
import th.co.glr.hr.ticket.QuotationRenderModel;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.QuotationStatus;

/**
 * What the permitted callers get from a quotation's เอกสารแนบ, through the real
 * {@link DealQuotationAttachmentService} against a real Postgres. A file is stored as a row plus
 * its bytes, listed (with the files of the documents a revision revises), served byte for byte,
 * and removed. Who is REFUSED is {@link DealQuotationAttachmentAuthzIntegrationTest}.
 */
class DealQuotationAttachmentIntegrationTest extends AbstractDealQuotationAttachmentIntegrationTest {

    // ── attach ───────────────────────────────────────────────────────────────────────────────

    /** Also pins the shape {@link #seed} writes. Every other test stores its fixture files in it. */
    @Test
    void theOwner_attachesAFileToADraft_andTheRowAndTheBytesAreStored() {
        DealQuotationDto quotation = draftOwnedBy(salesActor);

        DealQuotationAttachmentDto attached = attachmentService.upload(quotation.id(), pdf(FILE), salesActor);

        assertThat(attached).as("the upload answers the stored file").isNotNull();
        assertThat(attached.quotationId()).isEqualTo(quotation.id());
        assertThat(attached.quotationNumber()).isEqualTo(quotation.number());
        assertThat(attached.fileName()).isEqualTo(FILE);
        assertThat(attached.mimeType()).isEqualTo(PDF);
        assertThat(attached.fileSize()).isEqualTo((long) VENDOR_QUOTE.length);
        assertThat(attached.uploadedById()).isEqualTo(salesActor.id());
        assertThat(attached.uploadedByName()).isNotBlank().isEqualTo(employeeName(salesActor.id()));
        assertThat(attached.uploadedAt()).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
        assertThat(attached.inherited()).isFalse();

        Map<String, Object> row = jdbc.queryForMap("""
            SELECT domain, owner_id, file_name, file_path, mime_type, file_size, uploaded_by, storage_state, deleted_at
              FROM hr.file_attachment WHERE attachment_id = :id
            """, Map.of("id", attached.id()));
        assertThat(row.get("domain")).isEqualTo(DOMAIN);
        assertThat(row.get("owner_id")).isEqualTo(quotation.id());
        assertThat(row.get("file_name")).isEqualTo(FILE);
        assertThat((String) row.get("file_path")).as("the storage key, never a disk path")
            .startsWith("deal-quotation/" + quotation.id() + "/").endsWith(".pdf");
        assertThat(row.get("mime_type")).isEqualTo(PDF);
        assertThat(row.get("file_size")).isEqualTo((long) VENDOR_QUOTE.length);
        assertThat(row.get("uploaded_by")).isEqualTo(salesActor.id());
        assertThat(row.get("storage_state")).as("the bytes are in the database").isEqualTo("DATABASE");
        assertThat(row.get("deleted_at")).isNull();
        assertThat(storedBytes(attached.id())).isEqualTo(VENDOR_QUOTE);
        assertThat(fileRows()).isEqualTo(1);
        assertThat(blobRows()).isEqualTo(1);
    }

    @Test
    void theOwner_theSalesManager_andAGrantHolder_attach_onADraftAndWhileItAwaitsApproval() {
        DealQuotationDto quotation = draftOwnedBy(salesActor);

        DealQuotationAttachmentDto byOwner = attachmentService.upload(quotation.id(), pdf("owner.pdf"), salesActor);
        DealQuotationAttachmentDto byManager = attachmentService.upload(quotation.id(), pdf("manager.pdf"), salesManagerActor);
        DealQuotationAttachmentDto byGrantHolder = attachmentService.upload(quotation.id(), pdf("qc.pdf"), grantedQcActor);
        assertThat(byOwner).as("the owner's file on the draft").isNotNull();
        assertThat(byManager).as("the sales manager's file on the draft").isNotNull();
        assertThat(byGrantHolder).as("the grant holder's file on the draft").isNotNull();

        quotationService.submit(quotation.id(), salesActor);
        assertThat(statusOf(quotation.id())).isEqualTo(QuotationStatus.PENDING_APPROVAL);
        DealQuotationAttachmentDto whilePending = attachmentService.upload(quotation.id(), pdf("late.pdf"), salesActor);
        assertThat(whilePending).as("a file attached while the approver has the document").isNotNull();

        assertThat(filesOf(quotation.id())).isEqualTo(4);
        // The approver sees who attached each one.
        assertThat(attachmentService.list(quotation.id(), ceoActor))
            .extracting(DealQuotationAttachmentDto::fileName, DealQuotationAttachmentDto::uploadedById,
                DealQuotationAttachmentDto::uploadedByName)
            .containsExactlyInAnyOrder(
                tuple("owner.pdf", salesActor.id(), employeeName(salesActor.id())),
                tuple("manager.pdf", salesManagerActor.id(), employeeName(salesManagerActor.id())),
                tuple("qc.pdf", grantedQcActor.id(), employeeName(grantedQcActor.id())),
                tuple("late.pdf", salesActor.id(), employeeName(salesActor.id())));
    }

    // ── list ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void theList_isTheQuotationsOwnFiles_newestFirst_forEveryoneWhoMayRead() {
        DealQuotationDto quotation = draftOwnedBy(salesActor);
        Instant earlier = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        Instant later = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        long older = seed(quotation.id(), "older.pdf", VENDOR_QUOTE, salesActor, earlier);
        long newer = seed(quotation.id(), "newer.pdf", new byte[] {1, 2, 3}, salesManagerActor, later);

        // The owner, the sales manager, the approver, and a grant holder.
        for (UserPrincipal reader : List.of(salesActor, salesManagerActor, ceoActor, grantedQcActor)) {
            List<DealQuotationAttachmentDto> listed = attachmentService.list(quotation.id(), reader);

            assertThat(listed).as("what %s is listed", reader.role())
                .extracting(DealQuotationAttachmentDto::id).containsExactly(newer, older);
            assertThat(listed).allSatisfy(file -> {
                assertThat(file.inherited()).isFalse();
                assertThat(file.quotationId()).isEqualTo(quotation.id());
                assertThat(file.quotationNumber()).isEqualTo(quotation.number());
            });
            DealQuotationAttachmentDto first = listed.get(0);
            assertThat(first.fileName()).isEqualTo("newer.pdf");
            assertThat(first.mimeType()).isEqualTo(PDF);
            assertThat(first.fileSize()).isEqualTo(3L);
            assertThat(first.uploadedById()).isEqualTo(salesManagerActor.id());
            assertThat(first.uploadedByName()).isEqualTo(employeeName(salesManagerActor.id()));
            assertThat(first.uploadedAt()).isEqualTo(later);
        }
    }

    // ── download ─────────────────────────────────────────────────────────────────────────────

    @Test
    void aDownload_isTheExactBytes_withTheStoredNameAndType() {
        DealQuotationDto quotation = draftOwnedBy(salesActor);
        byte[] image = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, (byte) 0xFF, 0x7F, (byte) 0x80};
        DealQuotationAttachmentDto attached =
            attachmentService.upload(quotation.id(), file("รูปใบเสนอราคา ผู้ขาย.png", "image/png", image), salesActor);
        assertThat(attached).as("the upload answers the stored file").isNotNull();

        for (UserPrincipal reader : List.of(salesActor, salesManagerActor, ceoActor, grantedQcActor)) {
            AttachmentFile served = attachmentService.download(attached.id(), reader);

            assertThat(served).as("what %s is served", reader.role()).isNotNull();
            assertThat(served.content()).isEqualTo(image);
            assertThat(served.mimeType()).isEqualTo("image/png");
            assertThat(served.fileName()).isEqualTo("รูปใบเสนอราคา ผู้ขาย.png");
        }
    }

    // ── remove ───────────────────────────────────────────────────────────────────────────────

    @Test
    void theOwner_removesAFileFromADraft_andTheRowAndTheBytesAreGone() {
        DealQuotationDto quotation = draftOwnedBy(salesActor);
        long kept = seed(quotation.id(), "kept.pdf", VENDOR_QUOTE, salesActor);
        long removed = seed(quotation.id(), "wrong-file.pdf", VENDOR_QUOTE, salesActor);

        attachmentService.delete(removed, salesActor);

        assertThat(rowExists(removed)).as("the row is gone").isFalse();
        assertThat(storedBytes(removed)).as("the bytes are gone").isNull();
        assertThat(storedBytes(kept)).as("the other file is untouched").isEqualTo(VENDOR_QUOTE);
        assertThat(attachmentService.list(quotation.id(), salesActor))
            .extracting(DealQuotationAttachmentDto::id).containsExactly(kept);
        refused("downloading the removed file", NOT_FOUND, () -> attachmentService.download(removed, salesActor));
        refused("removing it a second time", NOT_FOUND, () -> attachmentService.delete(removed, salesActor));
    }

    // ── revisions, reorders and other quotations of the deal ─────────────────────────────────

    @Test
    void aRevision_listsItsOwnFilesFirst_thenThoseOfEveryDocumentItRevises_flaggedAndNamed() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        DealQuotationDto first = approved(deal(salesActor), salesActor);
        long onFirst = seed(first.id(), "first.pdf", VENDOR_QUOTE, salesActor, now.minus(3, ChronoUnit.HOURS));
        DealQuotationDto second = quotationService.createRevision(first.id(), salesActor);
        long onSecond = seed(second.id(), "second.pdf", VENDOR_QUOTE, salesActor, now.minus(2, ChronoUnit.HOURS));
        quotationService.submit(second.id(), salesActor);
        quotationService.approve(second.id(), new ApproveRequest(null), salesManagerActor);
        DealQuotationDto third = quotationService.createRevision(second.id(), salesActor);
        long onThird = seed(third.id(), "third.pdf", VENDOR_QUOTE, salesActor, now.minus(1, ChronoUnit.HOURS));
        // The premise: three documents in one chain, each with its own number.
        assertThat(second.parentQuotationId()).isEqualTo(first.id());
        assertThat(third.parentQuotationId()).isEqualTo(second.id());
        assertThat(List.of(first.number(), second.number(), third.number())).doesNotHaveDuplicates();

        // Own first, then the nearest earlier document, then the one before it.
        assertThat(attachmentService.list(third.id(), salesActor))
            .extracting(DealQuotationAttachmentDto::id, DealQuotationAttachmentDto::quotationId,
                DealQuotationAttachmentDto::quotationNumber, DealQuotationAttachmentDto::inherited)
            .containsExactly(
                tuple(onThird, third.id(), third.number(), false),
                tuple(onSecond, second.id(), second.number(), true),
                tuple(onFirst, first.id(), first.number(), true));
        // An earlier document never shows a later one's file.
        assertThat(attachmentService.list(second.id(), salesActor))
            .extracting(DealQuotationAttachmentDto::id, DealQuotationAttachmentDto::inherited)
            .containsExactly(tuple(onSecond, false), tuple(onFirst, true));
        assertThat(attachmentService.list(first.id(), salesActor))
            .extracting(DealQuotationAttachmentDto::id, DealQuotationAttachmentDto::inherited)
            .containsExactly(tuple(onFirst, false));
        // The approver of the revision reads an inherited file through its id.
        assertThat(served(onFirst, ceoActor)).isEqualTo(VENDOR_QUOTE);
    }

    @Test
    void aReorder_andAnotherQuotationOfTheSameDeal_startWithNoFiles() {
        long ticket = deal(salesActor);
        DealQuotationDto source = approved(ticket, salesActor);
        long file = seed(source.id(), FILE, VENDOR_QUOTE, salesActor);
        DealQuotationDto reorder = quotationService.createReorder(source.id(), salesActor);
        DealQuotationDto sibling = quotationService.create(ticket, draft(), salesActor);
        DealQuotationDto revision = quotationService.createRevision(source.id(), salesActor);
        // The premise: one copy that is not a revision, one unrelated quotation, one revision.
        assertThat(reorder.derivedFromQuotationId()).isEqualTo(source.id());
        assertThat(reorder.parentQuotationId()).isNull();
        assertThat(sibling.parentQuotationId()).isNull();
        assertThat(revision.parentQuotationId()).isEqualTo(source.id());

        // Control first: the file is there, and a revision of the same source does inherit it.
        assertThat(attachmentService.list(source.id(), salesActor))
            .extracting(DealQuotationAttachmentDto::id).containsExactly(file);
        assertThat(attachmentService.list(revision.id(), salesActor))
            .extracting(DealQuotationAttachmentDto::id, DealQuotationAttachmentDto::inherited)
            .containsExactly(tuple(file, true));

        assertThat(attachmentService.list(reorder.id(), salesActor)).as("a reorder inherits nothing").isEmpty();
        assertThat(attachmentService.list(sibling.id(), salesActor)).as("another quotation of the deal").isEmpty();
    }

    @Test
    void theLimitCountsAQuotationsOwnFiles_notTheOnesItInherits() {
        DealQuotationDto first = approved(deal(salesActor), salesActor);
        for (int i = 1; i <= 10; i++) {
            seed(first.id(), "first-" + i + ".pdf", VENDOR_QUOTE, salesActor);
        }
        DealQuotationDto revision = quotationService.createRevision(first.id(), salesActor);

        DealQuotationAttachmentDto attached = attachmentService.upload(revision.id(), pdf("revision.pdf"), salesActor);

        assertThat(attached).as("the revision's first own file, beside ten inherited ones").isNotNull();
        assertThat(filesOf(revision.id())).isEqualTo(1);
        assertThat(attachmentService.list(revision.id(), salesActor)).hasSize(11);
    }

    // ── never printed, never announced ───────────────────────────────────────────────────────

    /**
     * The file is evidence for the approver and nothing else. This searches for its name in
     * everything the quotation prints: the render model, both HTML documents the PDF engines are
     * given, and every cell of the workbook the service renders from the stored row. Each of the
     * three is first shown to carry the project name, so a document that printed nothing cannot pass.
     */
    @Test
    void aFilesName_appearsNowhereInTheRenderedQuotation() {
        DealQuotationDto quotation = draftOwnedBy(salesActor);
        seed(quotation.id(), FILE, VENDOR_QUOTE, salesActor);

        DealQuotationDto stored = quotationService.get(quotation.id(), salesActor);
        QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(stored, null, null);
        assertThat(model.toString()).contains(PROJECT_NAME).doesNotContain(TOKEN);
        for (String html : List.of(new QuotationRenderer().toHtml(model), QuotationHtmlDocument.render(model))) {
            assertThat(html).contains(PROJECT_NAME).doesNotContain(TOKEN);
        }
        List<String> cells = sheetTexts(quotation.id());
        assertThat(cells).anyMatch(text -> text.contains(PROJECT_NAME));
        assertThat(cells).noneMatch(text -> text.contains(TOKEN));

        // Control: the file is on this quotation, and the feature lists it under that name.
        assertThat(attachmentService.list(quotation.id(), salesActor))
            .extracting(DealQuotationAttachmentDto::fileName).containsExactly(FILE);
    }

    /**
     * The pricing-request upload this feature is modelled on writes "Attachment uploaded: {name}"
     * to the deal's timeline, which account and import read ({@code TicketAccessPolicy
     * #VIEWER_ROLES}). Doing the same here would show them the vendor's name. So attaching and
     * removing must write the file's name nowhere but the file table.
     */
    @Test
    void attachingAndRemovingAFile_putsItsNameOnNoTimelineAndInNoNotification() {
        long ticket = deal(salesActor);
        DealQuotationDto quotation = quotationService.create(ticket, draft(), salesActor);
        DealQuotationAttachmentDto attached = attachmentService.upload(quotation.id(), pdf(FILE), salesActor);
        assertThat(attached).as("the upload answers the stored file").isNotNull();
        DealQuotationAttachmentDto removed = attachmentService.upload(quotation.id(), pdf("removed " + TOKEN + ".pdf"), salesActor);
        attachmentService.delete(removed.id(), salesActor);
        quotationService.submit(quotation.id(), salesActor);

        List<String> timeline = jdbc.queryForList(
            "SELECT message FROM sales.ticket_event WHERE ticket_id = :id AND message IS NOT NULL",
            Map.of("id", ticket), String.class);
        // Control: this deal's timeline is being written to.
        assertThat(timeline).anyMatch(message -> message.contains(quotation.number()));
        assertThat(timeline).noneMatch(message -> message.contains(TOKEN));
        List<String> notifications = jdbc.queryForList(
            "SELECT concat_ws(' ', title, message, link) FROM hr.notification", Map.of(), String.class);
        assertThat(notifications).noneMatch(text -> text.contains(TOKEN));
    }
}
