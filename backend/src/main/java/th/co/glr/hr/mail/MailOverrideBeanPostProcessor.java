package th.co.glr.hr.mail;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/**
 * Installs {@link OverrideRedirectingMailer} around whichever {@link Mailer} bean
 * {@code app.mail.provider} selected ({@link LogMailer} / {@link SmtpMailer} / {@link ResendMailer}),
 * so {@code app.mail.override-to} containment applies regardless of transport and regardless of which
 * service calls it (see #782).
 *
 * <p>A {@link BeanPostProcessor} rather than a second {@code @Component implements Mailer} bean, on
 * purpose. Exactly one {@code Mailer}-typed bean definition is ever active at a time - the transport
 * classes stay {@code @ConditionalOnProperty}-gated exactly as before, this class does not touch
 * that - so every existing {@code @Autowired Mailer} / constructor-injected {@code Mailer} keeps
 * resolving completely unambiguously. A second {@code @Component} implementing {@code Mailer} would
 * make that injection ambiguous everywhere (two beans of the same type) and force either
 * {@code @Primary} plus a self-injection workaround, or a qualifier at every call site - both far more
 * invasive than swapping the bean *instance* post-construction, which is all a
 * {@link BeanPostProcessor} needs to do here. This is the same category of mechanism Spring itself
 * uses to apply {@code @Async}/{@code @Transactional} proxying to arbitrary beans, not a novel
 * pattern for this codebase.
 */
@Component
public class MailOverrideBeanPostProcessor implements BeanPostProcessor {
    private final String overrideTo;

    public MailOverrideBeanPostProcessor(@Value("${app.mail.override-to:}") String overrideTo) {
        this.overrideTo = overrideTo == null ? "" : overrideTo.trim();
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (overrideTo.isBlank() || !(bean instanceof Mailer mailer)) {
            return bean;
        }
        return new OverrideRedirectingMailer(mailer, overrideTo);
    }
}
