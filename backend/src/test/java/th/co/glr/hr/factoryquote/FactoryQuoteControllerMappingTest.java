package th.co.glr.hr.factoryquote;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * CR-1 (GLA-167) A8 — the route table of {@link FactoryQuoteController}. "ส่งแล้ว" is replaced by
 * "ติดต่อโรงงานแล้ว": {@code POST /api/factory-quotes/{id}/send} must be gone (not aliased) and
 * {@code POST /api/factory-quotes/{id}/contacted} must exist. A reflection check rather than a
 * MockMvc one because it needs no Spring context, so it is cheap and cannot be satisfied by a
 * catch-all handler.
 *
 * <p>The committed digest guard ({@code ApiSurfaceContractTest}) separately fails until
 * {@code docs/api/api-surface.json} is regenerated — that is the repo's own record of the change.
 */
class FactoryQuoteControllerMappingTest {

    @Test
    void theSendRouteIsGone_andTheContactedRouteExists() {
        List<String> posts = new ArrayList<>();
        for (Method m : FactoryQuoteController.class.getDeclaredMethods()) {
            PostMapping post = m.getAnnotation(PostMapping.class);
            if (post != null) {
                posts.addAll(Arrays.asList(post.value()));
                posts.addAll(Arrays.asList(post.path()));
            }
            RequestMapping any = m.getAnnotation(RequestMapping.class);
            if (any != null) {
                posts.addAll(Arrays.asList(any.value()));
            }
            for (GetMapping g : List.of(m.getAnnotationsByType(GetMapping.class))) {
                assertThat(Arrays.asList(g.value())).noneMatch(p -> p.endsWith("/send"));
            }
            PutMapping put = m.getAnnotation(PutMapping.class);
            if (put != null) {
                assertThat(Arrays.asList(put.value())).noneMatch(p -> p.endsWith("/send"));
            }
            DeleteMapping del = m.getAnnotation(DeleteMapping.class);
            if (del != null) {
                assertThat(Arrays.asList(del.value())).noneMatch(p -> p.endsWith("/send"));
            }
        }

        assertThat(posts)
            .as("POST routes declared on FactoryQuoteController")
            .noneMatch(p -> p.endsWith("/send"))
            .contains("/factory-quotes/{factoryQuoteId}/contacted");
    }
}
