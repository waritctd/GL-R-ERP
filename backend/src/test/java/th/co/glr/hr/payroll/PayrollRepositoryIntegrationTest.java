package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Exercises {@link PayrollRepository}'s persistence path against a real PostgreSQL database: process
 * a payroll period + lines and read them back by month, asserting the stored money figures survive
 * the INSERT/SELECT round-trip. The Mockito-based service/calculator unit tests never touch the SQL,
 * so this is the only coverage of the payroll write path and the {@code findLines} join back to
 * {@code hr.employee}.
 */
class PayrollRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {
    private PayrollRepository repository;

    @BeforeEach
    void wireRepository() {
        repository = new PayrollRepository(jdbc);
    }

    @Test
    void savesAProcessedPeriodAndReadsTheFiguresBackByMonth() {
        long alice = seedEmployee("EMP-001", "อลิสา", "ทดสอบ");
        long bob = seedEmployee("EMP-002", "บ๊อบ", "ทดสอบ");
        LocalDate month = LocalDate.of(2026, 7, 1);

        PayrollLineDto aliceLine = line(alice, "EMP-001", "อลิสา ทดสอบ",
            new BigDecimal("30000.00"), new BigDecimal("34000.00"),
            new BigDecimal("750.00"), new BigDecimal("1200.50"), new BigDecimal("31249.50"));
        PayrollLineDto bobLine = line(bob, "EMP-002", "บ๊อบ ทดสอบ",
            new BigDecimal("20000.00"), new BigDecimal("21500.00"),
            new BigDecimal("750.00"), new BigDecimal("300.00"), new BigDecimal("20450.00"));

        long periodId = repository.saveProcessedPeriod(month, alice, List.of(aliceLine, bobLine));
        assertThat(periodId).isPositive();

        PayrollPeriodDto readBack = repository.findPeriodByMonth(month).orElseThrow();

        assertThat(readBack.id()).isEqualTo(periodId);
        assertThat(readBack.payrollMonth()).isEqualTo(month);
        assertThat(readBack.status()).isEqualTo("PROCESSED");
        assertThat(readBack.processedById()).isEqualTo(alice);
        assertThat(readBack.lineCount()).isEqualTo(2);
        // Period totals are derived from the persisted lines.
        assertThat(readBack.totalGross()).isEqualByComparingTo("55500.00");     // 34000 + 21500
        assertThat(readBack.totalDeductions()).isEqualByComparingTo("1500.50"); // 1200.50 + 300
        assertThat(readBack.totalNet()).isEqualByComparingTo("51699.50");       // 31249.50 + 20450
        assertThat(readBack.totalSocialSecurity()).isEqualByComparingTo("1500.00"); // 750 + 750

        // Lines come back ordered by employee_code, so EMP-001 (Alice) is first.
        PayrollLineDto storedAlice = readBack.lines().get(0);
        assertThat(storedAlice.id()).isNotNull();
        assertThat(storedAlice.employeeId()).isEqualTo(alice);
        assertThat(storedAlice.employeeCode()).isEqualTo("EMP-001");
        assertThat(storedAlice.employeeName()).isEqualTo("อลิสา ทดสอบ");
        assertThat(storedAlice.baseSalary()).isEqualByComparingTo("30000.00");
        assertThat(storedAlice.grossEarnings()).isEqualByComparingTo("34000.00");
        assertThat(storedAlice.socialSecurity()).isEqualByComparingTo("750.00");
        assertThat(storedAlice.totalDeductions()).isEqualByComparingTo("1200.50");
        assertThat(storedAlice.netPay()).isEqualByComparingTo("31249.50");
        assertThat(storedAlice.calculationNote()).isEqualTo("round-trip EMP-001");

        PayrollLineDto storedBob = readBack.lines().get(1);
        assertThat(storedBob.employeeCode()).isEqualTo("EMP-002");
        assertThat(storedBob.netPay()).isEqualByComparingTo("20450.00");
    }

