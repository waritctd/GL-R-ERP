package th.co.glr.hr.importrequest;

import jakarta.servlet.http.HttpSession;
import java.util.Map;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.importrequest.ShipmentRoundDtos.AdvanceShipmentRoundRequest;
import th.co.glr.hr.importrequest.ShipmentRoundDtos.DocumentRequest;
import th.co.glr.hr.importrequest.ShipmentRoundDtos.UpdateShipmentRoundRequest;
import th.co.glr.hr.importrequest.ShipmentRoundDtos.ShipmentRoundDto;
import th.co.glr.hr.importrequest.ShipmentRoundDtos.ShipmentRoundsResponse;
import th.co.glr.hr.importrequest.ShipmentRoundDtos.UndoShipmentRoundRequest;

/** รอบขนส่ง — import's queue and round page. Authorisation lives in ShipmentRoundService. */
@RestController
@RequestMapping("/api/shipment-rounds")
public class ShipmentRoundController {

    private final ShipmentRoundService service;
    private final SessionContext sessions;

    public ShipmentRoundController(ShipmentRoundService service, SessionContext sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    @GetMapping
    ShipmentRoundsResponse list(HttpSession session) {
        return service.list(sessions.requireUser(session));
    }

    @GetMapping("/{id}")
    Map<String, ShipmentRoundDto> get(@PathVariable long id, HttpSession session) {
        return Map.of("round", service.get(id, sessions.requireUser(session)));
    }

    @PostMapping
    Map<String, ShipmentRoundDto> create(@RequestBody ShipmentRoundDtos.CreateShipmentRoundRequest request, HttpSession session) {
        return Map.of("round", service.createRound(request, sessions.requireUser(session)));
    }

    @PostMapping("/{id}/lots")
    Map<String, ShipmentRoundDto> addLots(@PathVariable long id, @RequestBody ShipmentRoundDtos.AddLotsRequest request, HttpSession session) {
        return Map.of("round", service.addLots(id, request, sessions.requireUser(session)));
    }

    @PutMapping("/{id}/lots/{lotId}")
    Map<String, ShipmentRoundDto> changeLot(@PathVariable long id, @PathVariable long lotId,
                                            @RequestBody ShipmentRoundDtos.ChangeLotRequest request, HttpSession session) {
        return Map.of("round", service.changeLot(id, lotId, request, sessions.requireUser(session)));
    }

    /** "round" is null when the last lot was taken off and the (empty PORTAL) round deleted. */
    @DeleteMapping("/{id}/lots/{lotId}")
    Map<String, Object> removeLot(@PathVariable long id, @PathVariable long lotId, HttpSession session) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("round", service.removeLot(id, lotId, sessions.requireUser(session)));
        return body;
    }

    @PostMapping("/{id}/lots/{lotId}/move")
    Map<String, Object> moveLot(@PathVariable long id, @PathVariable long lotId,
                                @RequestBody ShipmentRoundDtos.MoveLotRequest request, HttpSession session) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("round", service.moveLot(id, lotId, request, sessions.requireUser(session)));
        return body;
    }

    @PutMapping("/{id}/facts")
    Map<String, ShipmentRoundDto> facts(@PathVariable long id, @RequestBody UpdateShipmentRoundRequest request, HttpSession session) {
        return Map.of("round", service.updateFacts(id, request, sessions.requireUser(session)));
    }

    @PostMapping("/{id}/advance")
    Map<String, ShipmentRoundDto> advance(@PathVariable long id, @RequestBody AdvanceShipmentRoundRequest request, HttpSession session) {
        return Map.of("round", service.advance(id, request, sessions.requireUser(session)));
    }

    @PostMapping("/{id}/advance/undo")
    Map<String, ShipmentRoundDto> undo(@PathVariable long id, @RequestBody UndoShipmentRoundRequest request, HttpSession session) {
        return Map.of("round", service.undo(id, request, sessions.requireUser(session)));
    }

    /** Multipart: kind, optional docNo / docDate / note, optional file. A document needs a number or a file. */
    @PostMapping("/{id}/documents")
    Map<String, ShipmentRoundDto> addDocument(@PathVariable long id, @RequestParam String kind,
                                              @RequestParam(required = false) String docNo,
                                              @RequestParam(required = false) java.time.LocalDate docDate,
                                              @RequestParam(required = false) String note,
                                              @RequestParam(value = "file", required = false) MultipartFile file,
                                              HttpSession session) {
        return Map.of("round", service.addDocument(id, kind, docNo, docDate, note, file, sessions.requireUser(session)));
    }

    @PutMapping("/{id}/documents/{docId}")
    Map<String, ShipmentRoundDto> updateDocument(@PathVariable long id, @PathVariable long docId,
                                                 @RequestBody DocumentRequest request, HttpSession session) {
        return Map.of("round", service.updateDocument(id, docId, request, sessions.requireUser(session)));
    }

    @DeleteMapping("/{id}/documents/{docId}")
    Map<String, ShipmentRoundDto> deleteDocument(@PathVariable long id, @PathVariable long docId, HttpSession session) {
        return Map.of("round", service.deleteDocument(id, docId, sessions.requireUser(session)));
    }

    @PostMapping("/{id}/documents/{docId}/file")
    Map<String, ShipmentRoundDto> attachFile(@PathVariable long id, @PathVariable long docId,
                                             @RequestParam("file") MultipartFile file, HttpSession session) {
        return Map.of("round", service.attachFile(id, docId, file, sessions.requireUser(session)));
    }

    @GetMapping("/{id}/documents/{docId}/file")
    ResponseEntity<Resource> download(@PathVariable long id, @PathVariable long docId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        ShipmentRoundRepository.DocumentRow f = service.file(id, docId, user);
        Resource resource = new FileSystemResource(f.filePath());
        if (!resource.exists()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ไฟล์ถูกลบออกจากเซิร์ฟเวอร์แล้ว");
        }
        String mime = f.mimeType() != null ? f.mimeType() : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(mime))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(f.fileName()).build().toString())
            .body(resource);
    }

    @DeleteMapping("/{id}/documents/{docId}/file")
    Map<String, ShipmentRoundDto> removeFile(@PathVariable long id, @PathVariable long docId, HttpSession session) {
        return Map.of("round", service.removeFile(id, docId, sessions.requireUser(session)));
    }
}
