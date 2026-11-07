package com.poparthur.mynotes.identity.service.mail.resend;

import com.poparthur.mynotes.identity.service.exception.MailDeliveryFailureException;
import com.poparthur.mynotes.identity.service.mail.IdentityMail;
import com.poparthur.mynotes.identity.service.mail.IdentityMailSender;
import com.poparthur.mynotes.identity.service.mail.smtp.IdentityMailTemplate;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** HTTPS transport only; account state and token transactions belong to existing services. */
public class ResendIdentityMailSender implements IdentityMailSender {
    private final RestClient client;
    private final String from;
    private final IdentityMailTemplate template;

    public ResendIdentityMailSender(RestClient client, String from, IdentityMailTemplate template) {
        this.client = client;
        this.from = from;
        this.template = template;
    }

    @Override
    public void send(IdentityMail mail) {
        try {
            var rendered = template.render(mail);
            // Do not retry: an interrupted response may follow a successful delivery.
            client.post().uri("/emails").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("from", from, "to", List.of(mail.recipient()),
                            "subject", rendered.subject(), "text", rendered.text(), "html", rendered.html()))
                    .exchange((request, response) -> {
                        if (response.getStatusCode().value() != 200) {
                            throw new MailDeliveryFailureException();
                        }
                        return null;
                    });
        } catch (RuntimeException failure) {
            // Provider responses may contain addresses, tokens or credentials; discard the cause.
            throw new MailDeliveryFailureException();
        }
    }
}
