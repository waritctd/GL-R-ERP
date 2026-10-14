package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiExceptionHandler;
import th.co.glr.hr.config.CsrfCookieFilter;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.leave.LeaveAttachmentRepository;

/**
 * The four เอกสารแนบ routes over HTTP: the real {@link DealQuotationAttachmentController} in front
 * of the real {@link DealQuotationAttachmentService} and a real Postgres, with the application's
 * own {@link CsrfCookieFilter} ahead of it. No mock in the path.
 *
 * <p>What the service-level tests cannot show: the multipart part binds under the name the
 * frontend sends ({@code file}); the envelopes are {@code {attachment}}, {@code {items}} and
 * {@code {ok}}; a refusal's status reaches the caller and no file byte travels with it; and the
 * upload is refused by the CSRF filter when the header is missing. The rule itself is
 * {@link DealQuotationAttachmentAuthzIntegrationTest}.
 */
class DealQuotationAttachmentHttpIntegrationTest extends AbstractDealQuotationAttachmentIntegrationTest {

    /** Parentheses, a space and a #: what the Content-Disposition header has to survive. */
    private static final String AWKWARD_NAME = "ใบเสนอราคา ผู้ขาย (VENDOR-7F3A91) #2.pdf";
    private static final String CSRF = "csrf-token-for-this-browser";

    private MockMvc mvc;

