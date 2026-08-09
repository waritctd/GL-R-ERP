package th.co.glr.hr.brand;

import java.util.concurrent.TimeUnit;
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
 * <p>{@link th.co.glr.hr.notification.NotificationEmailService} no longer fetches the logo from
 * this endpoint over HTTP - it embeds the same bytes inline via {@link BrandAssets#logoBytes()}
 * and a {@code cid:} reference in the HTML, which also survives mail clients that block or delay
 * remote image fetches (the failure this endpoint alone could not prevent: Gmail was blocking the
 * remote fetch itself, so no amount of server-side health here could have fixed it). This
 * controller stays independent and useful on its own regardless - it is still what a browser or
 * any other caller hits to fetch the logo over HTTP, and {@code SecurityConfig}'s permit for it is
 * unrelated to email and stays in place. The classpath read itself lives in {@link BrandAssets},
 * not here - both this controller and {@code NotificationEmailService} depend on that shared
 * component instead of one depending on the other.
 *
 * <p>The logo asset itself ({@code static/brand/glr-logo.png}) may be absent - both this endpoint
 * and the email path degrade gracefully rather than throwing: this returns a clean 404, and
 * {@link BrandAssets#logoBytes()} returns an empty array, in which case the email sends with no
 * inline image and falls back to the {@code <img>} tag's alt text. There is no duplicate text
 * wordmark rendered beside the {@code <img>}: the header relies on styled {@code alt=} text as its
 * sole fallback (see {@code NotificationEmailService}'s HTML builder), so a blocked/missing image
 * degrades to that alt text rather than to a second wordmark.
 */
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
            // Email clients and Gmail's image proxy cache aggressively; this is a static,
            // versionless asset so a long, public, immutable-ish cache is safe.
            .cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePublic())
            .body(bytes);
    }
}
