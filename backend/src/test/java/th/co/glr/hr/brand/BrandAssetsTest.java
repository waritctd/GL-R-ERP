package th.co.glr.hr.brand;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

// Direct coverage of the extracted classpath read - previously this logic lived inline in
// BrandController and was only exercised indirectly via BrandControllerTest. Now that
// NotificationEmailService also depends on this class, it gets its own test rather than staying
// implicit.
class BrandAssetsTest {
    private final BrandAssets brandAssets = new BrandAssets();

    @Test
    void logoBytesReturnsTheRealPngWhenTheResourceExists() {
        // static/brand/glr-logo.png is committed (the real GL&R artwork, 360x195 RGB, ~45KB) - if
        // it's ever removed without updating this class, this test goes red instead of both the
        // HTTP endpoint and the inline-CID email silently degrading in production.
        byte[] bytes = brandAssets.logoBytes();

        assertThat(bytes).isNotEmpty();
        // PNG signature: 89 50 4E 47 0D 0A 1A 0A - confirms these are real image bytes, not just
        // "some non-empty array". 0x89 needs an explicit (byte) cast: 137 isn't representable in
        // byte's constant-narrowing range (-128..127), unlike the other, smaller literals here.
        assertThat(bytes).startsWith((byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
    }
}
