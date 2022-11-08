package se.sundsvall.springbootadmin.configuration;

import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.security.crypto.factory.PasswordEncoderFactories.createDelegatingPasswordEncoder;

import java.time.Duration;
import java.util.UUID;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import de.codecentric.boot.admin.server.config.AdminServerProperties;

/**
 * The purpose with this class is to configure the paths that should be public accessible.
 * When (if) this is no longer necessary, this class (and the AdminUser.class) can be removed.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

	private static final Duration REMEMBER_ME_DURATION = Duration.ofDays(14);

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http, AdminServerProperties adminServer) throws Exception {

		final var successHandler = new SavedRequestAwareAuthenticationSuccessHandler();
		successHandler.setTargetUrlParameter("redirectTo");
		successHandler.setDefaultTargetUrl(adminServer.path("/"));

		http.authorizeRequests(authorizeRequests -> authorizeRequests
			// Allow these paths for unauthorized users (i.e. public access)
			.antMatchers(GET, adminServer.path("/sba-settings.js")).permitAll()
			.antMatchers(GET, adminServer.path("/favicon.*")).permitAll()
			.antMatchers(GET, adminServer.path("/login")).permitAll()
			.antMatchers(GET, adminServer.path("/assets/**")).permitAll()
			.antMatchers(GET, adminServer.path("/actuator/info")).permitAll()
			.antMatchers(GET, adminServer.path("/actuator/health/**")).permitAll()
			.antMatchers(GET, adminServer.path("/wallboard")).permitAll()
			.antMatchers(GET, adminServer.path("/journal")).permitAll()
			.antMatchers(GET, adminServer.path("/applications")).permitAll()
			.antMatchers(GET, adminServer.path("/instances/events")).permitAll()
			.antMatchers(POST, adminServer.path("/instances")).permitAll()
			// All other requests should be protected.
			.anyRequest().authenticated())
			// Set up login page. Unauthorized requests will be redirected to the login-page.
			.formLogin(formLogin -> formLogin.loginPage(adminServer.path("/login")).successHandler(successHandler))
			.logout(logout -> logout.logoutUrl(adminServer.path("/logout")))
			// Disables CSRF-Protection for the endpoint the Spring Boot Admin Client uses to (de-)register.
			.csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
				.ignoringRequestMatchers(
					new AntPathRequestMatcher(adminServer.path("/instances"), HttpMethod.POST.toString()),
					new AntPathRequestMatcher(adminServer.path("/instances/*"), HttpMethod.DELETE.toString()),
					new AntPathRequestMatcher(adminServer.path("/actuator/**"))))
			.rememberMe(rememberMe -> rememberMe.key(UUID.randomUUID().toString()).tokenValiditySeconds((int) REMEMBER_ME_DURATION.toSeconds()));

		return http.build();
	}

	@Bean
	public UserDetailsService userDetailsService(AdminUser adminUser) {
		return new InMemoryUserDetailsManager(User.withUsername(adminUser.name())
			.password(adminUser.password())
			.roles("ADMIN")
			.passwordEncoder(createDelegatingPasswordEncoder()::encode)
			.build());
	}
}
