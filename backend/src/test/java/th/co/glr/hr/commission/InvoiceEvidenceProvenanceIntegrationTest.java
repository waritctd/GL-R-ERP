package th.co.glr.hr.commission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * V102 ({@code sales.invoice_details.evidence_provenance} +
 * {@code chk_invoice_details_evidence_present}) — real-Postgres coverage for the CHECK constraint
 * that records ข้อ 15 evidence going forward. See this branch's PR body (the durable record per
 * the current CLAUDE.md, now that the handoff corpus is retired) for the full decision history: the
 * ~1,100 rows from the 2026 historical commission import are genuine PRE-ERP PAPER APPROVALS
 * (owner's words: "the invoice was before we built the ERP, it's already all approved, so I would
 * like to record it") — not missing evidence to gate, evidence that predates the system's ability
 * to scan/attach it. V102 RECORDS that fact (a named {@code evidence_provenance}) rather than
 * gating payment on it, backfills every existing historical row inline (owner decision 2026-07-30
 * — see the migration file's comments for why that could not wait), and enforces going forward
 * that every {@code sales.invoice_details} row show ONE of: a real attachment, or a provenance
 * from a closed allow-list ({@code PRE_ERP_PAPER_APPROVAL} or the transient write-path sentinel
 * {@code PENDING_ATTACHMENT} — see {@link CommissionRepository#PENDING_ATTACHMENT_SENTINEL}'s
 * Javadoc) — NOT any non-null string, which would have let blank/whitespace/noise values through.
 *
 * <p>Because the inline backfill runs before the CHECK is added, V102 itself does NOT need {@code
 * NOT VALID} — every existing row already satisfies the CHECK by the time it is added, so a plain,
 * fully-validated {@code ADD CONSTRAINT} is correct and requires no follow-up VALIDATE migration.
 * {@link #preExistingAttachmentlessRow_isGrandfathered_becauseConstraintIsNotValid()} below still
 * exercises the {@code NOT VALID} technique directly (drop/insert/re-add), as a standalone proof of
 * general Postgres behavior worth keeping on record — not because V102's own migration relies on
 * it any more.
 *
 * <p>Every test here runs against the real, fully-migrated schema (V102 included) via {@link
 * th.co.glr.hr.support.AbstractPostgresIntegrationTest} — the CHECK constraint's exact behavior can
 * only be proven against a real Postgres, not Mockito.
 */
class InvoiceEvidenceProvenanceIntegrationTest extends th.co.glr.hr.support.AbstractPostgresIntegrationTest {
    private static final String CONSTRAINT_NAME = "chk_invoice_details_evidence_present";

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Wrong-way-round: a NEW row with neither evidence route is rejected outright.
    //
    // MUTATION-CHECK RECORD: temporarily changed V102's constraint definition (reproduced here
    // via a DROP + re-ADD without the CHECK's OR clause, i.e. requiring invoice_attachment_id
    // unconditionally -- a stand-in for "someone deleted the constraint entirely") by dropping
    // chk_invoice_details_evidence_present and not re-adding it at all before running this test:
    // the INSERT that should have been rejected instead SUCCEEDED (row count went from N to N+1),
    // and this specific test went red (expected DataIntegrityViolationException, got no
    // exception). No other test in this class was affected. Reverted (re-ran the fully-migrated
    // schema via the normal @BeforeEach reset, which re-applies V102 including the constraint);
    // confirmed green again immediately after.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void newInvoiceDetailsRow_withNeitherAttachmentNorProvenance_isRejected() {
        int before = countInvoiceDetails();

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO sales.invoice_details (invoice_number, invoice_date, gross_amount)
                VALUES (:invoiceNumber, :invoiceDate, :grossAmount)
                """,
                new MapSqlParameterSource()
                    .addValue("invoiceNumber", "V102-REJECT-" + UUID.randomUUID())
                    .addValue("invoiceDate", LocalDate.of(2026, 7, 1))
                    .addValue("grossAmount", new BigDecimal("100000.00"))))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining(CONSTRAINT_NAME);

        assertThat(countInvoiceDetails()).isEqualTo(before);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // F4 (2026-07-30, reviewer finding): the CHECK used during development accepted ANY non-null
    // string in evidence_provenance -- '', ' ', 'x' all satisfied `evidence_provenance IS NOT
    // NULL`, so the constraint could not actually distinguish real provenance from noise.
    // Tightened to a closed allow-list (PRE_ERP_PAPER_APPROVAL, PENDING_ATTACHMENT). Wrong-way-
    // round: each of these must still be REJECTED, exactly like the NULL case above.
    //
    // MUTATION-CHECK RECORD: temporarily widened V102's CHECK back to `evidence_provenance IS NOT
    // NULL` (dropping the allow-list, reproducing the exact pre-F4 constraint) by dropping
    // chk_invoice_details_evidence_present and re-adding that looser version. All three tests
    // below went red (expected DataIntegrityViolationException, INSERT succeeded instead) and no
    // other test in this class was affected. Reverted (normal @BeforeEach reset re-applies V102's
    // real, tightened constraint); confirmed green again immediately after.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void newInvoiceDetailsRow_withBlankStringProvenance_isRejected() {
        assertProvenanceValueIsRejected("");
    }

    @Test
    void newInvoiceDetailsRow_withWhitespaceOnlyProvenance_isRejected() {
        assertProvenanceValueIsRejected(" ");
    }

    @Test
    void newInvoiceDetailsRow_withUnrecognizedProvenance_isRejected() {
        // 'x' is exactly the kind of stray/typo'd value a hand-run UPDATE could introduce -- not
        // blank, not whitespace, just not one of the four real categories this column recognizes
        // (PRE_ERP_PAPER_APPROVAL, PENDING_ATTACHMENT, UAT_SEED, DEMO_SEED).
        assertProvenanceValueIsRejected("x");
    }

    private void assertProvenanceValueIsRejected(String provenanceValue) {
        int before = countInvoiceDetails();

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO sales.invoice_details (invoice_number, invoice_date, gross_amount, evidence_provenance)
                VALUES (:invoiceNumber, :invoiceDate, :grossAmount, :evidenceProvenance)
                """,
                new MapSqlParameterSource()
                    .addValue("invoiceNumber", "V102-BADVALUE-" + UUID.randomUUID())
                    .addValue("invoiceDate", LocalDate.of(2026, 7, 1))
                    .addValue("grossAmount", new BigDecimal("100000.00"))
                    .addValue("evidenceProvenance", provenanceValue)))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining(CONSTRAINT_NAME);

        assertThat(countInvoiceDetails()).isEqualTo(before);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Right-way-round: either evidence route alone is sufficient.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void newInvoiceDetailsRow_withOnlyEvidenceProvenance_isAccepted() {
        // The pre-ERP case going forward would never actually happen for a NEW row (the
        // historical import is a one-time, already-completed event) -- but the constraint must
        // accept this shape on its own terms, independent of attachment, exactly as written.
        int before = countInvoiceDetails();

        jdbc.update("""
            INSERT INTO sales.invoice_details (invoice_number, invoice_date, gross_amount, evidence_provenance)
            VALUES (:invoiceNumber, :invoiceDate, :grossAmount, 'PRE_ERP_PAPER_APPROVAL')
            """,
            new MapSqlParameterSource()
                .addValue("invoiceNumber", "V102-PROVENANCE-" + UUID.randomUUID())
                .addValue("invoiceDate", LocalDate.of(2026, 7, 1))
                .addValue("grossAmount", new BigDecimal("100000.00")));

        assertThat(countInvoiceDetails()).isEqualTo(before + 1);
    }

    @Test
    void newInvoiceDetailsRow_withUatSeedProvenance_isAccepted() {
        // Added 2026-07-30 after cross-checking against the uat branch's own fix
        // (fix/uat-seed-invoice-evidence @ 36925ae8): db/migration-uat/V903__uat_sales.sql and
        // V910__uat_golden_pcr_deal.sql insert sales.invoice_details rows with no attachment and
        // supply UAT_SEED -- synthetic fixture data, no receipt exists at all (distinct from
        // PRE_ERP_PAPER_APPROVAL, which is a real historical approval). This proves the allow-
        // list actually accepts it, not just that the uat-side migration compiles.
        assertProvenanceValueIsAccepted("UAT_SEED");
    }

    @Test
    void newInvoiceDetailsRow_withDemoSeedProvenance_isAccepted() {
        // Reserved, not yet consumed by any migration (db/migration-demo/V21 is currently safe by
        // version luck, not by using this value) -- allow-listed now so a future renumbering of
        // the demo seed above V102 has somewhere to point instead of silently hitting the CHECK.
        assertProvenanceValueIsAccepted("DEMO_SEED");
    }

    private void assertProvenanceValueIsAccepted(String provenanceValue) {
        int before = countInvoiceDetails();

        jdbc.update("""
            INSERT INTO sales.invoice_details (invoice_number, invoice_date, gross_amount, evidence_provenance)
            VALUES (:invoiceNumber, :invoiceDate, :grossAmount, :evidenceProvenance)
            """,
            new MapSqlParameterSource()
                .addValue("invoiceNumber", "V102-" + provenanceValue + "-" + UUID.randomUUID())
                .addValue("invoiceDate", LocalDate.of(2026, 7, 1))
                .addValue("grossAmount", new BigDecimal("100000.00"))
                .addValue("evidenceProvenance", provenanceValue));

        assertThat(countInvoiceDetails()).isEqualTo(before + 1);
    }

    @Test
    void newInvoiceDetailsRow_withOnlyAttachment_isAccepted() {
        // The normal, everyday app case: CommissionService#submit/#createFromDeal always attach
        // a real file (via CommissionAttachmentRepository#save + #attachInvoiceFile) and end with
        // evidence_provenance NULL / invoice_attachment_id set -- this proves that path still
        // works end-to-end under V102.
        //
        // NOTE on CommissionRepository#createInvoice: a plain CHECK is not deferrable to commit,
        // but submit()/createFromDeal() must insert the bare row BEFORE the file can be stored
        // (storage needs the row's generated invoice_id first). createInvoice() therefore writes
        // a transient PENDING_ATTACHMENT sentinel into evidence_provenance so THAT statement also
        // satisfies the constraint; attachInvoiceFile() clears it in the same statement that sets
        // the real attachment. Only the FINAL, fully-attached state is asserted here -- the
        // transient sentinel is intentionally private to CommissionRepository (see its Javadoc)
        // and not something this test (or any other caller) should assert on directly.
        CommissionRepository commissions = new CommissionRepository(jdbc);
        SubmitCommissionRequest request = new SubmitCommissionRequest(
            null, 1L, "V102-ATTACHMENT-" + UUID.randomUUID(), LocalDate.of(2026, 7, 1),
            new BigDecimal("100000.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        long invoiceId = commissions.createInvoice(request);
        long attachmentId = insertFileAttachment(invoiceId);
        commissions.attachInvoiceFile(invoiceId, attachmentId);

        Long attachmentIdColumn = jdbc.queryForObject(
            "SELECT invoice_attachment_id FROM sales.invoice_details WHERE invoice_id = :id",
            Map.of("id", invoiceId), Long.class);
        String provenanceColumn = jdbc.queryForObject(
            "SELECT evidence_provenance FROM sales.invoice_details WHERE invoice_id = :id",
            Map.of("id", invoiceId), String.class);
        assertThat(attachmentIdColumn).isNotNull();
        assertThat(provenanceColumn).isNull();
    }

    @Test
    void createInvoiceAlone_withoutAttachInvoiceFile_wouldLeaveThePendingSentinel_soCallersMustAlwaysAttach() {
        // Documents WHY CommissionRepository#createInvoice needs the PENDING_ATTACHMENT sentinel
        // at all: without it, this exact statement -- the real INSERT CommissionService#submit/
        // #createFromDeal issue before a file can be stored -- would violate the CHECK immediately
        // and break every commission submission. Both real callers always follow with
        // attachInvoiceFile() in the same transaction (never leaving this state committed); this
        // test only proves the intermediate, uncommitted row is valid on its own terms.
        CommissionRepository commissions = new CommissionRepository(jdbc);
        SubmitCommissionRequest request = new SubmitCommissionRequest(
            null, 1L, "V102-SENTINEL-" + UUID.randomUUID(), LocalDate.of(2026, 7, 1),
            new BigDecimal("100000.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        long invoiceId = commissions.createInvoice(request);

        String provenanceColumn = jdbc.queryForObject(
            "SELECT evidence_provenance FROM sales.invoice_details WHERE invoice_id = :id",
            Map.of("id", invoiceId), String.class);
        Long attachmentIdColumn = jdbc.queryForObject(
            "SELECT invoice_attachment_id FROM sales.invoice_details WHERE invoice_id = :id",
            Map.of("id", invoiceId), Long.class);
        assertThat(provenanceColumn).isNotNull(); // the transient sentinel -- not a real category
        assertThat(attachmentIdColumn).isNull();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Grandfathering technique: NOT VALID tolerates a pre-existing violating row.
    //
    // REVISED 2026-07-30 (F7 follow-on): V102's OWN migration no longer uses NOT VALID -- its
    // inline backfill (owner decision, see the migration file) sets evidence_provenance on every
    // one of the ~1,100 real historical rows BEFORE the CHECK is added, so by ADD CONSTRAINT time
    // every existing row already satisfies it and a plain, fully-validated CHECK is correct and
    // sufficient. This test no longer mirrors what V102 itself does. It is kept anyway as a
    // standalone, real-Postgres proof of the NOT VALID technique in general -- worth having on
    // record in case a FUTURE migration ever needs to grandfather a violating row again (e.g. a
    // backfill that, unlike V102's, cannot run inline for some reason) -- reproduced here by
    // dropping the real constraint, inserting a violating row, then re-adding an EQUIVALENT
    // constraint with NOT VALID, standing in for "a row that predates enforcement".
    //
    // MUTATION-CHECK RECORD: re-ran this test's re-ADD step WITHOUT "NOT VALID" (a plain
    // `ADD CONSTRAINT ... CHECK (...)`, which Postgres validates against every existing row
    // immediately) over the exact same seeded violating row: the ADD CONSTRAINT statement itself
    // threw (Postgres refuses to add a validated CHECK that an existing row violates), so this
    // test went red at the "re-add" step instead of reaching its assertions. This is the direct,
    // real-Postgres proof of what NOT VALID buys: grandfathering a row that could not otherwise be
    // added under a validated CHECK. Reverted back to NOT VALID; confirmed green again.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void preExistingAttachmentlessRow_isGrandfathered_becauseConstraintIsNotValid() {
        // A freshly-migrated test database already has V102 (and its constraint) applied before
        // this test runs, so a violating row can no longer be inserted normally -- exactly the
        // protection this constraint is for. To reproduce "a row that predates enforcement" (the
        // general shape V102's own inline backfill exists to avoid ever needing NOT VALID for),
        // drop the constraint, insert the violating row, then re-add an equivalent constraint with
        // NOT VALID -- the technique V102 could have used, but no longer needs to.
        jdbc.update("ALTER TABLE sales.invoice_details DROP CONSTRAINT " + CONSTRAINT_NAME, Map.of());
        Long invoiceId = jdbc.queryForObject("""
            INSERT INTO sales.invoice_details (invoice_number, invoice_date, gross_amount)
            VALUES (:invoiceNumber, :invoiceDate, :grossAmount)
            RETURNING invoice_id
            """,
            new MapSqlParameterSource()
                .addValue("invoiceNumber", "V102-LEGACY-" + UUID.randomUUID())
                .addValue("invoiceDate", LocalDate.of(2026, 1, 1))
                .addValue("grossAmount", new BigDecimal("50000.00")),
            Long.class);

        // Re-adding NOT VALID must succeed even though the row just inserted violates the check.
        jdbc.update("""
            ALTER TABLE sales.invoice_details
                ADD CONSTRAINT chk_invoice_details_evidence_present CHECK (
                    invoice_attachment_id IS NOT NULL
                    OR (evidence_provenance IS NOT NULL
                        AND evidence_provenance IN
                            ('PRE_ERP_PAPER_APPROVAL', 'PENDING_ATTACHMENT', 'UAT_SEED', 'DEMO_SEED'))
                ) NOT VALID
            """, Map.of());

        Long attachmentId = jdbc.queryForObject(
            "SELECT invoice_attachment_id FROM sales.invoice_details WHERE invoice_id = :id",
            Map.of("id", invoiceId), Long.class);
        String provenance = jdbc.queryForObject(
            "SELECT evidence_provenance FROM sales.invoice_details WHERE invoice_id = :id",
            Map.of("id", invoiceId), String.class);
        assertThat(attachmentId).isNull();
        assertThat(provenance).isNull();

        // OPERATIONAL NUANCE worth pinning explicitly (found while writing this test, not
        // guessed): NOT VALID only skips the one-time validation SCAN that ADD CONSTRAINT would
        // otherwise run over existing rows -- it does NOT exempt a grandfathered row from being
        // re-checked on every future UPDATE, even one that touches a completely unrelated column.
        // So a row left in this state (neither evidence route set) would reject an unrelated-
        // column UPDATE (e.g. via CommissionRepository#updateDeductions, if a sales_manager edits
        // deductions on it) with a Postgres constraint violation, not silently allow it. THIS IS
        // EXACTLY THE WINDOW F7 CLOSED: V102's real migration backfills every one of the ~1,100
        // real historical rows inline, before its CHECK is even added, so no row in production is
        // ever left in the state reproduced here for this test's own purposes. See the migration
        // file's comments for the full "why inline, not manual" reasoning (a manual, deferred
        // backfill would have left exactly this rejection as a live 500 for any sales_manager who
        // tried to edit deductions on a pre-ERP row before the backfill ran).
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE sales.invoice_details SET metadata = '{}'::jsonb WHERE invoice_id = :id",
                Map.of("id", invoiceId)))
            .isInstanceOf(DataIntegrityViolationException.class);

        // Once the row is backfilled, it becomes updatable again on unrelated columns too -- the
        // constraint problem is specific to rows that still show neither evidence route, not to
        // "old" rows in general.
        jdbc.update("""
            UPDATE sales.invoice_details SET evidence_provenance = 'PRE_ERP_PAPER_APPROVAL'
             WHERE invoice_id = :id
            """, Map.of("id", invoiceId));
        jdbc.update("UPDATE sales.invoice_details SET metadata = '{}'::jsonb WHERE invoice_id = :id",
            Map.of("id", invoiceId));
    }

    private int countInvoiceDetails() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM sales.invoice_details", Map.of(), Integer.class);
        return count == null ? 0 : count;
    }

    private long insertFileAttachment(long invoiceId) {
        // uploaded_by is a nullable FK (ON DELETE SET NULL, V33) -- null keeps this helper free
        // of any dependency on a seeded employee row existing in a fresh test database.
        org.springframework.jdbc.support.GeneratedKeyHolder key = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO hr.file_attachment
                (domain, owner_id, file_name, file_path, mime_type, file_size, uploaded_by)
            VALUES
                ('commission-invoice', :ownerId, 'invoice.pdf', '/tmp/invoice.pdf', 'application/pdf', 1024, NULL)
            """,
            new MapSqlParameterSource()
                .addValue("ownerId", invoiceId),
            key,
            new String[]{"attachment_id"});
        return key.getKey().longValue();
    }
}
