package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.attachment.FileAttachmentBlobRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationAttachmentDto;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ApproveRequest;
import th.co.glr.hr.dealquotation.DealQuotationRequests.CancelRequest;
import th.co.glr.hr.dealquotation.DealQuotationRequests.RejectRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRepository;
import th.co.glr.hr.leave.LeaveAttachmentRepository;
import th.co.glr.hr.ticket.QuotationStatus;

/**
 * Who may NOT reach a quotation's เอกสารแนบ, through the real {@link DealQuotationAttachmentService},
 * the real {@link DealQuotationService} whose view and edit rules it must reuse, and a real
 * Postgres. This is the permission evidence CLAUDE.md asks of an authorization change.
 *
 * <p>Every case is written wrong-way-round. It asserts a caller is REFUSED, then reads the tables
 * back to show nothing was stored or removed. Each refusal has a control on the same fixture,
 * either the same caller doing the neighbouring thing it is allowed or a permitted caller doing the
 * refused thing, so the fixture cannot be what refuses.
 *
 * <p>The files are seeded, not uploaded (see {@link AbstractDealQuotationAttachmentIntegrationTest}),
 * so each case fails on its own refusal when its guard is missing.
 *
 * <p>The rule:
 * <ul>
 *   <li>List and download: whoever may VIEW the quotation ({@code DealQuotationService#get}),
 *       except the roles {@code account} and {@code import}, with or without the quotation grant.</li>
 *   <li>Upload: whoever may EDIT it (the identity rule of {@code DealQuotationService#update}),
 *       while it is DRAFT or PENDING_APPROVAL.</li>
 *   <li>Delete: the same identity, while the quotation the file is on is DRAFT and has no
 *       revision open.</li>
 *   <li>An attachment id is looked up within this feature's own domain, so a file of leave, tax
 *       or a factory quote is a 404 here.</li>
 * </ul>
 */
class DealQuotationAttachmentAuthzIntegrationTest extends AbstractDealQuotationAttachmentIntegrationTest {

    // ── reading ──────────────────────────────────────────────────────────────────────────────

    @Test
    void accountAndImport_openTheQuotation_butReachNoneOfItsFiles() {
        DealQuotationDto quotation = draftOwnedBy(salesActor);
        long file = seed(quotation.id(), FILE, VENDOR_QUOTE, salesActor);
        long rows = fileRows();
        openToAccount(quotation.id()); // account reads only an APPROVED quotation on a deal in its scope

        for (UserPrincipal reader : List.of(accountActor, importActor)) {
            String who = reader.role();
            // Control: this caller reads the quotation itself.
            assertThat(quotationService.get(quotation.id(), reader).number()).as(who).isEqualTo(quotation.number());

            refused(who + " listing the files", FORBIDDEN, () -> attachmentService.list(quotation.id(), reader));
            refused(who + " downloading a file", FORBIDDEN, () -> attachmentService.download(file, reader));
            refused(who + " uploading", FORBIDDEN, () -> attachmentService.upload(quotation.id(), pdf("x.pdf"), reader));
            refused(who + " deleting a file", FORBIDDEN, () -> attachmentService.delete(file, reader));
            // The quotation this caller CAN read must not name the file either.
            assertThat(quotationService.get(quotation.id(), reader).toString())
                .as("the quotation %s reads", who).doesNotContain(TOKEN);
        }
        assertThat(fileRows()).as("nothing stored, nothing removed").isEqualTo(rows);

        // Control: the file is there, and a reader who is neither account nor import gets it.
        assertThat(attachmentService.list(quotation.id(), ceoActor))
            .extracting(DealQuotationAttachmentDto::id).containsExactly(file);
        assertThat(served(file, ceoActor)).isEqualTo(VENDOR_QUOTE);
    }

