package com.poparthur.mynotes.identity.http;

import com.poparthur.mynotes.identity.http.dto.*;
import com.poparthur.mynotes.identity.http.security.SessionSupport;
import com.poparthur.mynotes.identity.service.*;
import com.poparthur.mynotes.identity.service.result.SessionIdentity;
import com.poparthur.mynotes.shared.service.InputValidation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final RegistrationService registration;
    private final EmailVerificationService verification;
    private final PasswordRecoveryService recovery;
    private final CurrentUserService users;
    private final AuthenticationManager authentication;
    private final SessionSupport sessions;
    private final InputValidation validation;

    public AuthController(RegistrationService registration, EmailVerificationService verification,
                           PasswordRecoveryService recovery, CurrentUserService users,
                           AuthenticationManager authentication, SessionSupport sessions, InputValidation validation) {
        this.registration = registration; this.verification = verification; this.recovery = recovery;
        this.users = users; this.authentication = authentication; this.sessions = sessions; this.validation = validation;
    }

    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken token) { return new CsrfResponse(token.getHeaderName(), token.getToken()); }

    @PostMapping("/register")
    public ResponseEntity<MessageResponse> register(@Valid @RequestBody RegisterRequest request) {
        validation.email(request.email()); validation.newPassword(request.password(), "password");
        registration.register(request.email(), request.password());
        return ResponseEntity.accepted().body(MessageResponse.neutral());
    }

    @PostMapping("/verify-email")
    public ResponseEntity<Void> verify(@Valid @RequestBody TokenRequest request) {
        verification.verify(request.token());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<MessageResponse> resend(@Valid @RequestBody EmailRequest request) {
        validation.email(request.email());
        verification.resend(request.email());
        return ResponseEntity.accepted().body(MessageResponse.neutral());
    }

    @PostMapping("/login")
    public UserResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        var authenticated = authentication.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(body.email(), body.password()));
        sessions.establish(authenticated, request, response);
        return UserResponse.from(users.requireVerified(((SessionIdentity) authenticated.getPrincipal()).userId()));
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal SessionIdentity actor) {
        return UserResponse.from(users.requireVerified(actor.userId()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        sessions.logout(request, response);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<MessageResponse> forgot(@Valid @RequestBody EmailRequest request) {
        validation.email(request.email());
        recovery.requestReset(request.email());
        return ResponseEntity.accepted().body(MessageResponse.neutral());
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> reset(@Valid @RequestBody ResetPasswordRequest body,
                                      @AuthenticationPrincipal SessionIdentity actor,
                                      HttpServletRequest request, HttpServletResponse response) {
        var reset = recovery.reset(body.token(), body.newPassword());
        if (actor != null && actor.userId().equals(reset.userId())) {
            sessions.logout(request, response);
        } else {
            sessions.clearCsrf(request, response);
        }
        return ResponseEntity.noContent().build();
    }
}
