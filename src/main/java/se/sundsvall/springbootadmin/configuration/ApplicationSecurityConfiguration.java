package se.sundsvall.springbootadmin.configuration;

import static jakarta.servlet.DispatcherType.ASYNC;
import static java.util.UUID.randomUUID;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.security.crypto.factory.PasswordEncoderFactories.createDelegatingPasswordEncoder;
import static org.springframework.security.web.util.matcher.AntPathRequestMatcher.antMatcher;

import de.codecentric.boot.admin.server.config.AdminServerProperties;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;

import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer.FrameOptionsConfig;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

/**
 * The purpose with this class is to configure access to the paths used in SBA.
 */
@Configuration
@EnableWebSecurity
public class ApplicationSecurityConfiguration {

	private static final Duration REMEMBER_ME_DURATION = Duration.ofDays(14);

	/**
	 * The "Security disabled" bean.
	 * 
	 * When this bean is activated then security is disabled (no authentication required).
	 * This bean is activated by setting the application property "spring.security.enabled" to false.
	 * 
	 * @param  http
	 * @return           SecurityFilterChain
	 * @throws Exception
	 */
	@Bean
	@ConditionalOnProperty(name = "spring.security.enabled", havingValue = "false", matchIfMissing = false)
	SecurityFilterChain filterChainSecurityDisbaled(HttpSecurity http) throws Exception {

		http
			// Disable Configurer
			.csrf(AbstractHttpConfigurer::disable)

			// Remove "X-Frame-Options"-header (prevents Xibo from working)
			.headers(headers -> headers
				.frameOptions(FrameOptionsConfig::disable));

		return http.build();
	}

	/**
	 * The "Security enabled" bean.
	 * 
	 * When this bean is activated then security is enabled.
	 * This bean is activated by setting the application property "spring.security.enabled" to false.
	 * 
	 * @param  http
	 * @return           SecurityFilterChain
	 * @throws Exception
	 */
	@Bean
	@ConditionalOnProperty(name = "spring.security.enabled", havingValue = "true", matchIfMissing = true)
	SecurityFilterChain filterChainSecurityEnabled(HttpSecurity http, AdminServerProperties adminServer, UserDetailsService userDetailsService) throws Exception {

		final var loginSuccessHandler = new SavedRequestAwareAuthenticationSuccessHandler();
		loginSuccessHandler.setTargetUrlParameter("redirectTo");
		loginSuccessHandler.setDefaultTargetUrl("/");

		http.authorizeHttpRequests(authorizeRequests -> authorizeRequests
			.requestMatchers(
				// Grants public access to all static assets and the login page.
				antMatcher(GET, adminServer.path("/assets/**")),
				antMatcher(GET, adminServer.path("/actuator/info")),
				antMatcher(GET, adminServer.path("/actuator/health")),

				antMatcher(adminServer.path("/wallboard")),
				antMatcher(adminServer.path("/sba-settings.js")),
				antMatcher(adminServer.path("/login")),
				antMatcher(adminServer.path("/logout")),
				// Grants public access to the endpoint the Spring Boot Admin Client uses to (de-)register.
				antMatcher(adminServer.path("/instances")))
			.permitAll()

			// https://github.com/spring-projects/spring-security/issues/11027
			.dispatcherTypeMatchers(ASYNC).permitAll()

			// All other requests should be protected.
			.anyRequest().authenticated())

			// Set up login form.
			.formLogin(formLogin -> formLogin
				.loginPage(adminServer.path("/login"))
				.successHandler(loginSuccessHandler))

			// Remove "X-Frame-Options"-header (prevents Xibo from working)
			.headers(headers -> headers
				.frameOptions(FrameOptionsConfig::disable))

			.csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
				.ignoringRequestMatchers(

					// Disables CSRF-Protection for the endpoint the Spring Boot Admin Client uses to (de-)register.
					antMatcher(POST, adminServer.path("/instances")),
					antMatcher(DELETE, adminServer.path("/instances/*")),
					antMatcher(DELETE, adminServer.path("/instances/**")),
					antMatcher(adminServer.path("/actuator/**")),

					// Disables CSRF-Protection for the logout-endpoint.
					antMatcher(adminServer.path("/logout")),

					// Disables CSRF-Protection for the endpoint the UI uses to deregister applications.
					antMatcher(adminServer.path("/applications/**"))))

			.rememberMe(rememberMe -> rememberMe
				.alwaysRemember(true)
				.key(randomUUID().toString())
				.tokenValiditySeconds((int) REMEMBER_ME_DURATION.toSeconds()));

		return http.build();
	}

	@Bean
	UserDetailsService userDetailsService(AdminUser adminUser) {
		return new InMemoryUserDetailsManager(User.withUsername(adminUser.name())
			.password(adminUser.password())
			.roles("ADMIN")
			.passwordEncoder(createDelegatingPasswordEncoder()::encode)
			.build());
	}
}
