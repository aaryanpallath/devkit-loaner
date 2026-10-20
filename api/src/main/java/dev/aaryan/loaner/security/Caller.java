package dev.aaryan.loaner.security;

import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Who is calling. Ownership is keyed on the token's subject, which the identity provider never reassigns; the email
 * is kept for display and reminders only. Both come from the verified token, never from the request body.
 */
public record Caller(String subject, String email, boolean admin) {

    public static Caller of(JwtAuthenticationToken auth) {
        var jwt = auth.getToken();
        String email = jwt.getClaimAsString("email");
        if (jwt.getSubject() == null || email == null || email.isBlank()) {
            throw new IllegalStateException("token has no subject or email");
        }
        boolean admin = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_" + SecurityConfig.ADMIN));
        return new Caller(jwt.getSubject(), email.toLowerCase(), admin);
    }
}
