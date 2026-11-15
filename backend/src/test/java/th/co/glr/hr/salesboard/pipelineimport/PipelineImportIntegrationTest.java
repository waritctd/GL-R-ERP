package th.co.glr.hr.salesboard.pipelineimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiExceptionHandler;
import th.co.glr.hr.customer.ProjectRepository;
import th.co.glr.hr.dealquotation.AbstractDealQuotationSlice2IntegrationTest;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.salesboard.QuietRule;
import th.co.glr.hr.salesboard.SalesBoardRepository;
import th.co.glr.hr.ticket.DealStage;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * POST /api/sales/pipeline-imports and GET …/template through the real controller, service, repositories and
 * Postgres (plan 2026-11-15 Task 4.1, Review Focus 5). Written wrong-way-round where it matters: a dry run must write
 * NOTHING; a re-run must create NOTHING; another rep's deal must never be matched or touched; sales / account /
 * import / hr must be refused. All names and numbers are invented. Annotated itself — @EnabledIf is not inherited.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
class PipelineImportIntegrationTest extends AbstractDealQuotationSlice2IntegrationTest {

    private static final String URL = "/api/sales/pipeline-imports";
    private static final List<String> COUNTED = List.of(
        "sales.ticket", "sales.ticket_event", "sales.deal_activity", "sales.deal_party", "customers.customer",
        "customers.project");

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;
    private PipelineImportService service;
    private String repCode;
    private String otherRepCode;

