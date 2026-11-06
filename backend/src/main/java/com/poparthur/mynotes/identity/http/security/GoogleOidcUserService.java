package com.poparthur.mynotes.identity.http.security;

import com.poparthur.mynotes.identity.service.GoogleIdentityService;
import com.poparthur.mynotes.identity.service.VerifiedGoogleIdentity;
import com.poparthur.mynotes.identity.service.exception.GoogleAuthenticationException;
import com.poparthur.mynotes.identity.service.result.SessionIdentity;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

@Component
public class GoogleOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {
    private final OidcUserService delegate = new OidcUserService();
    private final GoogleIdentityService identities;

    public GoogleOidcUserService(GoogleIdentityService identities) { this.identities = identities; }

    @Override
    public OidcUser loadUser(OidcUserRequest request) {
        // Spring's authentication provider verifies signature, issuer, audience, timestamps and nonce first.
        var user = delegate.loadUser(request);
        var token = request.getIdToken();
        try {
            var result = identities.authenticate(new VerifiedGoogleIdentity(token.getIssuer().toString(), token.getSubject(),
                    token.getEmail(), Boolean.TRUE.equals(token.getEmailVerified())));
            return new LocalOidcUser(user, new SessionIdentity(result.userId(), result.authVersion()));
        } catch (GoogleAuthenticationException failure) {
            throw new OAuth2AuthenticationException(new OAuth2Error("oauth_failed"));
        }
    }
}
