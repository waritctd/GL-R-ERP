package th.co.glr.hr.brand;

import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Serves brand assets embedded in transactional email (th.co.glr.hr.notification.NotificationEmailService's
 * HTML body). Permitted explicitly in SecurityConfig (GET /api/public/brand/**); every other endpoint stays
 * default-deny. */
@RestController
@RequestMapping("/api/public/brand")
public class BrandController {
    private final BrandAssets brandAssets;

    public BrandController(BrandAssets brandAssets) {
        this.brandAssets = brandAssets;
    }

    @GetMapping("/logo.png")
    public ResponseEntity<byte[]> logo() {
        byte[] bytes = brandAssets.logoBytes();
        if (bytes.length == 0) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
            .contentType(MediaType.IMAGE_PNG)
            // Email clients and Gmail's image proxy cache aggressively; this is a static, versionless asset so a long,
            // public, immutable-ish cache is safe.
            .cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePublic())
            .body(bytes);
    }
}
