package th.co.glr.hr.salesanalytics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
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
        return java.util.Collections.singletonMap("snapshot", dto);
    }

    /** CEO only. The document must be a JSON object with the four required keys; the three series must agree in length. */
    public void upload(UserPrincipal user, String body) {
        if (!WRITE_ROLES.contains(user.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, FORBIDDEN);
        }
        if (user.employeeId() == null) {
            throw bad("บัญชีผู้ใช้นี้ยังไม่ได้ผูกกับข้อมูลพนักงาน กรุณาติดต่อฝ่ายบุคคล");
        }
        if (body == null || body.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw bad("ไฟล์ใหญ่เกิน 2 MB");
        }
        JsonNode root = parse(body);
        if (!root.isObject()) {
            throw bad("ข้อมูลวิเคราะห์ต้องเป็น JSON object");
        }
        for (String key : REQUIRED_KEYS) {
            if (!root.has(key)) {
                throw bad("ข้อมูลวิเคราะห์ขาดคีย์ " + key);
            }
        }
        for (String key : new String[] {"months", "monthly_total", "monthly"}) {
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
        }
        JsonNode asOf = root.path("as_of");
        repository.insert(body, asOf.isTextual() ? asOf.asText() : null, user.employeeId());
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