    @Test
    void theQuotationGrant_doesNotLiftTheExclusionOfAccountAndImport() {
        DealQuotationDto quotation = draftOwnedBy(salesActor);
        long file = seed(quotation.id(), FILE, VENDOR_QUOTE, salesActor);
        long rows = fileRows();

        for (UserPrincipal holder : List.of(accountWithGrantActor, importWithGrantActor)) {
            String who = holder.role() + " holding the quotation grant";
            // Control: the grant lets this caller open AND save the quotation itself.
            if ("account".equals(holder.role())) {
                openToAccount(quotation.id()); // account may open only an APPROVED quotation in its scope
            }
            assertThat(quotationService.get(quotation.id(), holder).id()).as(who).isEqualTo(quotation.id());

            // Reading is refused HERE, while the quotation is still one this holder opens (for account:
            // APPROVED and in scope). Refused only after forcing DRAFT, the account holder would be
            // turned away by the status rule instead, and the file-role exclusion would go untested.
            refused(who + " listing the files", FORBIDDEN, () -> attachmentService.list(quotation.id(), holder));
            refused(who + " downloading a file", FORBIDDEN, () -> attachmentService.download(file, holder));

            if ("account".equals(holder.role())) {
                forceStatus(quotation.id(), QuotationStatus.DRAFT); // back to an editable draft for the save below
            }
            assertThat(quotationService.update(quotation.id(), draft(), holder).docStatus())
                .as(who).isEqualTo(QuotationStatus.DRAFT);

            refused(who + " uploading", FORBIDDEN, () -> attachmentService.upload(quotation.id(), pdf("x.pdf"), holder));
            refused(who + " deleting a file", FORBIDDEN, () -> attachmentService.delete(file, holder));
        }
        assertThat(fileRows()).as("nothing stored, nothing removed").isEqualTo(rows);
        assertThat(storedBytes(file)).isEqualTo(VENDOR_QUOTE);

        // Control: the same grant, held by a role that is not excluded, opens all four.
        assertThat(attachmentService.list(quotation.id(), grantedQcActor))
            .extracting(DealQuotationAttachmentDto::id).containsExactly(file);
        assertThat(served(file, grantedQcActor)).isEqualTo(VENDOR_QUOTE);
        assertThat(attachmentService.upload(quotation.id(), pdf("by-qc.pdf"), grantedQcActor)).isNotNull();
        attachmentService.delete(file, grantedQcActor);
        assertThat(rowExists(file)).isFalse();
    }

    @Test
    void aCallerWhoMayNotOpenTheQuotation_canNeitherListNorDownloadNorUploadNorDelete() {
        DealQuotationDto quotation = draftOwnedBy(salesActor);
        long file = seed(quotation.id(), FILE, VENDOR_QUOTE, salesActor);
        long rows = fileRows();

        // Another rep (not this deal's), qc without the grant, and a plain employee.
        for (UserPrincipal stranger : List.of(otherSalesActor, qcActor, employeeActor)) {
            String who = stranger.role() + " " + stranger.id() + ", who may not open the quotation,";
            // Control: this caller is refused the quotation itself.
            refused(who + " opening the quotation", FORBIDDEN, () -> quotationService.get(quotation.id(), stranger));

            refused(who + " listing its files", FORBIDDEN, () -> attachmentService.list(quotation.id(), stranger));
            refused(who + " downloading a file", FORBIDDEN, () -> attachmentService.download(file, stranger));
            refused(who + " uploading", FORBIDDEN, () -> attachmentService.upload(quotation.id(), pdf("x.pdf"), stranger));
            refused(who + " deleting a file", FORBIDDEN, () -> attachmentService.delete(file, stranger));
        }
        assertThat(fileRows()).as("nothing stored, nothing removed").isEqualTo(rows);
        assertThat(storedBytes(file)).isEqualTo(VENDOR_QUOTE);

        // Control: the other rep does all four on a quotation of his OWN deal.
        DealQuotationDto his = draftOwnedBy(otherSalesActor);
        DealQuotationAttachmentDto hisFile = attachmentService.upload(his.id(), pdf("own.pdf"), otherSalesActor);
        assertThat(hisFile).isNotNull();
        assertThat(attachmentService.list(his.id(), otherSalesActor))
            .extracting(DealQuotationAttachmentDto::id).containsExactly(hisFile.id());
        assertThat(served(hisFile.id(), otherSalesActor)).isEqualTo(VENDOR_QUOTE);
        attachmentService.delete(hisFile.id(), otherSalesActor);
        assertThat(rowExists(hisFile.id())).isFalse();
    }

