package com.poparthur.mynotes.identity.service.mail.smtp;

import com.poparthur.mynotes.identity.service.exception.MailDeliveryFailureException;
import com.poparthur.mynotes.identity.service.mail.IdentityMail;
import com.poparthur.mynotes.identity.service.mail.IdentityMailSender;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

public class SmtpIdentityMailSender implements IdentityMailSender {
    private final JavaMailSender transport;
    private final String from;
    private final IdentityMailTemplate template;

    public SmtpIdentityMailSender(JavaMailSender transport, String from, IdentityMailTemplate template) {
        this.transport = transport; this.from = from; this.template = template;
    }

    @Override
    public void send(IdentityMail mail) {
        try {
            var rendered = template.render(mail);
            var message = transport.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(address(from)); helper.setTo(address(mail.recipient()));
            helper.setSubject(rendered.subject()); helper.setText(rendered.text(), rendered.html());
            transport.send(message);
        } catch (MessagingException | RuntimeException failure) {
            // SMTP/MIME exceptions can contain addresses or message content. Never expose their cause.
            throw new MailDeliveryFailureException();
        }
    }

    static InternetAddress address(String value) throws MessagingException {
        if (value == null || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new MessagingException("Invalid mailbox");
        }
        var address = new InternetAddress(value, true);
        address.validate();
        if (address.getPersonal() != null || !address.getAddress().equals(value)) {
            throw new MessagingException("A single plain mailbox is required");
        }
        return address;
    }
}
