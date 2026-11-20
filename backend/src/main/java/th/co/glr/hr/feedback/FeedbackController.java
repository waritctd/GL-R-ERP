package th.co.glr.hr.feedback;

import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.feedback.FeedbackDtos.DecisionRequest;
import th.co.glr.hr.feedback.FeedbackDtos.FeedbackDto;
import th.co.glr.hr.feedback.FeedbackDtos.Person;
import th.co.glr.hr.feedback.FeedbackDtos.RequestersRequest;
import th.co.glr.hr.feedback.FeedbackDtos.SubmitRequest;
import th.co.glr.hr.feedback.FeedbackDtos.UpdateRequest;

/** Identity-gated (any signed-in employee) for submit + own (any-requester) list; admin-only gates live in {@link FeedbackService}. */
@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {
    private final FeedbackService feedback;
    private final SessionContext sessions;

    public FeedbackController(FeedbackService feedback, SessionContext sessions) {
        this.feedback = feedback;
        this.sessions = sessions;
    }

    @PostMapping
    FeedbackDto submit(@RequestBody SubmitRequest request, HttpSession session) {
        return feedback.submit(sessions.requireUser(session), request);
    }

    @GetMapping("/mine")
    List<FeedbackDto> mine(HttpSession session) {
        return feedback.listMine(sessions.requireUser(session));
    }

    @GetMapping
    List<FeedbackDto> all(HttpSession session) {
        return feedback.listAll(sessions.requireUser(session));
    }

    @PostMapping("/{id}/decision")
    FeedbackDto decide(@PathVariable long id, @RequestBody DecisionRequest request, HttpSession session) {
        return feedback.decide(sessions.requireUser(session), id, request);
    }

    @GetMapping("/people")
    List<Person> people(HttpSession session) {
        return feedback.people(sessions.requireUser(session));
    }

    @PutMapping("/{id}/requesters")
    FeedbackDto setRequesters(@PathVariable long id, @RequestBody RequestersRequest request, HttpSession session) {
        return feedback.setRequesters(sessions.requireUser(session), id, request);
    }

    @PatchMapping("/{id}")
    FeedbackDto update(@PathVariable long id, @RequestBody UpdateRequest request, HttpSession session) {
        return feedback.update(sessions.requireUser(session), id, request);
    }
}