    @Test
    void anUnknownQuotationOrAttachment_isNotFound() {
        long noSuchId = 987_654_321L;

        refused("listing an unknown quotation's files", NOT_FOUND, () -> attachmentService.list(noSuchId, salesManagerActor));
        refused("uploading to an unknown quotation", NOT_FOUND,
            () -> attachmentService.upload(noSuchId, pdf("x.pdf"), salesManagerActor));
        refused("downloading an unknown file", NOT_FOUND, () -> attachmentService.download(noSuchId, salesManagerActor));
        refused("deleting an unknown file", NOT_FOUND, () -> attachmentService.delete(noSuchId, salesManagerActor));
        assertThat(fileRows()).isZero();
    }

    // ── uploading and deleting: identity ─────────────────────────────────────────────────────

    @Test
    void theApprover_whoReadsButDoesNotEdit_canNeitherUploadNorDelete() {
        DealQuotationDto inDraft = draftOwnedBy(salesActor);
        long draftFile = seed(inDraft.id(), FILE, VENDOR_QUOTE, salesActor);
        DealQuotationDto inReview = quotationService.submit(draftOwnedBy(salesActor).id(), salesActor);
        long reviewFile = seed(inReview.id(), FILE, VENDOR_QUOTE, salesActor);
        long rows = fileRows();

        for (DealQuotationDto quotation : List.of(inDraft, inReview)) {
            String where = "a " + quotation.docStatus() + " quotation";
            // Control: the CEO reads this quotation, and is the one who will approve it.
            assertThat(quotationService.get(quotation.id(), ceoActor).id()).isEqualTo(quotation.id());

            refused("ceo uploading to " + where, FORBIDDEN,
                () -> attachmentService.upload(quotation.id(), pdf("x.pdf"), ceoActor));
        }
        refused("ceo deleting a file of a draft", FORBIDDEN, () -> attachmentService.delete(draftFile, ceoActor));
        refused("ceo deleting a file of a pending quotation", FORBIDDEN, () -> attachmentService.delete(reviewFile, ceoActor));
        assertThat(fileRows()).as("nothing stored, nothing removed").isEqualTo(rows);

        // Controls: the CEO reads the files, and the owner attaches in both statuses and deletes on the draft.
        assertThat(attachmentService.list(inReview.id(), ceoActor))
            .extracting(DealQuotationAttachmentDto::id).containsExactly(reviewFile);
        assertThat(attachmentService.upload(inDraft.id(), pdf("a.pdf"), salesActor)).isNotNull();
        assertThat(attachmentService.upload(inReview.id(), pdf("b.pdf"), salesActor)).isNotNull();
        attachmentService.delete(draftFile, salesActor);
        assertThat(rowExists(draftFile)).isFalse();
    }

    @Test
    void aFileIsGuardedByTheQuotationItIsOn_notByAnotherOneTheCallerMayEdit() {
        DealQuotationDto mine = draftOwnedBy(salesActor);
        long myFile = seed(mine.id(), "mine.pdf", VENDOR_QUOTE, salesActor);
        DealQuotationDto theirs = draftOwnedBy(otherSalesActor);
        long theirFile = seed(theirs.id(), FILE, VENDOR_QUOTE, otherSalesActor);
        DealQuotationDto myApproved = approved(deal(salesActor), salesActor);
        long approvedFile = seed(myApproved.id(), "approved.pdf", VENDOR_QUOTE, salesActor);
        long rows = fileRows();

        // Control: the caller is the editor of a DRAFT of his own.
        assertThat(quotationService.update(mine.id(), draft(), salesActor).docStatus()).isEqualTo(QuotationStatus.DRAFT);

        refused("a rep deleting a file on another rep's draft", FORBIDDEN, () -> attachmentService.delete(theirFile, salesActor));
        refused("a rep downloading a file on another rep's draft", FORBIDDEN, () -> attachmentService.download(theirFile, salesActor));
        refused("a rep deleting a file on his own APPROVED quotation", CONFLICT,
            () -> attachmentService.delete(approvedFile, salesActor));
        assertThat(fileRows()).as("nothing removed").isEqualTo(rows);

        // The list is one quotation's. No other quotation's file appears in it.
        assertThat(attachmentService.list(mine.id(), salesActor))
            .extracting(DealQuotationAttachmentDto::id).containsExactly(myFile);
        // Control: the file on his own draft is his to delete.
        attachmentService.delete(myFile, salesActor);
        assertThat(rowExists(myFile)).isFalse();
        assertThat(rowExists(theirFile)).isTrue();
        assertThat(rowExists(approvedFile)).isTrue();
    }