    @Test
    void reprocessingTheSameMonthReplacesLinesInsteadOfDuplicating() {
        long alice = seedEmployee("EMP-001", "อลิสา", "ทดสอบ");
        long bob = seedEmployee("EMP-002", "บ๊อบ", "ทดสอบ");
        LocalDate month = LocalDate.of(2026, 7, 1);

        repository.saveProcessedPeriod(month, alice, List.of(
            line(alice, "EMP-001", "อลิสา ทดสอบ", new BigDecimal("30000.00"), new BigDecimal("34000.00"),
                new BigDecimal("750.00"), new BigDecimal("1200.50"), new BigDecimal("31249.50")),
            line(bob, "EMP-002", "บ๊อบ ทดสอบ", new BigDecimal("20000.00"), new BigDecimal("21500.00"),
                new BigDecimal("750.00"), new BigDecimal("300.00"), new BigDecimal("20450.00"))));

        // Re-process the same month with a single line — the old two lines must be replaced.
        long secondRun = repository.saveProcessedPeriod(month, bob, List.of(
            line(alice, "EMP-001", "อลิสา ทดสอบ", new BigDecimal("30000.00"), new BigDecimal("30000.00"),
                new BigDecimal("750.00"), new BigDecimal("750.00"), new BigDecimal("29250.00"))));

        PayrollPeriodDto readBack = repository.findPeriodByMonth(month).orElseThrow();
        assertThat(readBack.id()).isEqualTo(secondRun); // same period row reused (unique on month)
        assertThat(readBack.lineCount()).isEqualTo(1);
        assertThat(readBack.processedById()).isEqualTo(bob);
        assertThat(readBack.lines().get(0).netPay()).isEqualByComparingTo("29250.00");
    }

    // ------------------------------------------------------------------------------------------
    // Reconciliation additions (2026-07-21, C1/C2/C3): tax-allowance upsert round-trip, YTD-seed
    // merge with payroll_line, and director remuneration surviving insert + read.
    // ------------------------------------------------------------------------------------------

    @Test
    void directorRemunerationOnTheEmployeeSurvivesInsertAndReadBackThroughFindActiveEmployees() {
        long director = seedDirector("DIR-001", "กัลยาณี", "ทดสอบ", new BigDecimal("150000.00"));
        seedEmployee("EMP-900", "พนักงาน", "ทั่วไป");

        List<PayrollEmployeeSnapshot> employees = repository.findActiveEmployees();

        PayrollEmployeeSnapshot directorSnapshot = employees.stream()
            .filter(e -> e.employeeId() == director)
            .findFirst()
            .orElseThrow(() -> new AssertionError("director without a salary must still appear in findActiveEmployees"));
        assertThat(directorSnapshot.directorRemuneration()).isEqualByComparingTo("150000.00");
        assertThat(directorSnapshot.baseSalary()).isEqualByComparingTo(BigDecimal.ZERO);

        long processedById = employees.get(0).employeeId();
        PayrollLineDto directorLine = lineWithDirectorPay(director, "DIR-001", "กัลยาณี ทดสอบ",
            new BigDecimal("150000.00"), new BigDecimal("150000.00"));
        long periodId = repository.saveProcessedPeriod(LocalDate.of(2026, 7, 1), processedById, List.of(directorLine));
        PayrollPeriodDto readBack = repository.findPeriodById(periodId).orElseThrow();
        PayrollLineDto storedLine = readBack.lines().stream()
            .filter(l -> l.employeeId() == director)
            .findFirst()
            .orElseThrow();
        assertThat(storedLine.directorRemuneration()).isEqualByComparingTo("150000.00");
    }

