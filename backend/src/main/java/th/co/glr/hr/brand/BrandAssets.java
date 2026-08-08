package th.co.glr.hr.brand;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Loads brand assets from the classpath. A standalone component - not a static method on
 * {@link BrandController} - because {@link th.co.glr.hr.notification.NotificationEmailService} (a
 * service) also needs these bytes, to embed the logo inline via content-id, and a service reaching
 * into a {@code @RestController} (a web adapter) would invert the normal dependency direction:
 * adapters depend on services, not the other way round. Both {@link BrandController} (the HTTP
 * path, GET {@code /api/public/brand/logo.png}) and
 * {@link th.co.glr.hr.notification.NotificationEmailService} (the inline-CID email path) depend on
 * this instead, so there is exactly one place that knows where the asset lives on disk.
 */
@Component
public final class BrandAssets {
    private static final Logger log = LoggerFactory.getLogger(BrandAssets.class);
    private static final String LOGO_RESOURCE = "static/brand/glr-logo.png";

    /**
     * Returns the brand logo's bytes, or an empty array if the resource is absent or unreadable -
     * never throws. Both callers degrade gracefully on empty: {@link BrandController#logo()} 404s,
     * {@link th.co.glr.hr.notification.NotificationEmailService} sends with no inline image
     * (falling back to the {@code <img>} tag's alt text).
     */
    public byte[] logoBytes() {
        Resource resource = new ClassPathResource(LOGO_RESOURCE);
        if (!resource.exists()) {
            return new byte[0];
        }
        try {
            return resource.getInputStream().readAllBytes();
        } catch (IOException exception) {
            log.warn("Brand logo resource exists but could not be read: {}", exception.getMessage());
            return new byte[0];
        }
    }
}