    // ── uploading and deleting: status ───────────────────────────────────────────────────────

    /**
     * The gate is an allow-list. This sweeps every value {@code sales.quotation.doc_status} admits
     * (V165's CHECK is exactly {@link QuotationStatus}'s constants), set directly in the table. A
     * direct quotation reaches only five of them. The sweep is here so that a status nobody thought
     * of is refused. The statuses a quotation really reaches are driven through the service in
     * {@link #aSubmittedQuotation_keepsItsFiles_throughApprovalSupersessionAndCancellation}.
     */
    @Test
    void uploadIsOpenOnlyInDraftAndPendingApproval_andDeleteOnlyInDraft_overEveryStatusTheColumnAdmits() {
        DealQuotationDto quotation = draftOwnedBy(salesActor);
        long file = seed(quotation.id(), FILE, VENDOR_QUOTE, salesActor);
        long rows = fileRows();
        List<String> statuses = everyQuotationStatus();
        assertThat(statuses).as("the sweep has the column's twelve statuses to work on").hasSize(12);

        for (String status : statuses) {
            forceStatus(quotation.id(), status);
            boolean uploadOpen = QuotationStatus.DRAFT.equals(status) || QuotationStatus.PENDING_APPROVAL.equals(status);
            boolean deleteOpen = QuotationStatus.DRAFT.equals(status);
            // The owner, and the sales manager who may edit any deal.
            for (UserPrincipal editor : List.of(salesActor, salesManagerActor)) {
                if (!uploadOpen) {
                    refused(editor.role() + " uploading while " + status, CONFLICT,
                        () -> attachmentService.upload(quotation.id(), pdf("late.pdf"), editor));
                }
                if (!deleteOpen) {
                    refused(editor.role() + " deleting while " + status, CONFLICT,
                        () -> attachmentService.delete(file, editor));
                }
            }
        }
        assertThat(fileRows()).as("nothing stored, nothing removed").isEqualTo(rows);
        assertThat(storedBytes(file)).isEqualTo(VENDOR_QUOTE);

        // Control: back in DRAFT, the same two editors do both.
        forceStatus(quotation.id(), QuotationStatus.DRAFT);
        assertThat(attachmentService.upload(quotation.id(), pdf("in-time.pdf"), salesManagerActor)).isNotNull();
        attachmentService.delete(file, salesActor);
        assertThat(rowExists(file)).isFalse();
    }

    @Test
    void aSubmittedQuotation_keepsItsFiles_throughApprovalSupersessionAndCancellation() {
        long ticket = deal(salesActor);
        DealQuotationDto first = quotationService.create(ticket, draft(), salesActor);
        long file = seed(first.id(), FILE, VENDOR_QUOTE, salesActor);

        // PENDING_APPROVAL: nothing is taken away from what the approver is looking at.
        quotationService.submit(first.id(), salesActor);
        refused("the owner deleting while PENDING_APPROVAL", CONFLICT, () -> attachmentService.delete(file, salesActor));
        refused("the sales manager deleting while PENDING_APPROVAL", CONFLICT,
            () -> attachmentService.delete(file, salesManagerActor));

        // APPROVED.
        quotationService.approve(first.id(), new ApproveRequest(null), salesManagerActor);
        refused("uploading to an APPROVED quotation", CONFLICT,
            () -> attachmentService.upload(first.id(), pdf("late.pdf"), salesActor));
        refused("deleting from an APPROVED quotation", CONFLICT, () -> attachmentService.delete(file, salesActor));

        // SUPERSEDED: its revision is approved in its place.
        DealQuotationDto revision = quotationService.createRevision(first.id(), salesActor);
        quotationService.submit(revision.id(), salesActor);
        quotationService.approve(revision.id(), new ApproveRequest(null), salesManagerActor);
        assertThat(statusOf(first.id())).isEqualTo(QuotationStatus.SUPERSEDED);
        refused("uploading to a SUPERSEDED quotation", CONFLICT,
            () -> attachmentService.upload(first.id(), pdf("late.pdf"), salesActor));
        refused("deleting from a SUPERSEDED quotation", CONFLICT, () -> attachmentService.delete(file, salesActor));

        // CANCELLED: a draft the rep gave up.
        DealQuotationDto abandoned = draftOwnedBy(salesActor);
        long abandonedFile = seed(abandoned.id(), "abandoned.pdf", VENDOR_QUOTE, salesActor);
        quotationService.cancel(abandoned.id(), new CancelRequest("ลูกค้ายกเลิก"), salesActor);
        refused("uploading to a CANCELLED quotation", CONFLICT,
            () -> attachmentService.upload(abandoned.id(), pdf("late.pdf"), salesActor));
        refused("deleting from a CANCELLED quotation", CONFLICT, () -> attachmentService.delete(abandonedFile, salesActor));

        assertThat(filesOf(first.id())).as("the approved document keeps what it was approved with").isEqualTo(1);
        assertThat(storedBytes(file)).isEqualTo(VENDOR_QUOTE);
        assertThat(storedBytes(abandonedFile)).isEqualTo(VENDOR_QUOTE);
        assertThat(filesOf(revision.id())).isZero();
    }

