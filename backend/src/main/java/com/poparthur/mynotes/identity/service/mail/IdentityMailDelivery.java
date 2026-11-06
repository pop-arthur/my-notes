package com.poparthur.mynotes.identity.service.mail;

import com.poparthur.mynotes.identity.service.exception.MailDeliveryFailureException;
import com.poparthur.mynotes.identity.service.exception.MailDeliveryUnavailableException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class IdentityMailDelivery {
    private final ObjectProvider<IdentityMailSender> senders;

    public IdentityMailDelivery(ObjectProvider<IdentityMailSender> senders) {
        this.senders = senders;
    }

    /** Check availability before querying accounts so configuration does not reveal account existence. */
    public IdentityMailSender requireSender() {
        var sender = senders.getIfAvailable();
        if (sender == null) {
            throw new MailDeliveryUnavailableException();
        }
        return sender;
    }

    /** Invoke only after the transaction that issued the token has committed. */
    public void send(IdentityMailSender sender, IdentityMail message) {
        if (message == null) {
            return;
        }
        try {
            sender.send(message);
        } catch (RuntimeException failure) {
            // Adapter exceptions may contain the raw mail/token; do not retain their cause.
            throw new MailDeliveryFailureException();
        }
    }
}
