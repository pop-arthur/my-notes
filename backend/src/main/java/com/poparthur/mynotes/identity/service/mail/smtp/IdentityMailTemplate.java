package com.poparthur.mynotes.identity.service.mail.smtp;

import com.poparthur.mynotes.identity.service.mail.IdentityMail;
import com.poparthur.mynotes.shared.service.PublicOrigin;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.web.util.HtmlUtils;

/** Generates both MIME alternatives from one trusted, fixed-path account link. */
public class IdentityMailTemplate {
    private final PublicOrigin origin;

    public IdentityMailTemplate(PublicOrigin origin) { this.origin = origin; }

    public Rendered render(IdentityMail message) {
        String path;
        String subject;
        String action;
        switch (message.purpose()) {
            case EMAIL_VERIFICATION -> {
                path = "/verify-email"; subject = "Подтвердите email — Список задач"; action = "Подтвердить email";
            }
            case PASSWORD_RESET -> {
                path = "/reset-password"; subject = "Сброс пароля — Список задач"; action = "Задать новый пароль";
            }
            default -> throw new IllegalArgumentException("Unsupported mail purpose");
        }
        String link = origin.require().resolve(path).toASCIIString() + "?token="
                + URLEncoder.encode(message.token(), StandardCharsets.UTF_8);
        String text = action + ":\n" + link + "\n\nОткройте страницу и подтвердите действие. "
                + "Если ссылка истекла, запросите новую. Если вы не отправляли запрос, проигнорируйте письмо.";
        String html = "<!doctype html><html lang=\"ru\"><body><h1>" + action + "</h1><p>"
                + "Откройте страницу и подтвердите действие:</p><p><a href=\""
                + HtmlUtils.htmlEscape(link, "UTF-8") + "\">" + action + "</a></p>"
                + "<p>Если ссылка истекла, запросите новую. Если вы не отправляли запрос, проигнорируйте письмо.</p></body></html>";
        return new Rendered(subject, text, html);
    }

    public record Rendered(String subject, String text, String html) {
        @Override public String toString() { return "RenderedMail[redacted]"; }
    }
}
