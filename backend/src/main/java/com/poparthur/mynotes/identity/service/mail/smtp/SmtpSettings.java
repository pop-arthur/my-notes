package com.poparthur.mynotes.identity.service.mail.smtp;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.mail")
public record SmtpSettings(String host, int port, String username, String password, String from, TlsMode tlsMode) {
    public enum TlsMode { STARTTLS, TLS, NONE }

    public SmtpSettings {
        if (tlsMode == null) tlsMode = TlsMode.STARTTLS;
    }

    public int effectivePort() {
        return port == 0 ? switch (tlsMode) {
            case STARTTLS -> 587;
            case TLS -> 465;
            case NONE -> 1025;
        } : port;
    }

    @Override public String toString() { return "SmtpSettings[redacted]"; }
}
