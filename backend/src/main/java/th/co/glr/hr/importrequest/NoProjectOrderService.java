package th.co.glr.hr.importrequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.DealEntryAccess;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.common.ThaiText;
import th.co.glr.hr.factory.FactoryConfigDto;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.importrequest.ImportRequestDtos.ImportRequestDto;
import th.co.glr.hr.importrequest.ImportRequestRequests.ImportRequestItemInput;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.ticket.TicketEventKind;

/**
 * สั่งของไม่มีโครงการ: an import request for a sample, a showroom or stock, owned by a requester and a purpose instead of a
 * deal. Whoever may enter a deal may raise one ({@link DealEntryAccess}); there is no approval, so it is ISSUED at once with
 * a normal IR number at CONTACTED and waits in รอจัดรอบขนส่ง. No quotation, no money, no stage; nothing is ever delivered
 * from it. Every field is required, here as on the form.
 */
@Service
public class NoProjectOrderService {

    static final Map<String, String> PURPOSE_LABELS = Map.of("SAMPLE", "ตัวอย่าง", "SHOWROOM", "โชว์รูม", "STOCK", "สต็อก");
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    /** sales.import_request_item.qty is NUMERIC(12,2). */
    private static final BigDecimal MAX_QTY = new BigDecimal("9999999999.99");

    private final ImportRequestRepository stored;
    private final FactoryConfigRepository factories;
    private final NotificationRepository notifications;
    private final EmployeeAuthRepository employeeAuth;

    public NoProjectOrderService(ImportRequestRepository stored, FactoryConfigRepository factories,
                                 NotificationRepository notifications, EmployeeAuthRepository employeeAuth) {
        this.stored = stored;
        this.factories = factories;
        this.notifications = notifications;
        this.employeeAuth = employeeAuth;
    }

    public record Line(String code, String size, BigDecimal qty, String unit) {}

    public record CreateRequest(String purpose, String purposeNote, Long factoryId, LocalDate wantedBy, List<Line> lines) {}

    @Transactional
    public ImportRequestDto create(CreateRequest request, UserPrincipal actor) {
        DealEntryAccess.requireCanEnterDeal(actor, employeeAuth);
        if (request == null || request.purpose() == null || !PURPOSE_LABELS.containsKey(request.purpose())) {
            throw bad("เลือกว่าสั่งเพื่ออะไร (ตัวอย่าง / โชว์รูม / สต็อก)");
        }
        String note = request.purposeNote() == null ? "" : request.purposeNote().trim();
        if (note.isEmpty()) {
            throw bad("ระบุว่าให้ใคร / ใช้ที่ไหน");
        }
        // The note is also the form's "สั่งมาให้" (customer_name VARCHAR(200)), so it is capped at 200.
        if (note.length() > 200) {
            throw bad("ให้ใคร / ใช้ที่ไหน ยาวเกิน 200 ตัวอักษร");
        }
        FactoryConfigDto factory = request.factoryId() == null ? null : factories.findById(request.factoryId()).orElse(null);
        if (factory == null) {
            throw bad("เลือกโรงงาน");
        }
        if (request.lines() == null || request.lines().isEmpty()) {
            throw bad("ใส่สินค้าอย่างน้อย 1 รายการ");
        }
        List<ImportRequestItemInput> items = request.lines().stream().map(l -> {
            String code = l.code() == null ? "" : l.code().trim();
            String unit = l.unit() == null ? "" : l.unit().trim();
            if (code.isEmpty() || code.length() > 255) {
                throw bad("ระบุรหัสหรือชื่อสินค้าของทุกรายการ");
            }
            if (l.qty() == null || l.qty().signum() <= 0) {
                throw bad("จำนวนต้องมากกว่า 0 ทุกรายการ");
            }
            if (l.qty().stripTrailingZeros().scale() > 2) {
                throw bad("จำนวนมีทศนิยมได้ไม่เกิน 2 ตำแหน่ง");
            }
            if (l.qty().compareTo(MAX_QTY) > 0) {
                throw bad("จำนวนต้องไม่เกิน 9,999,999,999.99");
            }
            if (unit.isEmpty() || unit.length() > 30) {
                throw bad("ระบุหน่วยของทุกรายการ");
            }
            String size = l.size() == null || l.size().isBlank() ? null : l.size().trim();
            if (size != null && size.length() > 80) {
                throw bad("ขนาดยาวเกิน 80 ตัวอักษร");
            }
            return new ImportRequestItemInput(null, code, size, l.qty(), unit, null, null, null, null, null);
        }).toList();
        LocalDate today = LocalDate.now(BANGKOK);
        if (request.wantedBy() == null || request.wantedBy().isBefore(today)) {
            throw bad("ระบุวันที่ต้องการของ (วันนี้หรือหลังจากนี้)");
        }

        String doc = stored.nextDocNumber(today.getYear() + 543);
        String label = PURPOSE_LABELS.get(request.purpose());
        long id = stored.insertNoProjectOrder(factory.id(), factory.factoryName(), request.purpose(), label, actor.id(),
            actor.name(), note, request.wantedBy(), ThaiText.date(request.wantedBy()), doc, today, actor.id(), actor.name(),
            actor.email());
        stored.replaceItems(id, items);
        notifications.notifyByRoleAtLink("import", TicketEventKind.IMPORT_REQUEST_ISSUED,
            actor.name() + " ออกใบขอซื้อ (IR) " + doc + " สำหรับโรงงาน " + factory.factoryName() + " แล้ว\n" + label + " · " + note
                + "\nสิ่งที่ต้องทำต่อ: จัดรอบขนส่ง แล้วสั่งซื้อกับโรงงาน", "/fulfilment");
        return stored.findById(id).orElseThrow();
    }

    /**
     * Takes back a no-project order that is still on no round: the requester, import or the CEO. SUPERSEDED with no
     * successor, so its IR number stays on record; it leaves รอจัดรอบ and ของที่ฉันสั่ง. Import is told when the requester
     * did it. Once a lot is on a round, import takes the lot off first.
     */
    @Transactional
    public ImportRequestDto withdraw(long id, UserPrincipal actor) {
        var meta = stored.findNoProjectMeta(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบคำขอสั่งของนี้"));
        boolean requester = actor.id() == meta.requesterId();
        if (!requester && !"import".equals(actor.role()) && !"ceo".equals(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        ImportRequestDto row = stored.findById(id).orElseThrow();
        if (stored.withdrawNoProject(id) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, ImportRequestStatus.ISSUED.equals(row.status())
                ? "คำขอนี้อยู่ในรอบขนส่งแล้ว ให้ฝ่ายนำเข้าเอาออกจากรอบก่อนจึงยกเลิกได้" : "คำขอนี้ถูกยกเลิกไปแล้ว");
        }
        if (requester && !"import".equals(actor.role())) {
            notifications.notifyByRoleAtLink("import", "IMPORT_ORDER_WITHDRAWN",
                actor.name() + " ยกเลิกคำขอสั่งของ " + row.docNumber() + " (" + PURPOSE_LABELS.get(meta.purpose()) + " · "
                    + meta.purposeNote() + ")\nสิ่งที่ต้องทำต่อ: ไม่ต้องสั่งซื้อรายการนี้", "/fulfilment");
        }
        return stored.findById(id).orElseThrow();
    }

    private static ApiException bad(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }
}