    @BeforeEach
    void serveTheRealController() {
        mvc = MockMvcBuilders
            .standaloneSetup(new DealQuotationAttachmentController(attachmentService, new SessionContext()))
            .setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new CsrfCookieFilter())
            .build();
    }

    @Test
    void anUpload_bindsTheFilePart_andTheListAndTheFileRouteServeWhatWasStored() throws Exception {
        DealQuotationDto quotation = draftOwnedBy(salesActor);

        String uploaded = mvc.perform(withCsrf(multipart("/api/deal-quotations/{id}/attachments", quotation.id())
                .file(new MockMultipartFile("file", AWKWARD_NAME, PDF, VENDOR_QUOTE))
                .session(signedIn(salesActor))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.attachment.id").isNumber())
            .andExpect(jsonPath("$.attachment.fileName").value(AWKWARD_NAME))
            .andExpect(jsonPath("$.attachment.mimeType").value(PDF))
            .andExpect(jsonPath("$.attachment.fileSize").value(VENDOR_QUOTE.length))
            .andExpect(jsonPath("$.attachment.quotationId").value(Math.toIntExact(quotation.id())))
            .andExpect(jsonPath("$.attachment.quotationNumber").value(quotation.number()))
            .andExpect(jsonPath("$.attachment.uploadedById").value(Math.toIntExact(salesActor.id())))
            .andExpect(jsonPath("$.attachment.uploadedByName").value(employeeName(salesActor.id())))
            .andExpect(jsonPath("$.attachment.uploadedAt").exists())
            .andExpect(jsonPath("$.attachment.inherited").value(false))
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long id = new ObjectMapper().readTree(uploaded).path("attachment").path("id").asLong();
        assertThat(storedBytes(id)).as("the part's bytes are what was stored").isEqualTo(VENDOR_QUOTE);

        // The approver's list, and the file itself.
        mvc.perform(get("/api/deal-quotations/{id}/attachments", quotation.id()).session(signedIn(ceoActor)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].id").value(Math.toIntExact(id)))
            .andExpect(jsonPath("$.items[0].fileName").value(AWKWARD_NAME))
            .andExpect(jsonPath("$.items[0].inherited").value(false));
        MockHttpServletResponse served = mvc.perform(
                get("/api/deal-quotation-attachments/{id}/file", id).session(signedIn(ceoActor)))
            .andExpect(status().isOk())
            .andExpect(content().bytes(VENDOR_QUOTE))
            .andReturn().getResponse();
        assertThat(served.getContentType()).isEqualTo(PDF);
        // The browser must download the file, never render it as a page of this origin. A Thai name
        // with spaces and punctuation must survive the header.
        ContentDisposition disposition = ContentDisposition.parse(served.getHeader(HttpHeaders.CONTENT_DISPOSITION));
        assertThat(disposition.isAttachment()).as("Content-Disposition: %s", disposition).isTrue();
        assertThat(disposition.getFilename()).isEqualTo(AWKWARD_NAME);
        assertThat(served.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(served.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    }

    @Test
    void withoutTheCsrfHeader_theUploadIsRefusedBeforeTheController_andNothingIsStored() throws Exception {
        DealQuotationDto quotation = draftOwnedBy(salesActor);
        MockMultipartFile part = new MockMultipartFile("file", "vendor.pdf", PDF, VENDOR_QUOTE);

        // Control: with the header, this same session and this same part are stored.
        mvc.perform(withCsrf(multipart("/api/deal-quotations/{id}/attachments", quotation.id())
                .file(part).session(signedIn(salesActor))))
            .andExpect(status().isOk());
        assertThat(filesOf(quotation.id())).as("the upload that carries the header is stored").isEqualTo(1);

        // The browser sends the cookie by itself. Without the header that echoes it: 403.
        mvc.perform(multipart("/api/deal-quotations/{id}/attachments", quotation.id())
                .file(part).session(signedIn(salesActor)).cookie(new Cookie("XSRF-TOKEN", CSRF)))
            .andExpect(status().isForbidden());
        assertThat(filesOf(quotation.id())).as("the refused upload stored nothing").isEqualTo(1);
    }

    @Test
    void aRefusedCaller_getsTheStatus_andNotOneByteOfTheFile() throws Exception {
        DealQuotationDto quotation = draftOwnedBy(salesActor);
        long file = seed(quotation.id(), AWKWARD_NAME, VENDOR_QUOTE, salesActor);
        byte[] certificate = "medical certificate, confidential".getBytes(StandardCharsets.UTF_8);
        long leaveFile = new LeaveAttachmentRepository(jdbc).saveWithContent(quotation.id(), "ใบรับรองแพทย์.pdf",
            "leave/" + quotation.id() + "/certificate.pdf", PDF, (long) certificate.length, employeeActor.id(),
            certificate).id();

        // account opens the quotation itself. It gets neither the list nor the file.
        mvc.perform(get("/api/deal-quotations/{id}/attachments", quotation.id()).session(signedIn(accountActor)))
            .andExpect(status().isForbidden());
        assertThat(bodyOf(get("/api/deal-quotation-attachments/{id}/file", file).session(signedIn(accountActor)), 403))
            .doesNotContain(latin1(VENDOR_QUOTE));
        // A leave file's id, asked for by a rep who may edit the quotation of the same number: 404.
        assertThat(bodyOf(get("/api/deal-quotation-attachments/{id}/file", leaveFile).session(signedIn(salesActor)), 404))
            .doesNotContain(latin1(certificate));
        mvc.perform(withCsrf(delete("/api/deal-quotation-attachments/{id}", leaveFile).session(signedIn(salesActor))))
            .andExpect(status().isNotFound());
        assertThat(storedBytes(leaveFile)).isEqualTo(certificate);
        // Nobody signed in: 401.
        assertThat(bodyOf(get("/api/deal-quotation-attachments/{id}/file", file), 401)).doesNotContain(latin1(VENDOR_QUOTE));

        // Control: the owner is served the very bytes the others were not.
        mvc.perform(get("/api/deal-quotation-attachments/{id}/file", file).session(signedIn(salesActor)))
            .andExpect(status().isOk())
            .andExpect(content().bytes(VENDOR_QUOTE));
    }

    @Test
    void aDelete_removesTheFile_andAnswersOk() throws Exception {
        DealQuotationDto quotation = draftOwnedBy(salesActor);
        long file = seed(quotation.id(), AWKWARD_NAME, VENDOR_QUOTE, salesActor);

        mvc.perform(withCsrf(delete("/api/deal-quotation-attachments/{id}", file).session(signedIn(salesActor))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));

        assertThat(rowExists(file)).isFalse();
        mvc.perform(get("/api/deal-quotations/{id}/attachments", quotation.id()).session(signedIn(salesActor)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void everyRoute_needsASession() throws Exception {
        DealQuotationDto quotation = draftOwnedBy(salesActor);
        long file = seed(quotation.id(), AWKWARD_NAME, VENDOR_QUOTE, salesActor);

        mvc.perform(get("/api/deal-quotations/{id}/attachments", quotation.id())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/deal-quotation-attachments/{id}/file", file)).andExpect(status().isUnauthorized());
        mvc.perform(withCsrf(multipart("/api/deal-quotations/{id}/attachments", quotation.id())
                .file(new MockMultipartFile("file", "vendor.pdf", PDF, VENDOR_QUOTE))))
            .andExpect(status().isUnauthorized());
        mvc.perform(withCsrf(delete("/api/deal-quotation-attachments/{id}", file))).andExpect(status().isUnauthorized());
        assertThat(filesOf(quotation.id())).as("nothing stored, nothing removed").isEqualTo(1);
    }

    // ── a browser ────────────────────────────────────────────────────────────────────────────

    private static MockHttpSession signedIn(UserPrincipal user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, user);
        return session;
    }

    /** The double-submit pair every unsafe request of the real client carries. */
    private static <B extends AbstractMockHttpServletRequestBuilder<B>> B withCsrf(B request) {
        return request.cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF);
    }

    /** The response body of a request that must answer {@code expectedStatus}, byte for char. */
    private String bodyOf(RequestBuilder request, int expectedStatus) throws Exception {
        MockHttpServletResponse response = mvc.perform(request).andExpect(status().is(expectedStatus)).andReturn().getResponse();
        return latin1(response.getContentAsByteArray());
    }

    private static String latin1(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }
}