    // ── deleting: files a revision inherits ──────────────────────────────────────────────────

    @Test
    void aFileOfTheDocumentARevisionRevises_cannotBeDeletedByTheRevisionsEditor() {
        DealQuotationDto first = approved(deal(salesActor), salesActor);
        long inherited = seed(first.id(), FILE, VENDOR_QUOTE, salesActor);
        DealQuotationDto revision = quotationService.createRevision(first.id(), salesActor);
        long own = seed(revision.id(), "own.pdf", VENDOR_QUOTE, salesActor);

        // Control: the revision is a DRAFT these callers may edit.
        assertThat(quotationService.update(revision.id(), draft(), salesActor).docStatus()).isEqualTo(QuotationStatus.DRAFT);

        for (UserPrincipal editor : List.of(salesActor, salesManagerActor)) {
            refused(editor.role() + " deleting the earlier document's file", CONFLICT,
                () -> attachmentService.delete(inherited, editor));
        }
        assertThat(storedBytes(inherited)).isEqualTo(VENDOR_QUOTE);

        // Controls: the revision does show that file, as inherited, and its OWN file is deletable.
        assertThat(attachmentService.list(revision.id(), salesActor))
            .extracting(DealQuotationAttachmentDto::id, DealQuotationAttachmentDto::inherited)
            .containsExactly(tuple(own, false), tuple(inherited, true));
        attachmentService.delete(own, salesActor);
        assertThat(rowExists(own)).isFalse();
    }

    /**
     * Resubmitting a ตีกลับ'd draft mints the revision and leaves the rejected row itself a DRAFT
     * until that revision is approved ({@code DealQuotationService#submitAsRevisionOfRejected}).
     * "Delete only while DRAFT" alone would therefore let a rep delete a file that the approver is
     * reading on the pending revision. So a document with a revision open gives up no file.
     * {@code DealQuotationService#cancel} already refuses on the same condition.
     */
    @Test
    void aFileOfARejectedDraft_cannotBeDeleted_whileItsResubmissionIsOpen() {
        DealQuotationDto rejected = draftOwnedBy(salesActor);
        long file = seed(rejected.id(), FILE, VENDOR_QUOTE, salesActor);
        quotationService.submit(rejected.id(), salesActor);
        quotationService.reject(rejected.id(), new RejectRequest("ราคาสูงเกินไป"), salesManagerActor);
        DealQuotationDto resubmission = quotationService.submit(rejected.id(), salesActor);

        // The premise: a new document is with the approver, and the rejected row is still a DRAFT.
        assertThat(resubmission.parentQuotationId()).isEqualTo(rejected.id());
        assertThat(resubmission.docStatus()).isEqualTo(QuotationStatus.PENDING_APPROVAL);
        assertThat(statusOf(rejected.id())).isEqualTo(QuotationStatus.DRAFT);

        refused("the owner deleting a file its pending revision shows the approver", CONFLICT,
            () -> attachmentService.delete(file, salesActor));
        refused("the sales manager deleting it", CONFLICT, () -> attachmentService.delete(file, salesManagerActor));
        assertThat(storedBytes(file)).isEqualTo(VENDOR_QUOTE);
        assertThat(attachmentService.list(resubmission.id(), ceoActor))
            .extracting(DealQuotationAttachmentDto::id, DealQuotationAttachmentDto::inherited)
            .containsExactly(tuple(file, true));

        // Control: a rejected draft that has NOT been resubmitted is an ordinary draft.
        DealQuotationDto onlyRejected = draftOwnedBy(salesActor);
        long removable = seed(onlyRejected.id(), "wrong-file.pdf", VENDOR_QUOTE, salesActor);
        quotationService.submit(onlyRejected.id(), salesActor);
        quotationService.reject(onlyRejected.id(), new RejectRequest("แก้ราคา"), salesManagerActor);
        attachmentService.delete(removable, salesActor);
        assertThat(rowExists(removable)).isFalse();
    }

