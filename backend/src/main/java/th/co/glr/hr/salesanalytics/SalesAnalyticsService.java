package th.co.glr.hr.salesanalytics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;

@Service
public class SalesAnalyticsService {
    static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final Set<String> READ_ROLES = Set.of("ceo", "sales_manager");
    private static final Set<String> WRITE_ROLES = Set.of("ceo");
    private static final String TOO_BIG = "ไฟล์ใหญ่เกิน 2 MB";
    private static final String FORBIDDEN = "ไม่มีสิทธิ์เข้าถึงรายการนี้";
    private static final String[] REQUIRED_KEYS = {"months", "monthly_total", "monthly", "reps"};

    public record SnapshotDto(Object payload, String asOf, OffsetDateTime uploadedAt) {}

    private final SalesAnalyticsRepository repository;
    private final ObjectMapper mapper;

    public SalesAnalyticsService(SalesAnalyticsRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    /** ceo + sales_manager read; the answer is {"snapshot": null} until the first upload. */
    public Map<String, SnapshotDto> latest(UserPrincipal user) {
        if (!READ_ROLES.contains(user.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, FORBIDDEN);
        }
        SnapshotDto dto = repository.findLatest().map(r -> new SnapshotDto(mapper.convertValue(parse(r.payloadJson()), Object.class), r.asOf(), r.uploadedAt()))
            .orElse(null);
        return Collections.singletonMap("snapshot", dto);
    }

    /**
     * CEO only. The role is checked BEFORE the body is touched; the body is read with a hard cap of {@link #MAX_BYTES} + 1
     * bytes (and refused up front when the declared Content-Length is already over), so the server never buffers more than
     * the limit. The document must be a JSON object with the four required keys; the three series must agree in length.
     */
    public void upload(UserPrincipal user, InputStream in, long contentLength) {
        if (!WRITE_ROLES.contains(user.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, FORBIDDEN);
        }
        if (user.employeeId() == null) {
            throw bad("บัญชีผู้ใช้นี้ยังไม่ได้ผูกกับข้อมูลพนักงาน กรุณาติดต่อฝ่ายบุคคล");
        }
        if (contentLength > MAX_BYTES) {
            throw bad(TOO_BIG);
        }
        byte[] bytes;
        try {
            bytes = in.readNBytes(MAX_BYTES + 1);
        } catch (IOException e) {
            throw bad("อ่านไฟล์ไม่ได้");
        }
        if (bytes.length > MAX_BYTES) {
            throw bad(TOO_BIG);
        }
        String body = new String(bytes, StandardCharsets.UTF_8);
        JsonNode root = parse(body);
        if (!root.isObject()) {
            throw bad("ข้อมูลวิเคราะห์ต้องเป็น JSON object");
        }
        for (String key : REQUIRED_KEYS) {
            if (!root.has(key)) {
                throw bad("ข้อมูลวิเคราะห์ขาดคีย์ " + key);
            }
        }
        for (String key : new String[] {"months", "monthly_total", "monthly", "reps"}) {
            if (!root.get(key).isArray()) {
                throw bad(key + " ต้องเป็นอาร์เรย์");
            }
        }
        int n = root.get("months").size();
        if (root.get("monthly_total").size() != n || root.get("monthly").size() != n) {
            throw bad("months, monthly_total และ monthly ต้องมีจำนวนเท่ากัน");
        }
        if (n == 0) {
            throw bad("ข้อมูลวิเคราะห์ต้องมีอย่างน้อย 1 เดือน");
        }
        for (int i = 0; i < n; i++) {
            if (!root.get("months").get(i).isTextual()) {
                throw bad("months[" + i + "] ต้องเป็นข้อความ");
            }
            if (!root.get("monthly_total").get(i).isNumber()) {
                throw bad("monthly_total[" + i + "] ต้องเป็นตัวเลข");
            }
            JsonNode month = root.get("monthly").get(i);
            if (!month.isObject() || !month.path("total").isNumber() || !month.path("reps").isArray()) {
                throw bad("monthly[" + i + "] ต้องเป็น object ที่มี total (ตัวเลข) และ reps (อาร์เรย์)");
            }
            requireRepRows(month.get("reps"), "monthly[" + i + "].reps");
        }
        requireRepRows(root.get("reps"), "reps");
        JsonNode asOf = root.path("as_of");
        repository.insert(body, asOf.isTextual() ? asOf.asText() : null, user.employeeId());
    }

    /** Every row must be an object with a string `rep`, so the dashboard can label it. */
    private static void requireRepRows(JsonNode rows, String where) {
        for (int j = 0; j < rows.size(); j++) {
            if (!rows.get(j).isObject() || !rows.get(j).path("rep").isTextual()) {
                throw bad(where + "[" + j + "] ต้องเป็น object ที่มี rep (ข้อความ)");
            }
        }
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw bad("อ่านไฟล์ JSON ไม่ได้");
        }
    }

    private static ApiException bad(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }
}
