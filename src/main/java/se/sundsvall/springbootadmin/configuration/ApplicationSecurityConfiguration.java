package se.sundsvall.springbootadmin.configuration;

import de.codecentric.boot.admin.server.config.AdminServerProperties;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer.FrameOptionsConfig;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import se.sundsvall.dept44.util.jacoco.ExcludeFromJacocoGeneratedCoverageReport;

import static java.util.UUID.randomUUID;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.security.crypto.factory.PasswordEncoderFactories.createDelegatingPasswordEncoder;

@Configuration
@EnableWebSecurity
@ExcludeFromJacocoGeneratedCoverageReport
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
	SecurityFilterChain securityDisabled(HttpSecurity http) throws Exception {
		http
			.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
			.csrf(csrf -> csrf.disable())
			.headers(headers -> headers.frameOptions(FrameOptionsConfig::disable));
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
	SecurityFilterChain securityEnabled(HttpSecurity http, AdminServerProperties adminServer) throws Exception {

		var loginSuccessHandler = new SavedRequestAwareAuthenticationSuccessHandler();
		loginSuccessHandler.setTargetUrlParameter("redirectTo");
		loginSuccessHandler.setDefaultTargetUrl("/");

		http
			.authorizeHttpRequests(auth -> auth
				// Public GET endpoints
				.requestMatchers(GET,
					adminServer.path("/assets/**"),
					adminServer.path("/actuator/**"),
					adminServer.path("/actuator/health"),
					adminServer.path("/wallboard"),
					adminServer.path("/sba-settings.js"),
					adminServer.path("/login")).permitAll()
				// Public POST/DELETE endpoints
				.requestMatchers(POST, adminServer.path("/logout"), adminServer.path("/instances")).permitAll()
				.requestMatchers(DELETE, adminServer.path("/instances/**")).permitAll()
				// All other requests require authentication
				.anyRequest().authenticated())
			.formLogin(form -> form
				.loginPage(adminServer.path("/login"))
				.successHandler(loginSuccessHandler))
			.headers(headers -> headers.frameOptions(FrameOptionsConfig::disable))
			.csrf(csrf -> csrf
				.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
				// CSRF exemption for SBA endpoints
				.ignoringRequestMatchers(
					request -> POST.matches(request.getMethod()) && request.getServletPath().equals(adminServer.path("/instances")),
					request -> DELETE.matches(request.getMethod()) && request.getServletPath().startsWith(adminServer.path("/instances")),
					request -> GET.matches(request.getMethod()) && request.getServletPath().startsWith(adminServer.path("/actuator")),
					request -> POST.matches(request.getMethod()) && request.getServletPath().equals(adminServer.path("/logout")),
					request -> DELETE.matches(request.getMethod()) && request.getServletPath().startsWith(adminServer.path("/applications"))))
			.rememberMe(rm -> rm
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
