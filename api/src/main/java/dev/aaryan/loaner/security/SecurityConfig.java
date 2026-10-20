package dev.aaryan.loaner.security;

import java.util.Collection;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 * The JWT verification key comes from configuration: the IdP's JWKS URL on AWS, a local public key in development.
 */
@Configuration
public class SecurityConfig {

    public static final String ADMIN = "ADMIN";
    public static final String REMINDER = "REMINDER_SERVICE";

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

    static JwtAuthenticationConverter roles() {
        JwtAuthenticationConverter c = new JwtAuthenticationConverter();
        c.setJwtGrantedAuthoritiesConverter(SecurityConfig::authorities);
        c.setPrincipalClaimName("email");
        return c;
    }

    static Collection<GrantedAuthority> authorities(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles == null ? List.of()
                : roles.stream().map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r)).toList();
    }
}
