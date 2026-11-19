package th.co.glr.hr.salesboard.pipelineimport;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.EntryChannel;

/** The pure cell → value rules of the one-time pipeline import (plan 2026-11-15 Task 4.1, spec §7.1/§7.9). */
class PipelineFieldsTest {

    // ── S-code → stage, respecting DealRoute ──────────────────────────────────

    @Test
    void s3WithNoQuotationInTheAppLandsOnS2_withTheReason() {
        PipelineStages.StageChoice choice = PipelineStages.stageFor("S3", EntryChannel.DESIGNER_LED,
            PipelineStagesFacts.FRESH_DEAL);
        assertThat(choice.stage()).isEqualTo(DealStage.SPEC_APPROVED);
        assertThat(choice.warning()).isEqualTo("S3 ในชีท → นำเข้าเป็น S2 เพราะยังไม่มีใบเสนอราคาในระบบ");
    }

    @Test
    void ownerDirectS3StaysS3OnTheOwnersQuoteStage_whenItsFactHolds() {
        PipelineStages.StageChoice choice = PipelineStages.stageFor("S3", EntryChannel.OWNER_DIRECT, PipelineStagesFacts.ALL_HOLD);
        assertThat(choice.stage()).isEqualTo(DealStage.QUOTE_OWNER);
        assertThat(choice.warning()).isNull();
    }

}
