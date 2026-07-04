package th.co.glr.hr.attachment;

import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;

@RestController
@RequestMapping("/api")
public class AttachmentController {
    private final AttachmentRepository attachments;
    private final SessionContext sessions;

    @Value("${app.uploads-dir:./uploads}")
    private String uploadsDir;

    public AttachmentController(AttachmentRepository attachments, SessionContext sessions) {
        this.attachments = attachments;
        this.sessions    = sessions;
    }

    @GetMapping("/tickets/{ticketId}/attachments")
    Map<String, List<AttachmentDto>> list(@PathVariable long ticketId, HttpSession session) {
        sessions.requireUser(session);
        return Map.of("attachments", attachments.findByTicketId(ticketId));
    }

    @PostMapping("/tickets/{ticketId}/attachments")
    Map<String, AttachmentDto> upload(
        @PathVariable long ticketId,
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "attachType", defaultValue = "OTHER") String attachType,
        @RequestParam(value = "quotationId", required = false) Long quotationId,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        if (file.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "ไฟล์ว่างเปล่า");

        String originalName = file.getOriginalFilename() != null
            ? Paths.get(file.getOriginalFilename()).getFileName().toString()
            : "file";
        String ext = originalName.contains(".")
            ? originalName.substring(originalName.lastIndexOf('.'))
            : "";
        String stored = UUID.randomUUID() + ext;
        Path dir  = Paths.get(uploadsDir, String.valueOf(ticketId));
        Path dest = dir.resolve(stored);

        try {
            Files.createDirectories(dir);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        String mime = file.getContentType();
        if (mime == null) mime = URLConnection.guessContentTypeFromName(originalName);
        AttachmentDto dto = attachments.save(ticketId, quotationId, originalName,
            dest.toString(), mime, file.getSize(), attachType.toUpperCase(), user.id());
        return Map.of("attachment", dto);
    }

    @GetMapping("/attachments/{id}/file")
    ResponseEntity<Resource> download(@PathVariable long id, HttpSession session) {
        sessions.requireUser(session);
        AttachmentDto dto = attachments.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบไฟล์"));
        String filePath = attachments.findFilePathById(id);
        if (filePath == null) throw new ApiException(HttpStatus.NOT_FOUND, "ไม่พบไฟล์บนเซิร์ฟเวอร์");

        Resource resource = new FileSystemResource(filePath);
        if (!resource.exists()) throw new ApiException(HttpStatus.NOT_FOUND, "ไฟล์ถูกลบออกจากเซิร์ฟเวอร์แล้ว");

        String mime = dto.mimeType() != null ? dto.mimeType() : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(mime))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(dto.fileName()).build().toString())
            .body(resource);
    }

    @DeleteMapping("/attachments/{id}")
    Map<String, Boolean> delete(@PathVariable long id, HttpSession session) {
        sessions.requireUser(session);
        AttachmentDto dto = attachments.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบไฟล์"));
        String filePath = attachments.findFilePathById(id);
        attachments.delete(id);
        if (filePath != null) {
            try { Files.deleteIfExists(Paths.get(filePath)); } catch (IOException ignored) {}
        }
        return Map.of("ok", true);
    }
}
