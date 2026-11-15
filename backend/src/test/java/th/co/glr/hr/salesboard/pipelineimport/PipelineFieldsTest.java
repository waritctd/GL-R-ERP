package th.co.glr.hr.salesboard.pipelineimport;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.EntryChannel;

/** The pure cell → value rules of the one-time pipeline import (plan 2026-11-15 Task 4.1, spec §7.1/§7.9). */
class PipelineFieldsTest {

    // ── status column: S-code or the reps' long Thai text ─────────────────────

    @ParameterizedTest
    @CsvSource({
        "S1,S1", "s2,S2", " S3 ,S3", "S5,S5", "S6,S6", "S7,S7", "S8,S8", "S9,S9", "S03,S3",
        "S3 ทำใบเสนอราคาแล้ว,S3",
        "อยู่ระหว่างเสนอสินค้า,S1", "ผู้ออกแบบตกลงสเปคให้,S2", "ทำใบเสนอราคาแล้ว,S3",
        "รอผลประมูล รอผู้ซื้อ,S5", "รอผลประมูลรอผู้ซื้อ,S5", "ทราบผู้ซื้อแล้ว,S6", "ได้รับมัดจำแล้ว,S7",
        "อยู่ระหว่างรอส่งสินค้า,S8", "ส่งสินค้าครบแล้ว,S9",
    })
    void statusAcceptsCodesAndTheRepsOwnWording(String raw, String code) {
        assertThat(PipelineFields.repCode(raw).value()).isEqualTo(code);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"S3/S5", "S3 หรือ S5", "S3 รอเซ็น", "s6,s7"})
    void moreThanOneStatusOrTrailingTextIsAnError(String raw) {
        assertThat(PipelineFields.repCode(raw).value()).isNull();
        assertThat(PipelineFields.repCode(raw).error()).isEqualTo("ระบุสถานะเดียว");
    }

    @Test
    void s4IsRefusedWithTheRemedy() {
        assertThat(PipelineFields.repCode("S4").error()).isEqualTo("S4 ไม่มีในระบบ เลือก S3 หรือ S5");
    }

    @ParameterizedTest
    @CsvSource({"S10", "S0", "X1", "ยังไม่รู้"})
    void unknownStatusIsAnError(String raw) {
        assertThat(PipelineFields.repCode(raw).value()).isNull();
        assertThat(PipelineFields.repCode(raw).error()).contains(raw.trim());
    }

    @Test
    void blankStatusIsAnError() {
        assertThat(PipelineFields.repCode("  ").error()).isEqualTo("ไม่มีสถานะ (S1–S9)");
        assertThat(PipelineFields.repCode(null).error()).isEqualTo("ไม่มีสถานะ (S1–S9)");
    }

    // ── value: THB only ────────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "1,240,000|1240000.00", "1240000.00|1240000.00", "1240000|1240000.00", "฿1,240,000|1240000.00",
        "1,240,000 บาท|1240000.00", "THB 99.5|99.50", " 0 |0.00",
    })
    void valueAcceptsThaiAndPlainNumberFormats(String raw, String expected) {
        assertThat(PipelineFields.value(raw, null).value()).isEqualByComparingTo(new BigDecimal(expected));
    }

    @Test
    void numericCellIsTakenAsIs() {
        assertThat(PipelineFields.value("1,560,650.00", 1560650.0).value()).isEqualByComparingTo("1560650");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"USD 1,000", "$1000", "€5", "หนึ่งล้าน", "1.2.3", "-500", "99999999999999"})
    void nonThbOrNonNumericValueIsAnError(String raw) {
        assertThat(PipelineFields.value(raw, null).value()).isNull();
        assertThat(PipelineFields.value(raw, null).error()).isNotBlank();
    }

    @Test
    void aHugeNumericCellIsARowErrorNotAnException() {
        assertThat(PipelineFields.value("1E+20", 1e20).error()).isNotBlank();
        assertThat(PipelineFields.probability("1E+20", 1e20, false).error()).isNotBlank();
        assertThat(PipelineFields.probability("99999999999", 99999999999d, false).error()).isNotBlank();
        assertThat(PipelineFields.value("NaN", Double.NaN).error()).isNotBlank();
    }

    @Test
    void elevenNinesIsAValidValue() {
        assertThat(PipelineFields.value("99999999999", null).value()).isEqualByComparingTo("99999999999");
    }

    @Test
    void blankValueIsAbsentNotAnError() {
        assertThat(PipelineFields.value(" ", null).value()).isNull();
        assertThat(PipelineFields.value(" ", null).error()).isNull();
    }

    // ── win probability ────────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({"60,60", "60%,60", " 25 % ,25", "0,0", "100,100", "1,1", "0.6,60", "0.05,5"})
    void probabilityFromText(String raw, int expected) {
        assertThat(PipelineFields.probability(raw, null, false).value()).isEqualTo(expected);
    }

    @Test
    void probabilityFromAPercentFormattedCell() {
        assertThat(PipelineFields.probability("60%", 0.6, true).value()).isEqualTo(60);
        assertThat(PipelineFields.probability("60", 60.0, false).value()).isEqualTo(60);
        assertThat(PipelineFields.probability("0.6", 0.6, false).value()).isEqualTo(60);
        assertThat(PipelineFields.probability("1", 1.0, false).value()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"101", "-1", "สูง", "72.4", "1.5", "101%", "99999999999", "1e400"})
    void probabilityOutOfRangeIsAnError(String raw) {
        assertThat(PipelineFields.probability(raw, null, false).error()).isNotBlank();
    }

    // ── expected quarter ───────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({
        "2026-Q4,2026-Q4", "2026Q4,2026-Q4", "q1/2027,2027-Q1", "Q1/70,2027-Q1", "Q2/2570,2027-Q2",
        "Q3 26,2026-Q3", "2569-Q4,2026-Q4",
    })
    void quarterFormats(String raw, String expected) {
        assertThat(PipelineFields.quarter(raw, null).value()).isEqualTo(expected);
    }

    @Test
    void quarterFromADateCell() {
        assertThat(PipelineFields.quarter("", LocalDate.of(2026, 11, 3)).value()).isEqualTo("2026-Q4");
    }

    @Test
    void badQuarterIsAnError() {
        assertThat(PipelineFields.quarter("ปลายปี", null).error()).isNotBlank();
        assertThat(PipelineFields.quarter("Q5/2026", null).error()).isNotBlank();
    }

    // ── quotation numbers ─────────────────────────────────────────────────────

    @Test
    void aRevisionShorthandAfterASlashIsNotAQuotationNumber() {
        // "QNSH100-7/8": the 8 is "revision 8" shorthand, not a second quotation number.
        assertThat(PipelineFields.quotationBases("QNSH100-7/8")).containsExactly("QNSH100");
        assertThat(PipelineFields.quotationBases("QT-1, 22, ABC")).isEmpty();
    }

    @Test
    void textWithAPercentSignIsAlwaysLiteral() {
        PipelineFields.Parsed<Integer> tiny = PipelineFields.probability("0.6%", null, false);
        assertThat(tiny.value()).isEqualTo(1);
        assertThat(tiny.warning()).isEqualTo("ปัดเป็น 1%");
        PipelineFields.Parsed<Integer> half = PipelineFields.probability("60.5%", null, false);
        assertThat(half.value()).isEqualTo(61);
        assertThat(half.warning()).isEqualTo("ปัดเป็น 61%");
        assertThat(PipelineFields.probability("60%", null, false).warning()).isNull();
        // the fraction rule is for BARE numbers only
        assertThat(PipelineFields.probability("0.6", null, false).value()).isEqualTo(60);
    }

    @Test
    void aQuotationCellSplitsOnSlashAndCommaIntoRevisionlessBases() {
        assertThat(PipelineFields.quotationBases("QT-2026-0201-7 / qt-2026-0201-8, QT-2026-0300"))
            .containsExactly("QT-2026-0201", "QT-2026-0300");
        assertThat(PipelineFields.quotationBases("  ")).isEmpty();
        assertThat(PipelineFields.quotationBases(null)).isEmpty();
    }

    // ── titles ─────────────────────────────────────────────────────────────────

    @Test
    void normaliseFoldsCaseAndWhitespace() {
        assertThat(PipelineFields.normalise("  BMW  Showroom\tเชียงใหม่ ")).isEqualTo("bmw showroom เชียงใหม่");
    }

    // ── S-code → stage, respecting DealRoute ──────────────────────────────────

    @ParameterizedTest
    @CsvSource({
        "S1,PRESENTATION", "S2,SPEC_APPROVED", "S5,AWAITING_BUYER", "S6,NEGOTIATION",
    })
    void designerLedRouteTakesThePlannedStage_whereAFreshDealsFactsHold(String code, String stage) {
        PipelineStages.StageChoice choice = PipelineStages.stageFor(code, EntryChannel.DESIGNER_LED, PipelineStagesFacts.FRESH_DEAL);
        assertThat(choice.stage()).isEqualTo(stage);
        assertThat(choice.warning()).isNull();
        assertThat(DealStage.repCodeOf(choice.stage())).isEqualTo(code);
    }

    @Test
    void s3WithNoQuotationInTheAppLandsOnS2_withTheReason() {
        PipelineStages.StageChoice choice = PipelineStages.stageFor("S3", EntryChannel.DESIGNER_LED,
            PipelineStagesFacts.FRESH_DEAL);
        assertThat(choice.stage()).isEqualTo(DealStage.SPEC_APPROVED);
        assertThat(choice.warning()).isEqualTo("S3 ในชีท → นำเข้าเป็น S2 เพราะยังไม่มีใบเสนอราคาในระบบ");
    }

    @ParameterizedTest
    @CsvSource({"S7", "S8", "S9"})
    void documentStagesAreNeverImported(String code) {
        assertThat(PipelineStages.needsDocument(code)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"S1", "S2", "S3", "S5", "S6"})
    void earlierStagesAreImportable(String code) {
        assertThat(PipelineStages.needsDocument(code)).isFalse();
    }

    @Test
    void ownerDirectS3StaysS3OnTheOwnersQuoteStage_whenItsFactHolds() {
        PipelineStages.StageChoice choice = PipelineStages.stageFor("S3", EntryChannel.OWNER_DIRECT, PipelineStagesFacts.ALL_HOLD);
        assertThat(choice.stage()).isEqualTo(DealStage.QUOTE_OWNER);
        assertThat(choice.warning()).isNull();
    }

    @Test
    void ownerDirectS3WithNoQuotationLandsOnS2() {
        PipelineStages.StageChoice choice = PipelineStages.stageFor("S3", EntryChannel.OWNER_DIRECT,
            PipelineStagesFacts.FRESH_DEAL);
        assertThat(choice.stage()).isEqualTo(DealStage.SPEC_APPROVED);
        assertThat(choice.warning()).contains("S3 ในชีท → นำเข้าเป็น S2");
    }

    @Test
    void buyerDirectS3FallsBackToTheNearestEarlierOnRouteStageWithAWarning() {
        PipelineStages.StageChoice choice = PipelineStages.stageFor("S3", EntryChannel.BUYER_DIRECT, PipelineStagesFacts.FRESH_DEAL);
        assertThat(choice.stage()).isEqualTo(DealStage.SPEC_APPROVED);
        assertThat(choice.warning()).contains("S3").contains("S2");
    }

    @Test
    void buyerDirectS5FallsBackToSpecApproved() {
        PipelineStages.StageChoice choice = PipelineStages.stageFor("S5", EntryChannel.BUYER_DIRECT, PipelineStagesFacts.FRESH_DEAL);
        assertThat(choice.stage()).isEqualTo(DealStage.SPEC_APPROVED);
        assertThat(choice.warning()).isNotBlank();
    }

    @Test
    void everyChoiceIsOnRoute() {
        for (String channel : new String[] {EntryChannel.DESIGNER_LED, EntryChannel.OWNER_DIRECT,
            EntryChannel.BUYER_DIRECT}) {
            for (String code : new String[] {"S1", "S2", "S3", "S5", "S6"}) {
                String stage = PipelineStages.stageFor(code, channel, PipelineStagesFacts.FRESH_DEAL).stage();
                assertThat(th.co.glr.hr.ticket.DealRoute.isOnRoute(channel, stage)).as("%s %s", channel, code).isTrue();
                assertThat(th.co.glr.hr.ticket.TicketService.stageFactRefusal(null, null, java.util.Set.of(), stage))
                    .as("facts %s %s", channel, code).isNull();
            }
        }
    }

    // ── entry channel from the named parties ──────────────────────────────────

    @Test
    void channelFollowsTheFirstNamedParty() {
        assertThat(PipelineStages.channelFor("PIA", "เจ้าของ", "ผู้รับเหมา")).isEqualTo(EntryChannel.DESIGNER_LED);
        assertThat(PipelineStages.channelFor(" ", "เจ้าของ", "ผู้รับเหมา")).isEqualTo(EntryChannel.OWNER_DIRECT);
        assertThat(PipelineStages.channelFor(null, null, "ผู้รับเหมา")).isEqualTo(EntryChannel.BUYER_DIRECT);
        assertThat(PipelineStages.channelFor(null, "", null)).isNull();
    }
}
