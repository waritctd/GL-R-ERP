package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.auth.UserPrincipal;

/**
 * Step 1 of CLAUDE.md's "Permission changes must ship evidence": unit-test the DECISION. The
 * real-Postgres sibling {@code th.co.glr.hr.attachment.AttachmentTicketAccessIntegrationTest}
 * proves the same decision actually survives into the running controller and its SQL; this file
 * proves the branch itself is chosen correctly, exhaustively, for every role
 * {@code DivisionAccessPolicy} can mint.
 *
 * <p>Issue #389: written wrong-way-round throughout — "hr cannot read a deal's documents" and
 * "account cannot upload one" are the assertions that matter, not their positive counterparts.
 */
class TicketAccessPolicyTest {
    private static final long CREATOR_ID = 2L;
    private static final long ASSIGNEE_ID = 3L;
    private static final long STRANGER_ID = 4L;

    // Every role DivisionAccessPolicy.roleFor can return.
    private static final String[] ALL_ROLES = {
        "ceo", "hr", "import", "account", "warehouse", "qc", "sales_manager", "sales", "employee"
    };

    // ── the defect, both directions ───────────────────────────────────────────────────────

    @Test
    void hrCannotReadTheDocumentsOfADealItDoesNotParticipateIn() {
        assertThat(TicketAccessPolicy.canViewDocuments(summary(), stranger("hr"))).isFalse();
    }

    @Test
    void hrCannotWriteTheDocumentsOfADealItDoesNotParticipateIn() {
        assertThat(TicketAccessPolicy.canManageDocuments(summary(), stranger("hr"))).isFalse();
    }

    @Test
    void accountCanReadTheDocumentItMustConfirmMoneyAgainst() {
        assertThat(TicketAccessPolicy.canViewDocuments(summary(), stranger("account"))).isTrue();
    }

    @Test
    void accountStillCannotWriteDocuments_theTaxInvoiceHasExactlyOneEntryPoint() {
        // createFromDeal dual-writes the INVOICE attachment AND the rep's commission in one
        // transaction; a second write path here would satisfy the close gate's invoiceOnFile
        // check while the rep silently loses their commission.
        assertThat(TicketAccessPolicy.canManageDocuments(summary(), stranger("account"))).isFalse();
    }

    // ── the rest of the matrix, wrong-way-round ───────────────────────────────────────────

    @Test
    void aSalesRepWhoIsNeitherCreatorNorAssigneeCannotReadOrWrite() {
        UserPrincipal other = stranger("sales");
        assertThat(TicketAccessPolicy.canViewDocuments(summary(), other)).isFalse();
        assertThat(TicketAccessPolicy.canManageDocuments(summary(), other)).isFalse();
    }

    @Test
    void nonSalesStackRolesReachNothing() {
        for (String role : new String[]{"employee", "warehouse", "qc", "hr"}) {
            assertThat(TicketAccessPolicy.canViewDocuments(summary(), stranger(role)))
                .as("canViewDocuments for role %s", role).isFalse();
            assertThat(TicketAccessPolicy.canManageDocuments(summary(), stranger(role)))
                .as("canManageDocuments for role %s", role).isFalse();
        }
    }

    /**
     * THE PIN for import's refusal (issue #389 review), at the decision layer. import IS in
     * {@link TicketAccessPolicy#VIEWER_ROLES} — it reads the deal shell — and is still refused the
     * deal's documents, because {@code AttachType} spans SIGNED_QUOTATION/INVOICE and those carry
     * the approved customer price that {@code projectForRole}, {@code loadQuotationContext},
     * {@code listPayments} and {@code DepositNoticeService} all withhold from import.
     *
     * <p>An earlier revision of this branch granted the read on the reasoning "import already
     * reads the deal"; review refuted it. If this test goes red, that widening is back.
     */
    @Test
    void importIsRefusedDocumentsDespiteBeingAViewerRole() {
        assertThat(TicketAccessPolicy.VIEWER_ROLES)
            .as("precondition: import reads the deal shell, which is exactly why this pin matters")
            .contains("import");
        assertThat(TicketAccessPolicy.canViewDocuments(summary(), stranger("import"))).isFalse();
        assertThat(TicketAccessPolicy.canManageDocuments(summary(), stranger("import"))).isFalse();
    }

