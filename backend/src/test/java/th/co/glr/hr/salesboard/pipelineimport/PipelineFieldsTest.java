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
    @CsvSource(delimiter = '|', value = {"USD 1,000", "$1000", "€5", "หนึ่งล้าน", "1.2.3", "-500"})
    void nonThbOrNonNumericValueIsAnError(String raw) {
        assertThat(PipelineFields.value(raw, null).value()).isNull();
        assertThat(PipelineFields.value(raw, null).error()).isNotBlank();
    }

    @Test
    void blankValueIsAbsentNotAnError() {
        assertThat(PipelineFields.value(" ", null).value()).isNull();
        assertThat(PipelineFields.value(" ", null).error()).isNull();
    }

    // ── win probability ────────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({"60,60", "60%,60", " 25 % ,25", "0,0", "100,100", "72.4,72"})
    void probabilityFromText(String raw, int expected) {
        assertThat(PipelineFields.probability(raw, null, false).value()).isEqualTo(expected);
    }

    @Test
    void probabilityFromAPercentFormattedCell() {
        assertThat(PipelineFields.probability("60%", 0.6, true).value()).isEqualTo(60);
        assertThat(PipelineFields.probability("60", 60.0, false).value()).isEqualTo(60);
    }

    @ParameterizedTest
    @CsvSource({"101", "-1", "สูง"})
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

    // ── titles ─────────────────────────────────────────────────────────────────

    @Test
    void normaliseFoldsCaseAndWhitespace() {
        assertThat(PipelineFields.normalise("  BMW  Showroom\tเชียงใหม่ ")).isEqualTo("bmw showroom เชียงใหม่");
    }

    // ── S-code → stage, respecting DealRoute ──────────────────────────────────

    @ParameterizedTest
    @CsvSource({
        "S1,PRESENTATION", "S2,SPEC_APPROVED", "S3,QUOTE_DESIGN_SIDE", "S5,AWAITING_BUYER", "S6,NEGOTIATION",
        "S7,DEPOSIT_RECEIVED", "S8,DELIVERY_SCHEDULING", "S9,DELIVERED",
    })
    void designerLedRouteTakesThePlannedStage(String code, String stage) {
        PipelineStages.StageChoice choice = PipelineStages.stageFor(code, EntryChannel.DESIGNER_LED);
        assertThat(choice.stage()).isEqualTo(stage);
        assertThat(choice.warning()).isNull();
        assertThat(DealStage.repCodeOf(choice.stage())).isEqualTo(code);
    }

    @Test
    void ownerDirectS3StaysS3OnTheOwnersQuoteStage() {
        PipelineStages.StageChoice choice = PipelineStages.stageFor("S3", EntryChannel.OWNER_DIRECT);
        assertThat(choice.stage()).isEqualTo(DealStage.QUOTE_OWNER);
        assertThat(choice.warning()).isNull();
    }

    @Test
    void buyerDirectS3FallsBackToTheNearestEarlierOnRouteStageWithAWarning() {
        PipelineStages.StageChoice choice = PipelineStages.stageFor("S3", EntryChannel.BUYER_DIRECT);
        assertThat(choice.stage()).isEqualTo(DealStage.SPEC_APPROVED);
        assertThat(choice.warning()).contains("S3").contains("S2");
    }

    @Test
    void buyerDirectS5FallsBackToSpecApproved() {
        PipelineStages.StageChoice choice = PipelineStages.stageFor("S5", EntryChannel.BUYER_DIRECT);
        assertThat(choice.stage()).isEqualTo(DealStage.SPEC_APPROVED);
        assertThat(choice.warning()).isNotBlank();
    }

    @Test
    void everyChoiceIsOnRoute() {
        for (String channel : new String[] {EntryChannel.DESIGNER_LED, EntryChannel.OWNER_DIRECT,
            EntryChannel.BUYER_DIRECT}) {
            for (String code : new String[] {"S1", "S2", "S3", "S5", "S6", "S7", "S8", "S9"}) {
                assertThat(th.co.glr.hr.ticket.DealRoute.isOnRoute(channel,
                    PipelineStages.stageFor(code, channel).stage())).as("%s %s", channel, code).isTrue();
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
