package se.sundsvall.springbootadmin.configuration;

import static java.util.UUID.randomUUID;
import static org.springframework.security.crypto.factory.PasswordEncoderFactories.createDelegatingPasswordEncoder;

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
import se.sundsvall.dept44.util.jacoco.ExcludeFromJacocoGeneratedCoverageReport;

@Configuration
@EnableWebSecurity
@ExcludeFromJacocoGeneratedCoverageReport
public class ApplicationSecurityConfiguration {

	private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ApplicationSecurityConfiguration.class);
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
			.csrf(AbstractHttpConfigurer::disable)
			.headers(headers -> headers.frameOptions(FrameOptionsConfig::disable));
		return http.build();
	}

	/**
	 * The "Security enabled" bean.
	 *
	 * When this bean is activated then security is enabled.
	 * This bean is activated by setting the application property "spring.security.enabled" to true (or omitting it).
	 * 
	 * @param  http
	 * @return           SecurityFilterChain
	 * @throws Exception
	 */
	@Bean
	@ConditionalOnProperty(name = "spring.security.enabled", havingValue = "true", matchIfMissing = true)
	SecurityFilterChain securityEnabled(HttpSecurity http, AdminServerProperties adminServer) throws Exception {
		LOGGER.info("Configuring security with adminServer.path('/wallboard') = {}", adminServer.path("/wallboard"));
		LOGGER.info("Configuring security with adminServer.path('/login') = {}", adminServer.path("/login"));

		final var loginSuccessHandler = new SavedRequestAwareAuthenticationSuccessHandler();
		loginSuccessHandler.setTargetUrlParameter("redirectTo");
		loginSuccessHandler.setDefaultTargetUrl("/");

		http
			.authorizeHttpRequests(auth -> auth
				// Public endpoints (wallboard and its dependencies)
				.requestMatchers(
					adminServer.path("/assets/**"),
					adminServer.path("/actuator"),
					adminServer.path("/actuator/**"),
					adminServer.path("/wallboard"),
					adminServer.path("/wallboard/**"),
					adminServer.path("/applications"),
					adminServer.path("/applications/**"),
					adminServer.path("/instances"),
					adminServer.path("/instances/**"),
					adminServer.path("/sba-settings.js"),
					adminServer.path("/login"),
					adminServer.path("/logout")).permitAll()
				// All other requests require authentication
				.anyRequest().authenticated())
			.formLogin(form -> form
				.loginPage(adminServer.path("/login"))
				.successHandler(loginSuccessHandler))
			.headers(headers -> headers.frameOptions(FrameOptionsConfig::disable))
			.csrf(AbstractHttpConfigurer::disable)
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
