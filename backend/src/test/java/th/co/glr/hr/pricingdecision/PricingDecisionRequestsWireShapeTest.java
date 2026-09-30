package th.co.glr.hr.pricingdecision;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.UpdatePricingDecisionItemRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.UpdatePricingDecisionRequest;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wire-shape pin for {@code PUT /api/pricing-decisions/{id}}: deserialises raw JSON with the same
 * mapper family Spring MVC uses (Jackson 3, {@code tools.jackson}). Jackson 3 rejects a body that
 * leaves a primitive {@code boolean} creator property absent, which made the CEO's "ปรับราคาเอง"
 * save (it sends only four item fields) 400 with a bare "คำขอไม่ถูกต้อง". Absent flag == false.
 */
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
    void explicitTrueClearFlagStillRoundTripsTrue() throws Exception {
        UpdatePricingDecisionItemRequest item = mapper.readValue(
            "{\"pricingDecisionItemId\":1,\"clearDiscountPct\":true}",
            UpdatePricingDecisionItemRequest.class);

        assertThat(item.clearDiscountPct()).isTrue();
        assertThat(item.clearSellingPriceOverride()).isFalse();
        assertThat(item.clearSpecialPriceSqm()).isFalse();
        assertThat(item.clearDirectNetPrice()).isFalse();
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
