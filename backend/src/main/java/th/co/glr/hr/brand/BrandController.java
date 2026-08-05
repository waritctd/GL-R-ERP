package th.co.glr.hr.brand;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves brand assets embedded in transactional email
 * ({@link th.co.glr.hr.notification.NotificationEmailService}'s HTML body). Public and
 * unauthenticated by design: email clients fetch images with no session/cookie, and Gmail
 * additionally proxies images through its own googleusercontent.com host rather than the
 * recipient's browser - neither can complete a login flow. Permitted explicitly in
 * {@code SecurityConfig} (GET {@code /api/public/brand/**}); every other endpoint stays
 * default-deny.
 *
 * <p>The logo asset itself ({@code static/brand/glr-logo.png}) may be absent - this returns a
 * clean 404 rather than throwing. There is no duplicate text wordmark rendered beside the
 * {@code <img>}: the header relies on styled {@code alt=} text as its sole fallback (see
 * {@code NotificationEmailService}'s HTML builder), so a blocked/missing image degrades to that
 * alt text rather than to a second wordmark.
 */
@RestController
@RequestMapping("/api/public/brand")
public class BrandController {
    private static final Logger log = LoggerFactory.getLogger(BrandController.class);
    private static final String LOGO_RESOURCE = "static/brand/glr-logo.png";

    @GetMapping("/logo.png")
    public ResponseEntity<byte[]> logo() {
        Resource resource = new ClassPathResource(LOGO_RESOURCE);
        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }
        try {
            byte[] bytes = resource.getInputStream().readAllBytes();
            return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                // Email clients and Gmail's image proxy cache aggressively; this is a static,
                // versionless asset so a long, public, immutable-ish cache is safe.
                .cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePublic())
                .body(bytes);
        } catch (IOException exception) {
            log.warn("Brand logo resource exists but could not be read: {}", exception.getMessage());
            return ResponseEntity.notFound().build();
        }
    }
}
