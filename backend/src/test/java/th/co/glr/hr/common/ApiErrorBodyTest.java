package th.co.glr.hr.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The JSON error body, byte for byte, through the SAME HTTP converter production uses (Boot 4's
 * Jackson-3 {@link JacksonJsonHttpMessageConverter} — see CatalogPricingReadAuthzIntegrationTest's
 * note on why a bare ObjectMapper would not be the production serializer).
 *
 * <p>Quotation ↔ deal linking slice 2 (N6) needed a 409 whose body names the live quotation in
 * TOP-LEVEL fields ({@code liveQuotationId}, {@code number}, {@code docStatus}) — the frontend reads
 * them as {@code ApiError.details.<key>}, where {@code details} is the whole response JSON
 * ({@code frontend/src/api/client.js}). Before this, {@code ApiExceptionHandler#handleApiException}
 * rendered ONLY {@code {message, status}} and {@link ApiException} could not carry anything else.
 *
 * <p>The extension is opt-in and must be invisible to every existing refusal:
 * {@link #anOrdinaryApiException_rendersExactlyMessageAndStatus} is the byte-identical pin — a
 * {@code "details":{}} key, a null, or any reordering fails it.
 */
class ApiErrorBodyTest {

    @RestController
    static class ThrowingController {
        @GetMapping("/t/ordinary")
        String ordinary() {
            throw new ApiException(HttpStatus.CONFLICT, "ใบเสนอราคาไม่ได้อยู่ในสถานะร่างแล้ว จึงแก้ไขไม่ได้");
        }

        @GetMapping("/t/empty-details")
        String emptyDetails() {
            throw new ApiException(HttpStatus.BAD_REQUEST, "x", Map.of());
        }

        @GetMapping("/t/structured")
        String structured() {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("liveQuotationId", 42L);
            details.put("number", "QT-2026-0042-1");
            details.put("docStatus", "DRAFT");
            throw new ApiException(HttpStatus.CONFLICT, "มีใบที่ใช้งานอยู่", details);
        }

        @GetMapping("/t/denied")
        String denied() {
            throw new AccessDeniedException("nope");
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
        .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonMapper.builder()
            .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
            .build()))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();

    @Test
    void anOrdinaryApiException_rendersExactlyMessageAndStatus() throws Exception {
        MvcResult result = mvc.perform(get("/t/ordinary")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(result))
            .isEqualTo("{\"message\":\"ใบเสนอราคาไม่ได้อยู่ในสถานะร่างแล้ว จึงแก้ไขไม่ได้\",\"status\":409}");
    }

    @Test
    void anExplicitlyEmptyDetailsMap_rendersExactlyMessageAndStatus() throws Exception {
        assertThat(body(mvc.perform(get("/t/empty-details")).andReturn())).isEqualTo("{\"message\":\"x\",\"status\":400}");
    }

    /** A handler other than handleApiException builds ErrorResponse through its two-arg constructor. */
    @Test
    void theOtherHandlers_areUnchangedToo() throws Exception {
        MvcResult result = mvc.perform(get("/t/denied")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(result)).isEqualTo("{\"message\":\"ไม่มีสิทธิ์เข้าถึงรายการนี้\",\"status\":403}");
    }

    @Test
    void aStructuredApiException_rendersItsDetailsAsTopLevelFields_afterMessageAndStatus() throws Exception {
        MvcResult result = mvc.perform(get("/t/structured")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(result)).isEqualTo("{\"message\":\"มีใบที่ใช้งานอยู่\",\"status\":409,"
            + "\"liveQuotationId\":42,\"number\":\"QT-2026-0042-1\",\"docStatus\":\"DRAFT\"}");
    }

    @Test
    void aDetailMayNotShadowMessageOrStatus() {
        for (String reserved : new String[] {"message", "status"}) {
            assertThatThrownBy(() -> new ApiException(HttpStatus.CONFLICT, "x", Map.of(reserved, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void theTwoArgumentConstructor_carriesNoDetails() {
        assertThat(new ApiException(HttpStatus.CONFLICT, "x").getDetails()).isEmpty();
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
