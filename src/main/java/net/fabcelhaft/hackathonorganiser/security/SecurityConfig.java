package net.fabcelhaft.hackathonorganiser.security;

import static org.springframework.security.config.Customizer.withDefaults;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Reactive security configuration (T014; research.md §2): OIDC login only, with
 * {@code ROLE_ORGANISER} required for the whole organiser area and plain authentication required
 * everywhere else.
 *
 * <p>{@code .oauth2Login(withDefaults())} auto-detects the {@link HackathonOidcUserService} bean:
 * Spring Security's reactive {@code OAuth2LoginSpec} looks up a
 * {@code ReactiveOAuth2UserService<OidcUserRequest, OidcUser>} bean from the application context
 * before falling back to a plain {@code OidcReactiveOAuth2UserService}, so no explicit wiring
 * call is needed here.
 *
 * <p>FR-001 (no local username/password credential store, ever): {@code .formLogin()} and
 * {@code .httpBasic()} — or any other local-credential mechanism — MUST NOT be added to this
 * chain.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .authorizeExchange(exchanges -> exchanges
                        // specs/001-spring-boot-infrastructure/contracts/health-endpoint.md:
                        // "No authentication" — predates this feature's login requirement.
                        .pathMatchers("/actuator/health").permitAll()
                        .pathMatchers("/organiser/**").hasRole("ORGANISER")
                        .anyExchange().authenticated())
                .oauth2Login(withDefaults())
                // No thymeleaf-extras-springsecurity dialect is on the classpath (Setup phase did
                // not add one), so organiser forms have no CSRF-token hidden field to submit.
                // Disabled here rather than leaving forms silently broken.
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .build();
    }
}