    /**
     * The same rule under a race, which the test above cannot show. A resubmission and a delete can
     * overlap, and the rejected row is a DRAFT before, during and after, so the DRAFT condition in
     * the delete statement stops nothing. What closes it is the deal's lock, which
     * {@code DealQuotationService#cancel} takes before it looks for an open revision.
     *
     * <p>Two real transactions on two connections. The resubmission is the real
     * {@code DealQuotationService#submit}, left uncommitted, so it holds the lock it takes in
     * production. The delete goes through {@link #transactional}, as it runs in production. This
     * order does not depend on that: a bare delete still waits for a lock someone else holds. The
     * next test is the order that does.
     */
    @Test
    void aDeleteWaitsForAResubmissionInFlight_andThenLeavesTheFileItsRevisionShows() throws Exception {
        DealQuotationDto rejected = draftOwnedBy(salesActor);
        long file = seed(rejected.id(), FILE, VENDOR_QUOTE, salesActor);
        long spare = seed(rejected.id(), "spare.pdf", VENDOR_QUOTE, salesActor);
        quotationService.submit(rejected.id(), salesActor);
        quotationService.reject(rejected.id(), new RejectRequest("ราคาสูงเกินไป"), salesManagerActor);
        DealQuotationAttachmentService inItsOwnTransaction = transactional(attachmentService);

        // Control: with no resubmission in flight, this caller deletes a file of this draft this way.
        inItsOwnTransaction.delete(spare, salesActor);
        assertThat(rowExists(spare)).isFalse();

        ExecutorService otherConnection = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> delete = transactionTemplate.execute(resubmitting -> {
                DealQuotationDto resubmission = quotationService.submit(rejected.id(), salesActor);
                assertThat(resubmission.parentQuotationId()).isEqualTo(rejected.id());

                Future<Throwable> started = otherConnection.submit(
                    () -> catchThrowable(() -> inItsOwnTransaction.delete(file, salesActor)));
                assertThat(isOverWithin(started, 1_000))
                    .as("whether the delete is over while the resubmission is still uncommitted").isFalse();
                return started;
            });

            // The resubmission has committed. The delete now gets the lock, and sees the revision.
            Throwable outcome = delete.get(10, TimeUnit.SECONDS);
            refused("the owner deleting a file while its resubmission lands", CONFLICT, () -> {
                if (outcome != null) {
                    throw outcome;
                }
            });
        } finally {
            otherConnection.shutdownNow();
        }
        assertThat(storedBytes(file)).isEqualTo(VENDOR_QUOTE);
    }

    /**
     * The other order: the delete is first. It has passed its check and holds the deal's lock, so
     * a resubmission must wait until it has committed. What holds the lock that long is
     * {@code @Transactional} on {@code DealQuotationAttachmentService#delete}. Without it the lock
     * is given up at the end of its own statement, a resubmission mints its revision in the gap,
     * and the delete then removes a file that revision lists.
     */
    @Test
    void aResubmissionWaitsForADeleteInFlight_soNoRevisionListsAFileThatIsAboutToGo() throws Exception {
        DealQuotationDto rejected = draftOwnedBy(salesActor);
        long file = seed(rejected.id(), FILE, VENDOR_QUOTE, salesActor);
        quotationService.submit(rejected.id(), salesActor);
        quotationService.reject(rejected.id(), new RejectRequest("ราคาสูงเกินไป"), salesManagerActor);

        // The delete, stopped after its checks and just before its statement.
        CountDownLatch atItsStatement = new CountDownLatch(1);
        CountDownLatch goOn = new CountDownLatch(1);
        DealQuotationAttachmentRepository stopsBeforeDeleting = new DealQuotationAttachmentRepository(
                jdbc, new FileAttachmentBlobRepository(jdbc)) {
            @Override
            public int deleteFromDraft(long attachmentId) {
                atItsStatement.countDown();
                try {
                    goOn.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return super.deleteFromDraft(attachmentId);
            }
        };
        DealQuotationAttachmentService deleting = transactional(
            new DealQuotationAttachmentService(quotationService, stopsBeforeDeleting, fileStorage));
        DealQuotationService resubmitting = transactional(quotationService);

        ExecutorService twoConnections = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> delete = twoConnections.submit(
                () -> catchThrowable(() -> deleting.delete(file, salesActor)));
            assertThat(atItsStatement.await(10, TimeUnit.SECONDS)).as("the delete passed its checks").isTrue();

            Future<DealQuotationDto> resubmit = twoConnections.submit(
                () -> resubmitting.submit(rejected.id(), salesActor));
            boolean resubmittedMeanwhile = isOverWithin(resubmit, 1_500);
            goOn.countDown();

            assertThat(delete.get(15, TimeUnit.SECONDS)).as("the delete, which was first, goes through").isNull();
            DealQuotationDto revision = resubmit.get(15, TimeUnit.SECONDS);
            assertThat(resubmittedMeanwhile)
                .as("whether the resubmission went ahead while the delete was between its check and its statement")
                .isFalse();
            assertThat(rowExists(file)).isFalse();
            assertThat(attachmentService.list(revision.id(), ceoActor))
                .as("the revision never listed the file").isEmpty();
        } finally {
            goOn.countDown();
            twoConnections.shutdownNow();
        }
    }

    /** Whether {@code task} is over within {@code millis}. One that waits for a lock is not. */
    private static boolean isOverWithin(Future<?> task, long millis) {
        try {
            task.get(millis, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException stillWaiting) {
            return false;
        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── another domain's file ────────────────────────────────────────────────────────────────

    /**
     * {@code hr.file_attachment} is a shared table. Leave certificates, tax evidence and factory
     * quotes live in it, and {@code owner_id} carries no foreign key, so a leave request and a
     * quotation routinely share a number. Both foreign rows below are written by their own domain's
     * repository and filed under an owner id EQUAL to the quotation's. A guard that forgot the
     * domain would resolve that id to a quotation this caller may edit.
     */
    @Test
    void aFileOfAnotherDomain_is404Here_andItsBytesNeverComeBack() {
        DealQuotationDto quotation = draftOwnedBy(salesActor);
        long own = seed(quotation.id(), FILE, VENDOR_QUOTE, salesActor);
        byte[] certificate = "medical certificate".getBytes(StandardCharsets.UTF_8);
        byte[] factoryCost = "factory cost sheet".getBytes(StandardCharsets.UTF_8);
        long leaveFile = new LeaveAttachmentRepository(jdbc).saveWithContent(quotation.id(), "ใบรับรองแพทย์.pdf",
            "leave/" + quotation.id() + "/certificate.pdf", PDF, (long) certificate.length, employeeActor.id(),
            certificate).id();
        long factoryFile = new FactoryQuoteRepository(jdbc).saveAttachmentWithContent(quotation.id(), "factory-cost.pdf",
            "factory-quotes/" + quotation.id() + "/cost.pdf", PDF, (long) factoryCost.length, importActor.id(),
            factoryCost).id();

        for (long foreign : List.of(leaveFile, factoryFile)) {
            for (UserPrincipal reader : List.of(salesActor, salesManagerActor, ceoActor, grantedQcActor)) {
                refused(reader.role() + " downloading another domain's file " + foreign, NOT_FOUND,
                    () -> attachmentService.download(foreign, reader));
            }
            for (UserPrincipal editor : List.of(salesActor, salesManagerActor, grantedQcActor)) {
                refused(editor.role() + " deleting another domain's file " + foreign, NOT_FOUND,
                    () -> attachmentService.delete(foreign, editor));
            }
        }
        assertThat(storedBytes(leaveFile)).as("the leave certificate is untouched").isEqualTo(certificate);
        assertThat(storedBytes(factoryFile)).as("the factory quote file is untouched").isEqualTo(factoryCost);

        // The list is this feature's rows only, although three rows share the quotation's owner id.
        assertThat(attachmentService.list(quotation.id(), salesActor))
            .extracting(DealQuotationAttachmentDto::id).containsExactly(own);
        // Control: the same callers do reach the quotation's own file.
        assertThat(served(own, ceoActor)).isEqualTo(VENDOR_QUOTE);
    }

    // ── what is refused whoever asks ─────────────────────────────────────────────────────────

    @Test
    void theEleventhFile_isRefused_andNothingIsStored() {
        DealQuotationDto full = draftOwnedBy(salesActor);
        for (int i = 1; i <= 10; i++) {
            seed(full.id(), "file-" + i + ".pdf", VENDOR_QUOTE, salesActor);
        }
        long rows = fileRows();
        long blobs = blobRows();

        ApiException refusal = refused("the eleventh file", CONFLICT,
            () -> attachmentService.upload(full.id(), pdf("file-11.pdf"), salesActor));
        assertThat(refusal.getMessage()).as("the refusal states the limit").contains("10");
        assertThat(fileRows()).isEqualTo(rows);
        assertThat(blobRows()).isEqualTo(blobs);

        // Control: the tenth is accepted, and a leave file filed under the same owner id is not
        // one of the quotation's ten.
        DealQuotationDto nearlyFull = draftOwnedBy(salesActor);
        for (int i = 1; i <= 9; i++) {
            seed(nearlyFull.id(), "file-" + i + ".pdf", VENDOR_QUOTE, salesActor);
        }
        new LeaveAttachmentRepository(jdbc).saveWithContent(nearlyFull.id(), "ใบรับรองแพทย์.pdf",
            "leave/" + nearlyFull.id() + "/certificate.pdf", PDF, 3L, employeeActor.id(), new byte[] {1, 2, 3});
        assertThat(attachmentService.upload(nearlyFull.id(), pdf("file-10.pdf"), salesActor)).isNotNull();
        assertThat(filesOf(nearlyFull.id())).isEqualTo(10);
    }

    @Test
    void anEmptyFile_anOversizedFile_andATypeThatIsNotAllowed_areRefused_andNothingIsStored() {
        DealQuotationDto quotation = draftOwnedBy(salesActor);
        long rows = fileRows();
        long blobs = blobRows();
        byte[] overTheCap = new byte[Math.toIntExact(fileStorage.maxUploadBytes()) + 1];

        refused("an empty file", BAD_REQUEST,
            () -> attachmentService.upload(quotation.id(), file("empty.pdf", PDF, new byte[0]), salesActor));
        refused("a file one byte over FileStorageService's cap", BAD_REQUEST,
            () -> attachmentService.upload(quotation.id(), file("huge.pdf", PDF, overTheCap), salesActor));
        for (String type : List.of("text/html", "application/zip", "application/x-msdownload")) {
            refused("a " + type + " file", BAD_REQUEST,
                () -> attachmentService.upload(quotation.id(), file("vendor-quote", type, VENDOR_QUOTE), salesActor));
        }
        // A file with no declared type and an extension nothing recognises is refused. It is never
        // a server error.
        refused("a file that declares no type", BAD_REQUEST,
            () -> attachmentService.upload(quotation.id(), file("vendor-quote.unknownext", null, VENDOR_QUOTE), salesActor));
        assertThat(fileRows()).as("nothing stored").isEqualTo(rows);
        assertThat(blobRows()).isEqualTo(blobs);

        // Control: the three allowed types are each accepted, same quotation, same caller.
        for (String type : List.of(PDF, "image/jpeg", "image/png")) {
            assertThat(attachmentService.upload(quotation.id(), file("vendor-quote", type, VENDOR_QUOTE), salesActor))
                .as(type).isNotNull();
        }
        assertThat(filesOf(quotation.id())).isEqualTo(3);
    }

    private static List<String> everyQuotationStatus() {
        List<String> statuses = new ArrayList<>();
        for (Field field : QuotationStatus.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                try {
                    statuses.add((String) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return statuses;
    }
}
