package th.co.glr.hr.importrequest;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.OversightReader;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.importrequest.MyIncomingDtos.MyIncomingResponse;
import th.co.glr.hr.importrequest.ShipmentRoundRepository.MyLine;

/**
 * ทั้งบริษัท on ของที่ฉันสั่ง: the progress of every import request for the whole sales team (spec 0001). This is the only
 * read widened to all sales, and its row type has no field for a price, a factory document or a round note. It grants
 * nothing else: another rep's job, import request and round still answer 403 by id, and such a row carries canOpen false.
 */
@Service
public class CompanyIncomingService {

    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

    private final ShipmentRoundRepository rounds;
    private final EmployeeAuthRepository employeeAuth;

    public CompanyIncomingService(ShipmentRoundRepository rounds, EmployeeAuthRepository employeeAuth) {
        this.rounds = rounds;
        this.employeeAuth = employeeAuth;
    }

    public MyIncomingResponse all(UserPrincipal actor) {
        String role = actor.role();
        boolean salesSide = "sales".equals(role) || "sales_manager".equals(role) || "ceo".equals(role);
        boolean reader = OversightReader.holder(actor, employeeAuth::canReadAllSales);
        if (!salesSide && !reader) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        LocalDate cutoff = LocalDate.now(BANGKOK).minusDays(MyIncomingService.ARRIVED_DAYS);
        List<MyLine> lines = rounds.myLines(actor.id(), true, true, false, cutoff);
        // canOpen is what ของฉัน already scopes to, in one query. It only offers a link: each page still checks by id.
        boolean manager = "sales_manager".equals(role) || "ceo".equals(role);
        boolean allDeals = manager || reader || employeeAuth.canManageAllSalesDocuments(actor);
        Set<Long> mine = rounds.myLines(actor.id(), allDeals, manager || reader, employeeAuth.canSeeShowroomOrders(actor.id()), cutoff)
            .stream().map(MyLine::importRequestId).collect(Collectors.toSet());
        return new MyIncomingResponse(MyIncomingService.toRows(lines, cutoff, l -> false, l -> mine.contains(l.importRequestId())));
    }
}