    @BeforeEach
    void wireHttp() {
        // The base fixtures label the sales division "SALES"; the real eligibility check reads "SA".
        jdbc.update("UPDATE hr.division SET source_code = 'SA' WHERE source_code = 'SALES'", Map.of());
        service = transactional(new PipelineImportService(new PipelineImportRepository(jdbc), ticketService, tickets,
            customers, new ProjectRepository(jdbc)));
        mvc = MockMvcBuilders
            .standaloneSetup(new PipelineImportController(service, new SessionContext()))
            .setMessageConverters(new ByteArrayHttpMessageConverter(),
                new JacksonJsonHttpMessageConverter(JsonMapper.builder()
                    .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true).build()))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
        repCode = employeeCode(salesActor.id());
        otherRepCode = employeeCode(otherSalesActor.id());
    }

    // ── dry run writes nothing ───────────────────────────────────────────────

    @Test
    void dryRunWritesNothing_inAnyTableTheCommitWouldTouch() throws Exception {
        Map<String, Integer> before = counts();

        JsonNode result = upload(csv(
            row(repCode, "โรงแรมสมมุติ ก", "สตูดิโอสมมุติ", "", "", "QT-T-0001", "1,240,000", "S3", "60", "2026-Q4", ""),
            row(repCode, "อาคารทดลอง ข", "", "บริษัท เจ้าของสมมุติ", "", "", "500000", "S1", "", "", "")),
            true, salesManagerActor);

        assertThat(result.get("toCreate").asInt()).isEqualTo(2);
        assertThat(result.get("committed").asBoolean()).isFalse();
        assertThat(counts()).isEqualTo(before);
    }

    @Test
    void dryRunIsTheDefault_whenTheParameterIsOmitted() throws Exception {
        Map<String, Integer> before = counts();
        MockMultipartFile file = new MockMultipartFile("file", "p.csv", "text/csv",
            csv(row(repCode, "โครงการ ไม่ระบุ dryRun", "สตูดิโอ", "", "", "", "", "S1", "", "", "")));

        MvcResult r = mvc.perform(multipart(URL).file(file).session(session(salesManagerActor))).andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(counts()).isEqualTo(before);
    }

    // ── commit ───────────────────────────────────────────────────────────────

    @Test
    void commitCreatesTheDealOnTheExistingPath_withStageEventAndTracking() throws Exception {
        JsonNode result = upload(csv(row(repCode, "โรงแรมสมมุติ ก", "สตูดิโอสมมุติ", "บริษัท เจ้าของสมมุติ",
            "ผู้รับเหมาสมมุติ", "QT-T-0001", "1,240,000", "ทำใบเสนอราคาแล้ว", "60%", "Q1/70", "ยกมาจากชีท")),
            false, salesManagerActor);

        assertThat(result.get("committed").asBoolean()).isTrue();
        assertThat(result.get("toCreate").asInt()).isEqualTo(1);
        long id = result.get("plan").get(0).get("ticketId").asLong();
        Map<String, Object> t = jdbc.queryForMap("""
            SELECT t.created_by, t.keyed_by, t.title, t.sales_stage, t.entry_channel, t.designer_name, t.owner_name,
                   t.buyer_name, t.expected_quarter, t.win_probability, t.imported_value, t.imported_quotation_no,
                   t.note, t.customer_name, p.name AS project_name, t.status, t.lifecycle
              FROM sales.ticket t JOIN customers.project p ON p.project_id = t.project_id
             WHERE t.ticket_id = :id""", Map.of("id", id));
        assertThat(((Number) t.get("created_by")).longValue()).isEqualTo(salesActor.id());
        assertThat(((Number) t.get("keyed_by")).longValue()).isEqualTo(salesManagerActor.id());
        assertThat(t.get("title")).isEqualTo("โรงแรมสมมุติ ก");
        assertThat(t.get("project_name")).isEqualTo("โรงแรมสมมุติ ก");
        assertThat(t.get("sales_stage")).isEqualTo(DealStage.QUOTE_DESIGN_SIDE);
        assertThat(t.get("entry_channel")).isEqualTo("DESIGNER_LED");
        assertThat(t.get("designer_name")).isEqualTo("สตูดิโอสมมุติ");
        assertThat(t.get("owner_name")).isEqualTo("บริษัท เจ้าของสมมุติ");
        assertThat(t.get("buyer_name")).isEqualTo("ผู้รับเหมาสมมุติ");
        assertThat(t.get("customer_name")).isEqualTo("ผู้รับเหมาสมมุติ");
        assertThat(t.get("expected_quarter")).isEqualTo("2027-Q1");
        assertThat(((Number) t.get("win_probability")).intValue()).isEqualTo(60);
        assertThat((BigDecimal) t.get("imported_value")).isEqualByComparingTo("1240000");
        assertThat(t.get("imported_quotation_no")).isEqualTo("QT-T-0001");
        assertThat(t.get("note")).isEqualTo("ยกมาจากชีท");
        assertThat(t.get("status")).isEqualTo("draft");
        assertThat(t.get("lifecycle")).isEqualTo("ACTIVE");
        // The ordinary creation path's invariants: CREATED event, the "สร้างดีล" activity, a headline party.
        assertThat(eventCount(id, "CREATED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales.deal_activity WHERE ticket_id = :id",
            Map.of("id", id), Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales.deal_party WHERE ticket_id = :id AND is_headline",
            Map.of("id", id), Integer.class)).isEqualTo(1);
        Map<String, Object> ev = jdbc.queryForMap("""
            SELECT from_status, to_status, message FROM sales.ticket_event
             WHERE ticket_id = :id AND kind = 'STAGE_CHANGED'""", Map.of("id", id));
        assertThat(ev.get("from_status")).isEqualTo(DealStage.LEAD_APPROACH);
        assertThat(ev.get("to_status")).isEqualTo(DealStage.QUOTE_DESIGN_SIDE);
        assertThat(ev.get("message")).isEqualTo("นำเข้าจากไฟล์ pipeline");
    }

    @Test
    void ceoCanCommit_althoughTheCeoCannotEnterADealByHand() throws Exception {
        UserPrincipal ceo = actor(createEmployee("ซีอีโอ นำเข้า", "ceo-import@glr.co.th", "EXE", "ผู้บริหาร", null), "ceo");

        JsonNode result = upload(csv(row(repCode, "โครงการ ซีอีโอ", "สตูดิโอ", "", "", "", "", "S2", "", "", "")),
            false, ceo);

        assertThat(result.get("toCreate").asInt()).isEqualTo(1);
        long id = result.get("plan").get(0).get("ticketId").asLong();
        assertThat(summary(id).salesStage()).isEqualTo(DealStage.SPEC_APPROVED);
    }

    // ── idempotency (Review Focus 5) ─────────────────────────────────────────

    @Test
    void theSameFileTwiceCreatesNothingTheSecondTime() throws Exception {
        byte[] file = csv(
            row(repCode, "โรงแรมสมมุติ ก", "สตูดิโอสมมุติ", "", "", "QT-T-0001", "1,240,000", "S3", "60", "2026-Q4", ""),
            row(repCode, "อาคารทดลอง ข", "", "บริษัท เจ้าของสมมุติ", "", "", "500000", "S1", "", "", ""));
        upload(file, false, salesManagerActor);
        int tickets = count("sales.ticket");
        int customersCount = count("customers.customer");

        JsonNode dry = upload(file, true, salesManagerActor);
        JsonNode second = upload(file, false, salesManagerActor);

        assertThat(dry.get("toCreate").asInt()).isZero();
        assertThat(second.get("toCreate").asInt()).isZero();
        assertThat(second.get("toUpdate").asInt()).isZero();
        assertThat(second.get("unchanged").asInt()).isEqualTo(2);
        assertThat(count("sales.ticket")).isEqualTo(tickets);
        assertThat(count("customers.customer")).isEqualTo(customersCount);
    }

    @Test
    void existingDealMatchedByAppQuotationNumber_fillsOnlyBlankFields_andKeepsItsStage() throws Exception {
        long id = deal(salesActor);
        DealQuotationDto q = approved(id, salesActor, "DESIGNER");
        jdbc.update("UPDATE sales.ticket SET designer_name = 'ชื่อที่เซลส์พิมพ์', expected_quarter = NULL,"
            + " owner_name = NULL WHERE ticket_id = :id", Map.of("id", id));
        String stageBefore = summary(id).salesStage();
        int ticketsBefore = count("sales.ticket");

        JsonNode result = upload(csv(row(repCode, "ชื่ออื่นในชีท", "ชื่อในชีท", "เจ้าของในชีท", "", q.number(),
            "900000", "S6", "", "2026-Q3", "")), false, salesManagerActor);

        assertThat(result.get("toUpdate").asInt()).isEqualTo(1);
        assertThat(result.get("plan").get(0).get("ticketId").asLong()).isEqualTo(id);
        assertThat(count("sales.ticket")).isEqualTo(ticketsBefore);
        Map<String, Object> t = jdbc.queryForMap("SELECT designer_name, owner_name, expected_quarter, sales_stage,"
            + " title FROM sales.ticket WHERE ticket_id = :id", Map.of("id", id));
        assertThat(t.get("designer_name")).isEqualTo("ชื่อที่เซลส์พิมพ์");
        assertThat(t.get("owner_name")).isEqualTo("เจ้าของในชีท");
        assertThat(t.get("expected_quarter")).isEqualTo("2026-Q3");
        assertThat(t.get("sales_stage")).isEqualTo(stageBefore);
        assertThat(t.get("title")).isEqualTo("ดีล slice 2");
        assertThat(messages(result, "WARNING")).anyMatch(m -> m.contains("ไม่เปลี่ยนสถานะ"));
    }

    @Test
    void existingDealMatchedByNormalisedTitle_whenTheRowHasNoQuotationNumber() throws Exception {
        long id = deal(salesActor);

        JsonNode result = upload(csv(row(repCode, "  ดีล   SLICE 2 ", "สตูดิโอ", "", "", "", "", "S1", "40", "", "")),
            false, salesManagerActor);

        assertThat(result.get("toUpdate").asInt()).isEqualTo(1);
        assertThat(result.get("plan").get(0).get("ticketId").asLong()).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT win_probability FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", id), Integer.class)).isEqualTo(40);
    }

    @Test
    void anotherRepsDealIsNeverMatchedOrTouched() throws Exception {
        long theirs = deal(otherSalesActor);
        jdbc.update("UPDATE sales.ticket SET designer_name = NULL WHERE ticket_id = :id", Map.of("id", theirs));

        JsonNode result = upload(csv(row(repCode, "ดีล slice 2", "สตูดิโอของฉัน", "", "", "", "", "S1", "", "", "")),
            false, salesManagerActor);

        assertThat(result.get("toCreate").asInt()).isEqualTo(1);
        assertThat(result.get("plan").get(0).get("ticketId").asLong()).isNotEqualTo(theirs);
        assertThat(jdbc.queryForObject("SELECT designer_name FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", theirs), String.class)).isNull();
    }

    @Test
    void aSecondRowForTheSameDealInOneFileIsSkipped() throws Exception {
        JsonNode result = upload(csv(
            row(repCode, "โครงการซ้ำ", "สตูดิโอ", "", "", "QT-DUP", "", "S1", "", "", ""),
            row(repCode, "โครงการซ้ำ อีกชื่อ", "สตูดิโอ", "", "", "qt-dup", "", "S2", "", "", "")),
            false, salesManagerActor);

        assertThat(result.get("toCreate").asInt()).isEqualTo(1);
        assertThat(result.get("skipped").asInt()).isEqualTo(1);
        assertThat(messages(result, "ERROR")).anyMatch(m -> m.contains("ซ้ำกับแถว 2"));
    }

    // ── row issues ───────────────────────────────────────────────────────────

    @Test
    void badRowsAreSkippedWithAReason_andGoodRowsStillImport() throws Exception {
        UserPrincipal accountant = actor(createEmployee("บัญชี ไม่ใช่เซลส์", "acc-pl@glr.co.th", "ACC", "บัญชี", null),
            "account");
        Map<String, Integer> before = counts();

        JsonNode result = upload(csv(
            row("NOBODY-999", "โครงการ ก", "สตูดิโอ", "", "", "", "", "S1", "", "", ""),
            row(repCode, "โครงการ ข", "สตูดิโอ", "", "", "", "", "S4", "", "", ""),
            row(repCode, "โครงการ ค", "สตูดิโอ", "", "", "", "USD 5,000", "S1", "", "", ""),
            row(repCode, "", "สตูดิโอ", "", "", "", "", "S1", "", "", ""),
            row(repCode, "โครงการ จ", "", "", "", "", "", "S1", "", "", ""),
            row(employeeCode(accountant.id()), "โครงการ ฉ", "สตูดิโอ", "", "", "", "", "S1", "", "", ""),
            row(repCode, "โครงการ ดี", "สตูดิโอ", "", "", "", "", "S1", "", "", "")),
            false, salesManagerActor);

        assertThat(result.get("rows").asInt()).isEqualTo(7);
        assertThat(result.get("skipped").asInt()).isEqualTo(6);
        assertThat(result.get("toCreate").asInt()).isEqualTo(1);
        assertThat(messages(result, "ERROR")).anyMatch(m -> m.contains("NOBODY-999"))
            .anyMatch(m -> m.equals("S4 ไม่มีในระบบ เลือก S3 หรือ S5"))
            .anyMatch(m -> m.contains("USD 5,000"))
            .anyMatch(m -> m.contains("ไม่มีชื่อโครงการ"))
            .anyMatch(m -> m.contains("ผู้ออกแบบ"))
            .anyMatch(m -> m.contains("ไม่ใช่พนักงานขาย"));
        assertThat(count("sales.ticket")).isEqualTo(before.get("sales.ticket") + 1);
    }

    @Test
    void repResolvesByEmailToo() throws Exception {
        JsonNode result = upload(csv(row("  SALES-S2@glr.co.th ", "โครงการ อีเมล", "สตูดิโอ", "", "", "", "", "S1",
            "", "", "")), false, salesManagerActor);

        long id = result.get("plan").get(0).get("ticketId").asLong();
        assertThat(jdbc.queryForObject("SELECT created_by FROM sales.ticket WHERE ticket_id = :id", Map.of("id", id),
            Long.class)).isEqualTo(salesActor.id());
    }

    @Test
    void offRouteStage_landsOnTheNearestEarlierOnRouteStage_withAWarningInTheDryRun() throws Exception {
        JsonNode dry = upload(csv(row(repCode, "โครงการ ผู้ซื้อตรง", "", "", "ผู้รับเหมาสมมุติ", "", "", "S5", "", "", "")),
            true, salesManagerActor);
        assertThat(messages(dry, "WARNING")).anyMatch(m -> m.contains("S5") && m.contains("S2"));
        assertThat(dry.get("plan").get(0).get("repCode").asText()).isEqualTo("S2");

        JsonNode done = upload(csv(row(repCode, "โครงการ ผู้ซื้อตรง", "", "", "ผู้รับเหมาสมมุติ", "", "", "S5", "", "",
            "")), false, salesManagerActor);
        long id = done.get("plan").get(0).get("ticketId").asLong();
        assertThat(summary(id).salesStage()).isEqualTo(DealStage.SPEC_APPROVED);
        assertThat(summary(id).entryChannel()).isEqualTo("BUYER_DIRECT");
    }

    @Test
    void anXlsxWithNumericAndPercentCellsCommitsLikeTheCsv() throws Exception {
        byte[] xlsx = PipelineSheetTest.xlsx(wb -> {
            Sheet s = wb.createSheet("pipeline");
            PipelineSheetTest.header(s);
            Row r = s.createRow(1);
            r.createCell(0).setCellValue(repCode);
            r.createCell(1).setCellValue("โครงการ xlsx สมมุติ");
            r.createCell(2).setCellValue("สตูดิโอ xlsx");
            r.createCell(6).setCellValue(1560650.5d);
            r.createCell(7).setCellValue("S6");
            var pct = wb.createCellStyle();
            pct.setDataFormat(wb.createDataFormat().getFormat("0%"));
            var p = r.createCell(8);
            p.setCellValue(0.75);
            p.setCellStyle(pct);
            r.createCell(9).setCellValue("Q4/69");
        });

        JsonNode result = upload("Pipeline.xlsx", xlsx, false, salesManagerActor);

        long id = result.get("plan").get(0).get("ticketId").asLong();
        Map<String, Object> t = jdbc.queryForMap("SELECT sales_stage, imported_value, win_probability,"
            + " expected_quarter FROM sales.ticket WHERE ticket_id = :id", Map.of("id", id));
        assertThat(t.get("sales_stage")).isEqualTo(DealStage.NEGOTIATION);
        assertThat((BigDecimal) t.get("imported_value")).isEqualByComparingTo("1560650.50");
        assertThat(((Number) t.get("win_probability")).intValue()).isEqualTo(75);
        assertThat(t.get("expected_quarter")).isEqualTo("2026-Q4");
    }

    // ── all-or-nothing per file ──────────────────────────────────────────────

    @Test
    void aFailureOnTheLastRowRollsBackTheWholeFile() throws Exception {
        jdbc.getJdbcTemplate().execute("""
            CREATE FUNCTION sales.test_refuse_boom() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN IF NEW.title = 'ระเบิด' THEN RAISE EXCEPTION 'boom'; END IF; RETURN NEW; END $$""");
        jdbc.getJdbcTemplate().execute("CREATE TRIGGER test_refuse_boom BEFORE INSERT ON sales.ticket"
            + " FOR EACH ROW EXECUTE FUNCTION sales.test_refuse_boom()");
        Map<String, Integer> before = counts();

        int status = status(multipart(URL).file(new MockMultipartFile("file", "p.csv", "text/csv", csv(
                row(repCode, "โครงการดี 1", "สตูดิโอ", "", "", "", "", "S1", "", "", ""),
                row(repCode, "โครงการดี 2", "สตูดิโอ", "", "", "", "", "S2", "", "", ""),
                row(repCode, "ระเบิด", "สตูดิโอ", "", "", "", "", "S3", "", "", ""))))
            .param("dryRun", "false"), salesManagerActor);

        assertThat(status).isGreaterThanOrEqualTo(400);
        assertThat(counts()).isEqualTo(before);
    }

    // ── xlsx + template ──────────────────────────────────────────────────────

    @Test
    void theDownloadableTemplateHasTheHeaderAndOnlyExampleRowsOnASecondSheet() throws Exception {
        MvcResult r = mvc.perform(get(URL + "/template").session(session(salesManagerActor))).andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(r.getResponse().getContentAsByteArray()))) {
            Sheet first = wb.getSheetAt(0);
            Row header = first.getRow(0);
            List<String> labels = new ArrayList<>();
            header.forEach(c -> labels.add(c.getStringCellValue()));
            assertThat(labels).isEqualTo(PipelineSheet.TEMPLATE_HEADERS);
            assertThat(first.getLastRowNum()).isZero();
        }
        // The template itself imports as zero rows, not as the examples.
        JsonNode dry = upload("template.xlsx", r.getResponse().getContentAsByteArray(), true, salesManagerActor);
        assertThat(dry.get("rows").asInt()).isZero();
    }

    // ── role gates ───────────────────────────────────────────────────────────

    @Test
    void salesAccountImportAndHrAreRefused_bothModesAndTheTemplate() throws Exception {
        Map<String, Integer> before = counts();
        List<UserPrincipal> refused = new ArrayList<>(List.of(salesActor));
        for (String role : List.of("account", "import", "hr")) {
            refused.add(actor(createEmployee("ผู้ใช้ " + role, role + "-pl@glr.co.th", "ACC", "บัญชี", null), role));
        }
        byte[] file = csv(row(repCode, "โครงการ ห้ามเข้า", "สตูดิโอ", "", "", "", "", "S1", "", "", ""));
        for (UserPrincipal who : refused) {
            for (String dryRun : List.of("true", "false")) {
                assertThat(status(multipart(URL).file(new MockMultipartFile("file", "p.csv", "text/csv", file))
                    .param("dryRun", dryRun), who)).as("%s dryRun=%s", who.role(), dryRun).isEqualTo(403);
            }
            assertThat(status(get(URL + "/template"), who)).as("%s template", who.role()).isEqualTo(403);
        }
        assertThat(counts()).isEqualTo(before);
    }

    // ── limits ───────────────────────────────────────────────────────────────

    @Test
    void aFileOverFiveMegabytesIs400() throws Exception {
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        java.util.Arrays.fill(big, (byte) 'a');

        assertThat(status(multipart(URL).file(new MockMultipartFile("file", "p.csv", "text/csv", big)),
            salesManagerActor)).isEqualTo(400);
    }

    @Test
    void moreThan2000RowsIs400() throws Exception {
        String[][] rows = new String[2001][];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = row(repCode, "โครงการ " + i, "สตูดิโอ", "", "", "", "", "S1", "", "", "");
        }

        assertThat(status(multipart(URL).file(new MockMultipartFile("file", "p.csv", "text/csv", csv(rows))),
            salesManagerActor)).isEqualTo(400);
    }

    // ── the board shows the imported value ───────────────────────────────────

    @Test
    void theBoardShowsTheImportedValue_inThb_whenTheDealHasNoQuotation() throws Exception {
        JsonNode result = upload(csv(row(repCode, "โครงการ มูลค่า", "สตูดิโอ", "", "", "", "2,500,000", "S1", "", "",
            "")), false, salesManagerActor);
        long id = result.get("plan").get(0).get("ticketId").asLong();

        var deal = new SalesBoardRepository(jdbc, new QuietRule(jdbc, 14)).activeDealsOwnedBy(salesActor.id())
            .stream().filter(d -> d.ticketId() == id).findFirst().orElseThrow();

        assertThat(deal.value()).isEqualByComparingTo("2500000");
        assertThat(deal.currency()).isEqualTo("THB");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String[] row(String... cells) {
        return cells;
    }

    private static byte[] csv(String[]... rows) {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append(String.join(",", PipelineSheet.TEMPLATE_HEADERS)).append("\r\n");
        for (String[] r : rows) {
            List<String> quoted = new ArrayList<>();
            for (String c : r) {
                quoted.add("\"" + c.replace("\"", "\"\"") + "\"");
            }
            sb.append(String.join(",", quoted)).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private JsonNode upload(byte[] csv, boolean dryRun, UserPrincipal actor) throws Exception {
        return upload("pipeline.csv", csv, dryRun, actor);
    }

    private JsonNode upload(String name, byte[] bytes, boolean dryRun, UserPrincipal actor) throws Exception {
        MvcResult r = mvc.perform(multipart(URL).file(new MockMultipartFile("file", name, null, bytes))
            .param("dryRun", String.valueOf(dryRun)).session(session(actor))).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString(StandardCharsets.UTF_8))
            .isEqualTo(200);
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private int status(org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder<?> request, UserPrincipal actor) throws Exception {
        return mvc.perform(request.session(session(actor))).andReturn().getResponse().getStatus();
    }

    private static MockHttpSession session(UserPrincipal actor) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, actor);
        return session;
    }

    private static List<String> messages(JsonNode result, String severity) {
        List<String> out = new ArrayList<>();
        result.get("issues").forEach(i -> {
            if (severity.equals(i.get("severity").asText())) {
                out.add(i.get("message").asText());
            }
        });
        return out;
    }

    private String employeeCode(long employeeId) {
        return jdbc.queryForObject("SELECT employee_code FROM hr.employee WHERE employee_id = :id",
            Map.of("id", employeeId), String.class);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Map.of(), Integer.class);
    }

    private Map<String, Integer> counts() {
        Map<String, Integer> m = new LinkedHashMap<>();
        COUNTED.forEach(t -> m.put(t, count(t)));
        return m;
    }
}
