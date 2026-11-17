package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Slice 6.4: the wire name of the new optional field, and that the pre-6.4 body still binds with no date. The record
 * gained a compat constructor; Jackson must still bind through the canonical one (a wrong pick would silently drop
 * {@code nextFollowUpAt}).
 */
class DealActivityRequestJsonTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void nextFollowUpAtBindsFromTheBody() {
        DealActivityRequest request = mapper.readValue(
            "{\"activityDate\":\"2026-11-17\",\"kind\":\"CALL\",\"note\":\"คุยแล้ว\",\"nextFollowUpAt\":\"2026-11-24\"}",
            DealActivityRequest.class);

        assertThat(request.nextFollowUpAt()).isEqualTo(LocalDate.of(2026, 11, 24));
        assertThat(request.note()).isEqualTo("คุยแล้ว");
    }

    @Test
    void theBodyBeforeSlice64StillBinds_withNoFollowUpDate() {
        DealActivityRequest request = mapper.readValue(
            "{\"activityDate\":\"2026-11-17\",\"kind\":\"CALL\",\"note\":null}", DealActivityRequest.class);

        assertThat(request.kind()).isEqualTo("CALL");
        assertThat(request.nextFollowUpAt()).isNull();
    }
}
