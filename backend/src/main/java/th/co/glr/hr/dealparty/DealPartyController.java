package th.co.glr.hr.dealparty;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.dealparty.DealPartyDtos.AddPartyRequest;
import th.co.glr.hr.dealparty.DealPartyDtos.BidOutcomeRequest;
import th.co.glr.hr.dealparty.DealPartyDtos.BidOutcomeResponse;
import th.co.glr.hr.dealparty.DealPartyDtos.OpenDealsResponse;
import th.co.glr.hr.dealparty.DealPartyDtos.PartiesResponse;
import th.co.glr.hr.dealparty.DealPartyDtos.PartyResponse;
import th.co.glr.hr.dealparty.DealPartyDtos.PatchPartyRequest;

/** Deal parties and the bid outcome (V204). Sits under {@code /api/tickets} beside the deal's other routes. */
@RestController
@RequestMapping("/api/tickets")
public class DealPartyController {
    private final DealPartyService service;
    private final SessionContext sessions;

    public DealPartyController(DealPartyService service, SessionContext sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    /** The actor's open, pre-outcome deals on this project — the editor's "โครงการนี้มีดีลของคุณอยู่แล้ว" prompt.
     * A literal segment, so it is matched before {@code /{id}}. */
    @GetMapping("/open-for-project")
    OpenDealsResponse openForProject(@RequestParam("projectName") String projectName, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new OpenDealsResponse(service.openDealsForProject(projectName, user));
    }

    @GetMapping("/{id}/parties")
    PartiesResponse list(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new PartiesResponse(service.list(id, user));
    }

    @PostMapping("/{id}/parties")
    @ResponseStatus(HttpStatus.CREATED)
    PartyResponse add(@PathVariable long id, @Valid @RequestBody AddPartyRequest request, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new PartyResponse(service.add(id, request, user));
    }

    @PatchMapping("/{id}/parties/{partyId}")
    PartyResponse patch(@PathVariable long id, @PathVariable long partyId, @RequestBody PatchPartyRequest request,
                        HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new PartyResponse(service.patch(id, partyId, request, user));
    }

    @DeleteMapping("/{id}/parties/{partyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable long id, @PathVariable long partyId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        service.delete(id, partyId, user);
    }

    @PostMapping("/{id}/bid-outcome")
    BidOutcomeResponse bidOutcome(@PathVariable long id, @Valid @RequestBody BidOutcomeRequest request,
                                  HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new BidOutcomeResponse(service.recordBidOutcome(id, request, user));
    }
}
