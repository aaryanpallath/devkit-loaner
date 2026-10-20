package dev.aaryan.loaner.security;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Every API call needs a signed JWT from the company identity provider (company SSO). Roles come from the token's
 * "roles" claim: ADMIN for the IT desk, REMINDER_SERVICE for the overdue-reminder Lambda; anyone else is a borrower.
 * Tokens must be signed by the configured key, issued by the configured issuer, and addressed to this API's
 * audience (see jwtDecoder). A token that carries an email must also mark it
 * verified (email_verified = true); service tokens without an email are allowed, but only roles grant them access.
 */
@Configuration
public class SecurityConfig {

    public static final String ADMIN = "ADMIN";
    public static final String REMINDER = "REMINDER_SERVICE";

    /**
     * One decoder for every environment: keys from the IdP's JWKS URL (AWS) or a PEM public key (development and
     * tests); the expected issuer and audience are always required. Validates signature, expiry, issuer and audience.
     */
    @Bean
    JwtDecoder jwtDecoder(@Value("${loaner.auth.jwks-uri:}") String jwksUri,
                          @Value("${loaner.auth.public-key:}") Resource publicKey,
                          @Value("${loaner.auth.issuer}") String issuer,
                          @Value("${loaner.auth.audience}") String audience) throws Exception {
        NimbusJwtDecoder decoder;
        if (!jwksUri.isBlank()) {
            decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
        } else if (publicKey != null && publicKey.exists()) {
            decoder = NimbusJwtDecoder.withPublicKey(readPublicKey(publicKey)).build();
        } else {
            throw new IllegalStateException("set loaner.auth.jwks-uri or loaner.auth.public-key");
        }
        OAuth2TokenValidator<Jwt> audienceCheck = new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                aud -> aud != null && aud.contains(audience));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), audienceCheck));
        return decoder;
    }

    static RSAPublicKey readPublicKey(Resource pem) throws Exception {
        String b64 = new String(pem.getContentAsByteArray()).replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        return (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64)));
    }

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        http
            // bearer tokens only, no cookies or sessions, so there is no CSRF surface
            .csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/devices").hasRole(ADMIN)
                .requestMatchers(HttpMethod.POST, "/api/devices/*/reservations/*/checkout",
                        "/api/devices/*/reservations/*/return").hasRole(ADMIN)
                .requestMatchers("/api/reservations/overdue").hasAnyRole(ADMIN, REMINDER)
                .anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(roles())));
        return http.build();
    }

    static Converter<Jwt, AbstractAuthenticationToken> roles() {
        JwtAuthenticationConverter c = new JwtAuthenticationConverter();
        c.setJwtGrantedAuthoritiesConverter(SecurityConfig::authorities);
        return jwt -> {
            if (jwt.hasClaim("email") && !Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))) {
                throw new OAuth2AuthenticationException(new OAuth2Error("invalid_token", "email is not verified", null));
            }
            return c.convert(jwt);
        };
    }

    static Collection<GrantedAuthority> authorities(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles == null ? List.of()
                : roles.stream().map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r)).toList();
    }
}