    @Test
    void taxAllowanceUpsertRoundTripsAllSixteenFieldsAndOverwritesOnConflict() {
        long alice = seedEmployee("EMP-001", "อลิสา", "ทดสอบ");
        var upsert = new th.co.glr.hr.payroll.PayrollReconciliationDtos.EmployeeTaxAllowanceUpsertRequest(
            alice,
            new BigDecimal("60000.00"), new BigDecimal("30000.00"), new BigDecimal("30000.00"),
            new BigDecimal("60000.00"), new BigDecimal("60000.00"), new BigDecimal("50000.00"),
            new BigDecimal("15000.00"), new BigDecimal("10000.00"), new BigDecimal("200000.00"),
            new BigDecimal("100000.00"), new BigDecimal("50000.00"), new BigDecimal("100000.00"),
            new BigDecimal("50000.00"), new BigDecimal("5000.00"), new BigDecimal("3000.00"),
            new BigDecimal("1000.00"));

        repository.upsertTaxAllowances(2026, List.of(upsert), alice);
        Map<Long, PayrollTaxAllowanceInput> firstRead = repository.findTaxAllowancesByEmployee(java.time.LocalDate.of(2026, 6, 1));
        assertThat(firstRead.get(alice).spouseAllowance()).isEqualByComparingTo("60000.00");
        assertThat(firstRead.get(alice).politicalDonation()).isEqualByComparingTo("1000.00");

        // Re-upsert with a changed value: ON CONFLICT must overwrite, not duplicate.
        var updated = new th.co.glr.hr.payroll.PayrollReconciliationDtos.EmployeeTaxAllowanceUpsertRequest(
            alice,
            new BigDecimal("0.00"), new BigDecimal("30000.00"), new BigDecimal("30000.00"),
            new BigDecimal("60000.00"), new BigDecimal("60000.00"), new BigDecimal("50000.00"),
            new BigDecimal("15000.00"), new BigDecimal("10000.00"), new BigDecimal("200000.00"),
            new BigDecimal("100000.00"), new BigDecimal("50000.00"), new BigDecimal("100000.00"),
            new BigDecimal("50000.00"), new BigDecimal("5000.00"), new BigDecimal("3000.00"),
            new BigDecimal("1000.00"));
        repository.upsertTaxAllowances(2026, List.of(updated), alice);

        Map<Long, PayrollTaxAllowanceInput> secondRead = repository.findTaxAllowancesByEmployee(java.time.LocalDate.of(2026, 6, 1));
        assertThat(secondRead).hasSize(1);
        assertThat(secondRead.get(alice).spouseAllowance()).isEqualByComparingTo("0.00");

        List<th.co.glr.hr.payroll.PayrollReconciliationDtos.EmployeeTaxAllowanceDto> rows = repository.findTaxAllowanceRows(2026);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).employeeCode()).isEqualTo("EMP-001");
    }

    // ------------------------------------------------------------------------------------------
    // verification_status reset on overwrite (2026-08-08 fix). PUT /api/payroll/tax-allowances
    // (PayrollController, HR-only) reaches upsertTaxAllowances directly with no verification step of
    // its own, unlike TaxAllowanceDeclarationService#apply, which always re-verifies in the same
    // transaction right after (see TaxAllowanceApplySeamIntegrationTest for that regression guard).
    // Before the fix, overwriting a VERIFIED row's amounts through this bare upsert silently left
    // verification_status = 'VERIFIED' on brand-new, never-reviewed figures -- and
    // findTaxAllowancesByEmployee applies VERIFIED rows to withholding same as GRANDFATHERED_UNVERIFIED.
    // Every assertion here is written wrong-way-round: it asserts the bad outcome CANNOT happen.
    // ------------------------------------------------------------------------------------------

    @Test
    void overwritingAVerifiedRowsAmountsThroughTheLegacyPathDoesNotLeaveItVerified() {
        long alice = seedEmployee("EMP-301", "อลิสา", "reset1");
        long hr = seedEmployee("EMP-302", "เอชอาร์", "reset1");
        repository.upsertTaxAllowances(2026,
            List.of(taxAllowanceUpsert(alice, new BigDecimal("60000.00"), 1, "doc-v1")), hr);

        // HR verifies against supporting documents -- a real reviewer/timestamp/deadline attached,
        // exactly how a genuine apply()/reverify() leaves the row.
        repository.markTaxAllowanceVerified(alice, 2026, hr);
        repository.setTaxAllowanceVerificationDeadline(alice, 2026, LocalDate.of(2026, 12, 31));
        assertThat(verificationStatusOf(alice, 1)).isEqualTo("VERIFIED");

        // A later, unrelated overwrite through the SAME legacy path changes the declared amount --
        // nobody has reviewed this new figure.
        repository.upsertTaxAllowances(2026,
            List.of(taxAllowanceUpsert(alice, new BigDecimal("90000.00"), 1, "doc-v1")), hr);

        assertThat(verificationStatusOf(alice, 1))
            .as("a VERIFIED row whose amounts changed under it must NOT still read VERIFIED")
            .isEqualTo("GRANDFATHERED_UNVERIFIED");
        assertThat(verifiedByIdOf(alice, 1)).as("no stale reviewer left on an unreviewed figure").isNull();
        assertThat(verifiedAtOf(alice, 1)).as("no stale review timestamp left on an unreviewed figure").isNull();
        assertThat(verificationDeadlineOf(alice, 1)).as("no stale deadline borrowed from the old review").isNull();
        // Sanity: the overwrite really did take effect -- this is not a no-op being misread as a reset.
        Map<Long, PayrollTaxAllowanceInput> resolved = repository.findTaxAllowancesByEmployee(LocalDate.of(2026, 6, 1));
        assertThat(resolved.get(alice).spouseAllowance()).isEqualByComparingTo("90000.00");
    }

    @Test
    void reUpsertingByteIdenticalFiguresDoesNotDowngradeAnAlreadyVerifiedRow() {
        long alice = seedEmployee("EMP-303", "อลิสา", "reset2");
        long hr = seedEmployee("EMP-304", "เอชอาร์", "reset2");
        repository.upsertTaxAllowances(2026,
            List.of(taxAllowanceUpsert(alice, new BigDecimal("60000.00"), 1, "doc-v1")), hr);
        repository.markTaxAllowanceVerified(alice, 2026, hr);
        repository.setTaxAllowanceVerificationDeadline(alice, 2026, LocalDate.of(2026, 12, 31));
        OffsetDateTime verifiedAtBefore = verifiedAtOf(alice, 1);
        assertThat(verifiedAtBefore).isNotNull();

        // Same employee, same year/month, IDENTICAL figures -- a genuine no-op re-save (e.g. a
        // retried request), not a real edit.
        repository.upsertTaxAllowances(2026,
            List.of(taxAllowanceUpsert(alice, new BigDecimal("60000.00"), 1, "doc-v1")), hr);

        assertThat(verificationStatusOf(alice, 1))
            .as("a no-op re-save of identical figures must not downgrade a legitimately verified row")
            .isEqualTo("VERIFIED");
        assertThat(verifiedByIdOf(alice, 1)).isEqualTo(hr);
        assertThat(verifiedAtOf(alice, 1)).isEqualTo(verifiedAtBefore);
        assertThat(verificationDeadlineOf(alice, 1)).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    void overwritingOneDatedRowDoesNotDowngradeASiblingDatedRowInTheSameYear() {
        long alice = seedEmployee("EMP-305", "อลิสา", "reset3");
        long hr = seedEmployee("EMP-306", "เอชอาร์", "reset3");
        // Two dated rows for the same employee/year (V93 effective dating): January and June.
        repository.upsertTaxAllowances(2026, List.of(
            taxAllowanceUpsert(alice, new BigDecimal("60000.00"), 1, "doc-jan"),
            taxAllowanceUpsert(alice, new BigDecimal("60000.00"), 6, "doc-jun")), hr);
        // markTaxAllowanceVerified has NO effective_month -- it verifies the WHOLE year's lineage at
        // once, exactly as the real declaration workflow's apply()/reverify() do.
        repository.markTaxAllowanceVerified(alice, 2026, hr);
        repository.setTaxAllowanceVerificationDeadline(alice, 2026, LocalDate.of(2026, 12, 31));
        assertThat(verificationStatusOf(alice, 1)).isEqualTo("VERIFIED");
        assertThat(verificationStatusOf(alice, 6)).isEqualTo("VERIFIED");

        // Only June's amount is corrected through the legacy path. January is never part of this call.
        repository.upsertTaxAllowances(2026,
            List.of(taxAllowanceUpsert(alice, new BigDecimal("90000.00"), 6, "doc-jun")), hr);

        assertThat(verificationStatusOf(alice, 6))
            .as("the row that actually changed must lose its verification")
            .isEqualTo("GRANDFATHERED_UNVERIFIED");
        assertThat(verificationStatusOf(alice, 1))
            .as("a sibling dated row this call never touched must NOT be downgraded too -- reset is per-row, not per-year")
            .isEqualTo("VERIFIED");
        assertThat(verifiedByIdOf(alice, 1)).as("January's own review is untouched").isEqualTo(hr);
        assertThat(verificationDeadlineOf(alice, 1)).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    void changingOnlyTheDocumentReferenceAlsoResetsVerification() {
        long alice = seedEmployee("EMP-307", "อลิสา", "reset4");
        long hr = seedEmployee("EMP-308", "เอชอาร์", "reset4");
        repository.upsertTaxAllowances(2026,
            List.of(taxAllowanceUpsert(alice, new BigDecimal("60000.00"), 1, "receipt-A.pdf")), hr);
        repository.markTaxAllowanceVerified(alice, 2026, hr);
        assertThat(verificationStatusOf(alice, 1)).isEqualTo("VERIFIED");

        // Same declared amount, but a DIFFERENT document was supplied -- VERIFIED meant HR confirmed
        // receipt-A.pdf specifically, not whatever this new reference points to.
        repository.upsertTaxAllowances(2026,
            List.of(taxAllowanceUpsert(alice, new BigDecimal("60000.00"), 1, "receipt-B.pdf")), hr);

        assertThat(verificationStatusOf(alice, 1))
            .as("a changed document_reference must also invalidate the prior verification")
            .isEqualTo("GRANDFATHERED_UNVERIFIED");
    }

    /**
     * Opus review finding F1 (2026-08-08, blocking, fixed before merge): an earlier version of this
     * fix reset ANY content change to GRANDFATHERED_UNVERIFIED regardless of the row's PRIOR status.
     * From EXPIRED_UNVERIFIED that was a silent PROMOTION -- GRANDFATHERED_UNVERIFIED IS applied to
     * payroll (findTaxAllowancesByEmployee excludes only EXPIRED_UNVERIFIED), so a lapsed,
     * no-longer-reviewed declaration would have re-entered withholding on nothing more than a bare
     * amount edit through the legacy PUT, with no reviewer, no deadline, and no automated way back
     * out (the sweep only re-examines declarations still APPROVED in hr.tax_allowance_declaration).
     * Wrong-way-round: asserts the promotion CANNOT happen, and separately that the row's PRE-EXPIRY
     * verifier/timestamp survive as genuine history rather than being laundered away.
     */
    @Test
    void overwritingAnExpiredRowsAmountsDoesNotPromoteItBackIntoPayroll() {
        long alice = seedEmployee("EMP-309", "อลิสา", "reset5");
        long hr = seedEmployee("EMP-310", "เอชอาร์", "reset5");
        repository.upsertTaxAllowances(2026,
            List.of(taxAllowanceUpsert(alice, new BigDecimal("60000.00"), 1, "doc-v1")), hr);
        repository.markTaxAllowanceVerified(alice, 2026, hr);
        OffsetDateTime verifiedAtBeforeExpiry = verifiedAtOf(alice, 1);
        assertThat(verifiedAtBeforeExpiry).isNotNull();

        // The verification lapsed -- the sweep's real effect (expireTaxAllowanceVerification), not a
        // hand-set status. The row is now excluded from payroll.
        repository.expireTaxAllowanceVerification(alice, 2026);
        assertThat(verificationStatusOf(alice, 1)).isEqualTo("EXPIRED_UNVERIFIED");
        assertThat(repository.findTaxAllowancesByEmployee(LocalDate.of(2026, 6, 1)))
            .as("EXPIRED_UNVERIFIED must be excluded from payroll before the overwrite (sanity)")
            .doesNotContainKey(alice);

        // A later, unrelated amount edit through the legacy path -- nobody reviewed this new figure,
        // and nobody re-verified the lapsed declaration either.
        repository.upsertTaxAllowances(2026,
            List.of(taxAllowanceUpsert(alice, new BigDecimal("90000.00"), 1, "doc-v1")), hr);

        assertThat(verificationStatusOf(alice, 1))
            .as("an EXPIRED_UNVERIFIED row whose amounts changed must NOT be promoted to GRANDFATHERED_UNVERIFIED")
            .isEqualTo("EXPIRED_UNVERIFIED");
        assertThat(repository.findTaxAllowancesByEmployee(LocalDate.of(2026, 6, 1)))
            .as("must still NOT apply to payroll -- this is the whole point of F1")
            .doesNotContainKey(alice);
        assertThat(verifiedByIdOf(alice, 1)).as("pre-expiry reviewer is preserved, not cleared").isEqualTo(hr);
        assertThat(verifiedAtOf(alice, 1))
            .as("pre-expiry timestamp is preserved, not cleared")
            .isEqualTo(verifiedAtBeforeExpiry);
        // Sanity: the overwrite really did take effect on the stored row -- a direct SQL read, since
        // findTaxAllowancesByEmployee deliberately excludes this row and can't be used to check it.
        BigDecimal storedAmount = jdbc.queryForObject(
            "SELECT spouse_allowance FROM hr.employee_tax_allowance WHERE employee_id = :id AND effective_month = 1",
            Map.of("id", alice), BigDecimal.class);
        assertThat(storedAmount).isEqualByComparingTo("90000.00");
    }

    /** Full-arity upsert request with everything but spouseAllowance/effectiveMonth/documentReference zeroed. */
    private th.co.glr.hr.payroll.PayrollReconciliationDtos.EmployeeTaxAllowanceUpsertRequest taxAllowanceUpsert(
        long employeeId, BigDecimal spouseAllowance, Integer effectiveMonth, String documentReference
    ) {
        return new th.co.glr.hr.payroll.PayrollReconciliationDtos.EmployeeTaxAllowanceUpsertRequest(
            employeeId,
            spouseAllowance, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            0, 0, 0, false, 0,
            effectiveMonth, documentReference, BigDecimal.ZERO);
    }

    private String verificationStatusOf(long employeeId, int effectiveMonth) {
        return jdbc.queryForObject(
            "SELECT verification_status FROM hr.employee_tax_allowance WHERE employee_id = :id AND effective_month = :month",
            Map.of("id", employeeId, "month", effectiveMonth), String.class);
    }

    private Long verifiedByIdOf(long employeeId, int effectiveMonth) {
        return jdbc.queryForObject(
            "SELECT verified_by_id FROM hr.employee_tax_allowance WHERE employee_id = :id AND effective_month = :month",
            Map.of("id", employeeId, "month", effectiveMonth), Long.class);
    }

    private OffsetDateTime verifiedAtOf(long employeeId, int effectiveMonth) {
        return jdbc.queryForObject(
            "SELECT verified_at FROM hr.employee_tax_allowance WHERE employee_id = :id AND effective_month = :month",
            Map.of("id", employeeId, "month", effectiveMonth), OffsetDateTime.class);
    }

    private LocalDate verificationDeadlineOf(long employeeId, int effectiveMonth) {
        return jdbc.queryForObject(
            "SELECT verification_deadline FROM hr.employee_tax_allowance WHERE employee_id = :id AND effective_month = :month",
            Map.of("id", employeeId, "month", effectiveMonth), LocalDate.class);
    }

    @Test
    void yearToDateMergesTheSeedWithProcessedPayrollLinesRatherThanReplacingThem() {
        long seedOnly = seedEmployee("EMP-101", "ซีดอย่างเดียว", "ทดสอบ");
        long lineOnly = seedEmployee("EMP-102", "ไลน์อย่างเดียว", "ทดสอบ");
        long both = seedEmployee("EMP-103", "ทั้งคู่", "ทดสอบ");
        LocalDate month = LocalDate.of(2026, 7, 1);

        // seedOnly: only a YTD seed row, no processed payroll_line this year.
        repository.upsertYtdSeed(2026, List.of(
            new th.co.glr.hr.payroll.PayrollReconciliationDtos.YtdSeedUpsertRequest(
                seedOnly, new BigDecimal("100000.00"), new BigDecimal("5000.00"), new BigDecimal("2000.00"), "pre-system history"),
            new th.co.glr.hr.payroll.PayrollReconciliationDtos.YtdSeedUpsertRequest(
                both, new BigDecimal("50000.00"), new BigDecimal("2500.00"), new BigDecimal("1000.00"), "pre-system history")
        ), seedOnly);

        // lineOnly + both: a processed payroll_line row for an earlier month this year.
        repository.saveProcessedPeriod(LocalDate.of(2026, 6, 1), lineOnly, List.of(
            line(lineOnly, "EMP-102", "ไลน์อย่างเดียว ทดสอบ", new BigDecimal("20000.00"), new BigDecimal("20000.00"),
                new BigDecimal("1000.00"), new BigDecimal("1000.00"), new BigDecimal("18000.00")),
            line(both, "EMP-103", "ทั้งคู่ ทดสอบ", new BigDecimal("20000.00"), new BigDecimal("20000.00"),
                new BigDecimal("1000.00"), new BigDecimal("1000.00"), new BigDecimal("18000.00"))
        ));

        Map<Long, PayrollYearToDate> ytd = repository.findYearToDateByEmployee(month);

        assertThat(ytd.get(seedOnly).taxableIncome()).isEqualByComparingTo("100000.00");
        assertThat(ytd.get(lineOnly).taxableIncome()).isEqualByComparingTo("20000.00");
        // both: seed (50,000) + payroll_line's gross_taxable_income (20,000) = 70,000.
        assertThat(ytd.get(both).taxableIncome()).isEqualByComparingTo("70000.00");
        assertThat(ytd.get(both).socialSecurity()).isEqualByComparingTo("3500.00");
        // the line() helper hardcodes withholdingTax to zero (it only parameterizes totalDeductions),
        // so "both" only carries the seed's 1,000 withholding here.
        assertThat(ytd.get(both).withholdingTax()).isEqualByComparingTo("1000.00");

        List<th.co.glr.hr.payroll.PayrollReconciliationDtos.YtdSeedDto> rows = repository.findYtdSeedRows(2026);
        assertThat(rows).hasSize(2);
    }

    @Test
    void findExportRowsJoinsPiiBankAndAddressScopedToTheRequestedPeriod() {
        long alice = seedExportEmployee("EMP-001", "อลิสา", "ทดสอบ", "gullayanee",
            "นาง", "3100902988046", "1002818495", "3100902988046",
            "0952555944", "99", "บางนา", "10260");
        long bob = seedExportEmployee("EMP-002", "บ๊อบ", "ทดสอบ", "bob",
            "นาย", "1103900192241", null, null,
            "0952498185", "201", "พัฒนาการ", "10110");
        long carol = seedExportEmployee("EMP-003", "แครอล", "ทดสอบ", "carol",
            "นางสาว", "1234567890123", null, "1234567890123",
            "1111111111", "5", "สาทร", "10120");

        LocalDate july = LocalDate.of(2026, 7, 1);
        long julyPeriod = repository.saveProcessedPeriod(july, alice, List.of(
            line(alice, "EMP-001", "อลิสา ทดสอบ", new BigDecimal("30000.00"), new BigDecimal("30000.00"),
                new BigDecimal("750.00"), new BigDecimal("2929.00"), new BigDecimal("26321.00")),
            line(bob, "EMP-002", "บ๊อบ ทดสอบ", new BigDecimal("20000.00"), new BigDecimal("20000.00"),
                new BigDecimal("750.00"), new BigDecimal("750.00"), new BigDecimal("19250.00"))));
        // A separate June period for Carol — must NOT leak into the July export rows.
        repository.saveProcessedPeriod(LocalDate.of(2026, 6, 1), carol, List.of(
            line(carol, "EMP-003", "แครอล ทดสอบ", new BigDecimal("40000.00"), new BigDecimal("40000.00"),
                new BigDecimal("875.00"), new BigDecimal("1000.00"), new BigDecimal("38125.00"))));

        List<th.co.glr.hr.payroll.export.PayrollExportRow> rows = repository.findExportRows(julyPeriod);

        // Scoped to the July period only, ordered by employee_code.
        assertThat(rows).extracting(th.co.glr.hr.payroll.export.PayrollExportRow::employeeCode)
            .containsExactly("EMP-001", "EMP-002");

        var aliceRow = rows.get(0);
        assertThat(aliceRow.titleTh()).isEqualTo("นาง");
        assertThat(aliceRow.firstNameTh()).isEqualTo("อลิสา");
        assertThat(aliceRow.firstNameEn()).isEqualTo("gullayanee");
        assertThat(aliceRow.nationalId()).isEqualTo("3100902988046");
        assertThat(aliceRow.taxId()).isEqualTo("1002818495");
        assertThat(aliceRow.socialSecurityNo()).isEqualTo("3100902988046");
        assertThat(aliceRow.bankAccount()).isEqualTo("0952555944");
        assertThat(aliceRow.houseNo()).isEqualTo("99");
        assertThat(aliceRow.addressRest()).contains("บางนา");
        assertThat(aliceRow.postalCode()).isEqualTo("10260");
        assertThat(aliceRow.netAmount()).isEqualByComparingTo("26321.00");
        assertThat(aliceRow.grossTaxableIncome()).isEqualByComparingTo("30000.00");
        assertThat(aliceRow.socialSecurity()).isEqualByComparingTo("750.00");

        // A missing tax id comes back null (LEFT JOIN / nullable column), not an empty string.
        assertThat(rows.get(1).taxId()).isNull();
    }

    private long seedExportEmployee(String code, String firstTh, String lastTh, String firstEn,
                                    String titleTh, String nationalId, String taxId, String ssn,
                                    String bankAccount, String houseNo, String subdistrict, String postal) {
        Long titleId = jdbc.queryForObject(
            "INSERT INTO hr.title (name_th) VALUES (:t) ON CONFLICT (name_th) DO UPDATE SET name_th = EXCLUDED.name_th RETURNING title_id",
            Map.of("t", titleTh), Long.class);
        Long bankId = jdbc.queryForObject(
            "INSERT INTO hr.bank (name_th) VALUES ('ธ.กสิกรไทย') ON CONFLICT (name_th) DO UPDATE SET name_th = EXCLUDED.name_th RETURNING bank_id",
            Map.of(), Long.class);
        long employeeId = jdbc.queryForObject(
            """
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, first_name_en, title_id, current_salary, is_active)
            VALUES (:code, :first, :last, :firstEn, :titleId, 30000, TRUE)
            RETURNING employee_id
            """,
            Map.of("code", code, "first", firstTh, "last", lastTh, "firstEn", firstEn, "titleId", titleId),
            Long.class);
        jdbc.update(
            "INSERT INTO hr.employee_bank_account (employee_id, bank_id, account_no) VALUES (:id, :bankId, :acct)",
            Map.of("id", employeeId, "bankId", bankId, "acct", bankAccount));
        jdbc.update(
            "INSERT INTO hr_restricted.employee_pii (employee_id, national_id, tax_id, social_security_no) VALUES (:id, :nid, :tax, :ssn)",
            new MapSqlParameterSource()
                .addValue("id", employeeId).addValue("nid", nationalId).addValue("tax", taxId).addValue("ssn", ssn));
        jdbc.update(
            """
            INSERT INTO hr.employee_address (employee_id, address_type, house_no, subdistrict, province, postal_code)
            VALUES (:id, 'CURRENT', :house, :sub, 'กรุงเทพฯ', :postal)
            """,
            Map.of("id", employeeId, "house", houseNo, "sub", subdistrict, "postal", postal));
        return employeeId;
    }

    private long seedEmployee(String code, String firstNameTh, String lastNameTh) {
        return jdbc.queryForObject(
            """
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, is_active)
            VALUES (:code, :first, :last, 30000, TRUE)
            RETURNING employee_id
            """,
            Map.of("code", code, "first", firstNameTh, "last", lastNameTh),
            Long.class);
    }

    /** A director: no salary at all, only director_remuneration -- must still be payroll-eligible. */
    private long seedDirector(String code, String firstNameTh, String lastNameTh, BigDecimal directorRemuneration) {
        return jdbc.queryForObject(
            """
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, director_remuneration, is_active)
            VALUES (:code, :first, :last, 0, :directorRemuneration, TRUE)
            RETURNING employee_id
            """,
            Map.of("code", code, "first", firstNameTh, "last", lastNameTh, "directorRemuneration", directorRemuneration),
            Long.class);
    }

    private PayrollLineDto lineWithDirectorPay(
        long employeeId, String code, String name, BigDecimal directorRemuneration, BigDecimal gross
    ) {
        return new PayrollLineDto(
            null, employeeId, code, name,
            null, null, null,
            BigDecimal.ZERO,                // baseSalary
            BigDecimal.ZERO,                // dailyRate
            BigDecimal.ZERO,                // hourlyRate
            specialPays(),
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            gross,                          // grossEarnings
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            gross,                          // grossTaxableIncome
            BigDecimal.ZERO,                // ssoWageBase (directors have none)
            BigDecimal.ZERO,                // socialSecurity (directors have none)
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO,                // withholdingTax
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO,                // totalDeductions
            gross,                          // netPay
            "director",
            directorRemuneration,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
        );
    }

    private PayrollLineDto line(
        long employeeId,
        String code,
        String name,
        BigDecimal baseSalary,
        BigDecimal gross,
        BigDecimal socialSecurity,
        BigDecimal deductions,
        BigDecimal net
    ) {
        return new PayrollLineDto(
            null, employeeId, code, name,
            null, null, null,
            baseSalary,
            new BigDecimal("1000.0000"),   // dailyRate
            new BigDecimal("125.0000"),    // hourlyRate
            specialPays(),
            BigDecimal.ZERO,               // specialPayTotal
            BigDecimal.ZERO,               // overtimePay
            BigDecimal.ZERO,               // commissionPay
            gross,                         // grossEarnings
            BigDecimal.ZERO,               // nonTaxableIncome
            BigDecimal.ZERO,               // unpaidLeaveDays
            BigDecimal.ZERO,               // unpaidLeaveDeduction
            gross,                         // grossTaxableIncome
            gross,                         // ssoWageBase
            socialSecurity,
            BigDecimal.ZERO,               // projectedAnnualIncome
            BigDecimal.ZERO,               // taxExpenseDeduction
            BigDecimal.ZERO,               // taxAllowanceTotal
            BigDecimal.ZERO,               // taxableAnnualIncome
            BigDecimal.ZERO,               // annualTax
            BigDecimal.ZERO,               // withholdingTax
            BigDecimal.ZERO,               // studentLoanDeduction
            BigDecimal.ZERO,               // legalExecutionDeduction
            BigDecimal.ZERO,               // otherPostTaxDeductions
            deductions,                    // totalDeductions
            net,                           // netPay
            "round-trip " + code,
            BigDecimal.ZERO,               // directorRemuneration
            BigDecimal.ZERO,               // warningLetterDeduction
            BigDecimal.ZERO,               // customerReturnDeduction
            BigDecimal.ZERO);              // otherPretaxDeduction
    }

    private List<PayrollSpecialPayDto> specialPays() {
        return List.of(
            new PayrollSpecialPayDto("specialPay1", "พิเศษ 1", BigDecimal.ZERO),
            new PayrollSpecialPayDto("specialPay2", "พิเศษ 2", BigDecimal.ZERO),
            new PayrollSpecialPayDto("specialPay3", "พิเศษ 3", BigDecimal.ZERO),
            new PayrollSpecialPayDto("specialPay4", "พิเศษ 4", BigDecimal.ZERO),
            new PayrollSpecialPayDto("specialPay5", "พิเศษ 5", BigDecimal.ZERO),
            new PayrollSpecialPayDto("specialPay6", "พิเศษ 6", BigDecimal.ZERO),
            new PayrollSpecialPayDto("specialPay7", "พิเศษ 7", BigDecimal.ZERO),
            new PayrollSpecialPayDto("specialPay8", "พิเศษ 8", BigDecimal.ZERO),
            new PayrollSpecialPayDto("specialPay9", "พิเศษ 9", BigDecimal.ZERO));
    }
}
