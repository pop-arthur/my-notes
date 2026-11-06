package com.poparthur.mynotes.identity.http.security;

import com.poparthur.mynotes.identity.service.result.SessionIdentity;
import java.util.Collection;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/** Temporary OIDC authentication input; replaced with SessionIdentity before the response is committed. */
final class LocalOidcUser implements OidcUser {
    private final OidcUser delegate;
    private final SessionIdentity identity;

    LocalOidcUser(OidcUser delegate, SessionIdentity identity) { this.delegate = delegate; this.identity = identity; }
    SessionIdentity identity() { return identity; }
    @Override public Map<String, Object> getClaims() { return delegate.getClaims(); }
    @Override public OidcUserInfo getUserInfo() { return delegate.getUserInfo(); }
    @Override public OidcIdToken getIdToken() { return delegate.getIdToken(); }
    @Override public Map<String, Object> getAttributes() { return delegate.getAttributes(); }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return delegate.getAuthorities(); }
    @Override public String getName() { return identity.getName(); }
    @Override public String toString() { return "LocalOidcUser[redacted]"; }
}