    @Test
    void importRegainsAccessOnlyOnADealItActuallyPickedUp() {
        // The assignee IS the import user who picked the deal up — a participant, not a role grant.
        assertThat(TicketAccessPolicy.canViewDocuments(summary(), actor(ASSIGNEE_ID, "import"))).isTrue();
        assertThat(TicketAccessPolicy.canManageDocuments(summary(), actor(ASSIGNEE_ID, "import"))).isTrue();
    }

    @Test
    void writeAccessIsNeverWiderThanReadAccess() {
        for (String role : ALL_ROLES) {
            for (UserPrincipal actor :
                    new UserPrincipal[]{stranger(role), actor(CREATOR_ID, role), actor(ASSIGNEE_ID, role)}) {
                if (TicketAccessPolicy.canManageDocuments(summary(), actor)) {
                    assertThat(TicketAccessPolicy.canViewDocuments(summary(), actor))
                        .as("role %s may write but not read — write must imply read", role)
                        .isTrue();
                }
            }
        }
    }

    @Test
    void aNullActorReachesNothing() {
        assertThat(TicketAccessPolicy.canViewDocuments(summary(), null)).isFalse();
        assertThat(TicketAccessPolicy.canManageDocuments(summary(), null)).isFalse();
        assertThat(TicketAccessPolicy.isParticipant(summary(), null)).isFalse();
    }

    // ── positive controls: the roles whose job this is keep their access ──────────────────

    @Test
    void participantsAndOversightRolesKeepFullDocumentAccess() {
        for (UserPrincipal actor : new UserPrincipal[]{
                actor(CREATOR_ID, "sales"),          // the deal's own rep
                actor(ASSIGNEE_ID, "import"),        // whoever picked it up
                stranger("sales_manager"),
                stranger("ceo")}) {
            assertThat(TicketAccessPolicy.canViewDocuments(summary(), actor))
                .as("canViewDocuments for %s", actor.role()).isTrue();
            assertThat(TicketAccessPolicy.canManageDocuments(summary(), actor))
                .as("canManageDocuments for %s", actor.role()).isTrue();
        }
    }

    @Test
    void theDealViewerRoleSetIsTheOneTicketServiceUses() {
        // The drift that produced #389 was AttachmentController keeping its own copy of "who may
        // reach a deal". This is now the single constant TicketService.VIEWER_ROLES aliases.
        // NOTE: it is the DEAL-read set, not the document-read set — canViewDocuments narrows it
        // further for sales and import (see importIsRefusedDocumentsDespiteBeingAViewerRole).
        assertThat(TicketAccessPolicy.VIEWER_ROLES)
            .containsExactlyInAnyOrder("sales", "import", "ceo", "account", "sales_manager")
            .doesNotContain("hr");
    }

    @Test
    void anUnassignedDealHasNoSecondParticipant() {
        TicketSummaryDto unassigned = summary(null);
        assertThat(TicketAccessPolicy.isParticipant(unassigned, actor(ASSIGNEE_ID, "import"))).isFalse();
        assertThat(TicketAccessPolicy.canManageDocuments(unassigned, actor(ASSIGNEE_ID, "import"))).isFalse();
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────

    private TicketSummaryDto summary() {
        return summary(ASSIGNEE_ID);
    }

    private TicketSummaryDto summary(Long assignedToId) {
        return new TicketSummaryDto(
            10L, "PR-2026-0001", "SALES", "Ticket title", "approved", "NORMAL",
            CREATOR_ID, "Creator Name",
            assignedToId, assignedToId == null ? null : "Assignee Name",
            "Customer", null, null, null, null, null,
            null,
            Instant.parse("2026-07-01T00:00:00Z"),
            Instant.parse("2026-07-01T00:00:00Z"),
            null,
            0, false, null, null,
            "LEAD_APPROACH", null, null, Instant.parse("2026-07-01T00:00:00Z"),
            "ACTIVE", "UNKNOWN", "REQUIRED", null, "DESIGNER_LED");
    }

    private UserPrincipal stranger(String role) {
        return actor(STRANGER_ID, role);
    }

    private UserPrincipal actor(long userId, String role) {
        return new UserPrincipal(userId, "user" + userId + "@glr.co.th", "User " + userId, role,
            userId, true, LocalDate.of(2026, 1, 1), false, 1L, false);
    }
}
