package th.co.glr.hr.brand;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

// SecurityConfig permits GET /api/public/brand/** unauthenticated (see SecurityConfig) - not
// re-asserted here via a MockMvc/security slice, since that would just restate the permitAll
// matcher rather than exercise a decision. This is a plain unit test of the controller's own
// logic (the classpath read itself is BrandAssets's job - see BrandAssetsTest). See
// BrandAssetSecurityIntegrationTest for the real-SecurityFilterChain coverage.
class BrandControllerTest {
    private final BrandController controller = new BrandController(new BrandAssets());

    @Test
    void returnsThePngWhenTheResourceExists() {
        // static/brand/glr-logo.png is committed (the real GL&R artwork, 360x195 RGB, ~45KB) - if
        // it's ever removed without updating this endpoint, this test goes red instead of the
        // email silently degrading to a 404'd <img> in production.
        ResponseEntity<byte[]> response = controller.logo();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(response.getBody()).isNotEmpty();
        assertThat(response.getHeaders().getCacheControl()).contains("max-age=");
    }
}
