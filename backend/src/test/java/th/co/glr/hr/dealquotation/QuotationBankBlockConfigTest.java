package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

/** Pins the bank block's path from application.yml to DealQuotationService — the two places review found
 * nothing guarded (PR #929, finding F3). Every English quotation would have silently fallen back to the
 * proforma line — or printed a wrong account a customer then pays. */
class QuotationBankBlockConfigTest {

    /** The owner's own text, verbatim from QN6900902-6 and QN6900933 (the two agree). */
    private static final List<String> OWNERS_BLOCK = List.of(
        "Please arrange payment to the following bank account. Bank Name : Kasikorn Bank Public Company Limited",
        "Beneficiary name : G.L.& R. Taps and Tiles Co., Ltd. Beneficiary account number : 003-92-1222-6 Saving Account",
        "SWIFT code : KASITHBK");

    private static final List<String> KEYS = List.of(
        "app.quotation.bank-block-line1",
        "app.quotation.bank-block-line2",
        "app.quotation.bank-block-line3");

    @Test
    void applicationYml_resolvesTheOwnersBankBlockVerbatim() throws Exception {
        MutablePropertySources sources = new MutablePropertySources();
        new YamlPropertySourceLoader()
            .load("application.yml", new ClassPathResource("application.yml"))
            .forEach(sources::addLast);
        // Resolved against the FILE ONLY — no system environment in these sources — so an APP_QUOTATION_BANK_LINE* set
        // on the CI runner cannot mask a wrong default. The `${APP_…:default}` placeholder falls through to the
        // default, which is the committed text.
        PropertySourcesPropertyResolver resolver = new PropertySourcesPropertyResolver(sources);
        List<String> resolved = new ArrayList<>();
        for (String key : KEYS) {
            resolved.add(resolver.getProperty(key));
        }
        // Character for character. `&` in YAML, the `:` separators inside a `${VAR:default}` placeholder (Spring
        // splits on the FIRST colon), and the surrounding quotes are each a way to arrive at a subtly different string
        // — this is where one of them would show.
        assertThat(resolved).containsExactlyElementsOf(OWNERS_BLOCK);
    }

    @Test
    void theServiceInjectsExactlyTheKeysTheYamlDefines() {
        Constructor<?>[] constructors = DealQuotationService.class.getConstructors();
        // One public constructor is what lets Spring inject through it without an @Autowired marker.
        assertThat(constructors).hasSize(1);
        List<String> injected = new ArrayList<>();
        for (Annotation[] parameter : constructors[0].getParameterAnnotations()) {
            for (Annotation annotation : parameter) {
                if (annotation instanceof Value value && value.value().contains("bank-block")) {
                    injected.add(value.value());
                }
            }
        }
        // A typo here resolves to the EMPTY default at runtime, silently, and the document falls back to the proforma
        // line — so the keys are pinned to the ones the YAML test above reads.
        assertThat(injected).containsExactly(
            "${" + KEYS.get(0) + ":}",
            "${" + KEYS.get(1) + ":}",
            "${" + KEYS.get(2) + ":}");
    }
}
