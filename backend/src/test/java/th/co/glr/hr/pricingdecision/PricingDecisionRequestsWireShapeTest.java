package th.co.glr.hr.pricingdecision;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.UpdatePricingDecisionItemRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.UpdatePricingDecisionRequest;
import tools.jackson.databind.json.JsonMapper;

/** Wire-shape pin for PUT /api/pricing-decisions/{id}: deserialises raw JSON with the same mapper family Spring
 * MVC uses (Jackson 3, tools.jackson). Absent flag == false. */
class PricingDecisionRequestsWireShapeTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void frontendOverridePayloadOmittingClearFlagsDeserialises() throws Exception {
        String json = """
            {"items":[{"pricingDecisionItemId":1,"sellingPriceOverride":1800,
                       "clearSellingPriceOverride":false,"decisionNote":"x"}]}""";

        UpdatePricingDecisionRequest req = mapper.readValue(json, UpdatePricingDecisionRequest.class);

        assertThat(req.items()).hasSize(1);
        UpdatePricingDecisionItemRequest item = req.items().get(0);
        assertThat(item.pricingDecisionItemId()).isEqualTo(1L);
        assertThat(item.sellingPriceOverride()).isEqualByComparingTo(new BigDecimal("1800"));
        assertThat(item.clearSellingPriceOverride()).isFalse();
        assertThat(item.decisionNote()).isEqualTo("x");
        assertThat(item.clearDiscountPct()).isFalse();
        assertThat(item.clearSpecialPriceSqm()).isFalse();
        assertThat(item.clearDirectNetPrice()).isFalse();
    }

    @Test
    void itemWithOnlyIdDeserialisesWithAllFlagsFalseAndAllNullablesNull() throws Exception {
        UpdatePricingDecisionItemRequest item = mapper.readValue(
            "{\"pricingDecisionItemId\":1}", UpdatePricingDecisionItemRequest.class);

        assertThat(item.pricingDecisionItemId()).isEqualTo(1L);
        assertThat(item.clearSellingPriceOverride()).isFalse();
        assertThat(item.clearDiscountPct()).isFalse();
        assertThat(item.clearSpecialPriceSqm()).isFalse();
        assertThat(item.clearDirectNetPrice()).isFalse();
        assertThat(item.marginPct()).isNull();
        assertThat(item.minimumSellingPrice()).isNull();
        assertThat(item.decisionNote()).isNull();
        assertThat(item.sellingPriceOverride()).isNull();
        assertThat(item.discountPct()).isNull();
        assertThat(item.specialPriceSqm()).isNull();
        assertThat(item.directNetPrice()).isNull();
        assertThat(item.listUnitPrice()).isNull();
    }

    @Test
    void explicitTrueClearFlagsAllRoundTripTrue() throws Exception {
        UpdatePricingDecisionItemRequest item = mapper.readValue(
            "{\"pricingDecisionItemId\":1,\"clearSellingPriceOverride\":true,"
                + "\"clearDiscountPct\":true,\"clearSpecialPriceSqm\":true,"
                + "\"clearDirectNetPrice\":true}",
            UpdatePricingDecisionItemRequest.class);

        assertThat(item.clearSellingPriceOverride()).isTrue();
        assertThat(item.clearDiscountPct()).isTrue();
        assertThat(item.clearSpecialPriceSqm()).isTrue();
        assertThat(item.clearDirectNetPrice()).isTrue();
    }

    @Test
    void headerOnlyPriceModePayloadStillDeserialises() throws Exception {
        UpdatePricingDecisionRequest req = mapper.readValue(
            "{\"priceMode\":\"NET\"}", UpdatePricingDecisionRequest.class);

        assertThat(req.priceMode()).isEqualTo("NET");
        assertThat(req.items()).isNull();
        assertThat(req.ceoNote()).isNull();
    }
}
