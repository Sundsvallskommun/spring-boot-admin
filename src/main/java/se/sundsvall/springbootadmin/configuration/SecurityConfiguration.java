package se.sundsvall.springbootadmin.configuration;

import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

import java.time.Duration;
import java.util.UUID;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import de.codecentric.boot.admin.server.config.AdminServerProperties;

@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

	private static final Duration REMEMBER_ME_DURATION = Duration.ofDays(14);

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http, AdminServerProperties adminServer, AccessDeniedHandler accessDeniedHandler) throws Exception {

		SavedRequestAwareAuthenticationSuccessHandler successHandler2 = new SavedRequestAwareAuthenticationSuccessHandler();
		successHandler2.setTargetUrlParameter("redirectTo");
		successHandler2.setDefaultTargetUrl(adminServer.path("/"));

		http.authorizeRequests(authorizeRequests -> authorizeRequests
			.antMatchers(adminServer.path("/login")).permitAll()
			.antMatchers(adminServer.path("/assets/**")).permitAll()
			.antMatchers(adminServer.path("/actuator/info")).permitAll()
			.antMatchers(adminServer.path("/actuator/health/**")).permitAll()
			.antMatchers(adminServer.path("/wallboard")).permitAll()
			.antMatchers(adminServer.path("/journal")).permitAll()
			.antMatchers(GET, adminServer.path("/applications/**")).permitAll()
			.antMatchers(POST, adminServer.path("/instances")).permitAll()
			// .antMatchers(this.adminServer.path("/actuator/**")).permitAll()
			.antMatchers(adminServer.path("/instances/**/details")).permitAll()
			.antMatchers(adminServer.path("/instances/**/actuator/info")).permitAll()
			.antMatchers(adminServer.path("/instances/**/actuator/health")).permitAll()
			.antMatchers(adminServer.path("/instances/**/actuator/metrics/**")).permitAll()
			.antMatchers(adminServer.path("/instances/events")).permitAll()
			.anyRequest().authenticated())
			.formLogin(formLogin -> formLogin.loginPage(adminServer.path("/login")).defaultSuccessUrl(adminServer.path("/wallboard")))
			.logout(logout -> logout.logoutUrl(adminServer.path("/logout")))
			.exceptionHandling().accessDeniedHandler(accessDeniedHandler).and()
			.csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
				.ignoringRequestMatchers(
					new AntPathRequestMatcher(adminServer.path("/instances"),
						HttpMethod.POST.toString()),
					new AntPathRequestMatcher(adminServer.path("/instances/*"),
						HttpMethod.DELETE.toString()),
					new AntPathRequestMatcher(adminServer.path("/actuator/**"))))
			.rememberMe(rememberMe -> rememberMe.key(UUID.randomUUID().toString()).tokenValiditySeconds((int) REMEMBER_ME_DURATION.toSeconds()));

		return http.build();
	}

	@Bean
	public AccessDeniedHandler accessDeniedHandler() {
		return new CustomAccessDeniedHandler();
	}

	@Bean
	public UserDetailsService userDetailsService(AdminUser adminUser) {

		final var passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();

		final var admin = User.withUsername(adminUser.name())
			.password(adminUser.password())
			.roles("ADMIN")
			.passwordEncoder(passwordEncoder::encode)
			.build();

		return new InMemoryUserDetailsManager(admin);
	}
}
