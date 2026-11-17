package th.co.glr.hr.mail;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/** Installs OverrideRedirectingMailer around whichever Mailer bean app.mail.provider selected (LogMailer /
 * SmtpMailer / ResendMailer), so app.mail.override-to containment applies regardless of transport and
 * regardless of which service calls it (see #782). */
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
