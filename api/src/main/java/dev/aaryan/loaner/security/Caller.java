package dev.aaryan.loaner.security;

import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Who is calling: their email (from the token, never from the request body) and whether they are IT desk staff. */
public record Caller(String email, boolean admin) {

    public static Caller of(JwtAuthenticationToken auth) {
        String email = auth.getToken().getClaimAsString("email");
        if (email == null || email.isBlank()) throw new IllegalStateException("token has no email claim");
        boolean admin = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + SecurityConfig.ADMIN));
        return new Caller(email.toLowerCase(), admin);
    }
}
