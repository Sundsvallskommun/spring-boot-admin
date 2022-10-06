package se.sundsvall.springbootadmin.configuration;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
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
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import de.codecentric.boot.admin.server.config.AdminServerProperties;

@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

	@Autowired
	private AdminServerProperties adminServer;

	@Autowired
	private AdminUser adminUser;

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

		SavedRequestAwareAuthenticationSuccessHandler successHandler = new SavedRequestAwareAuthenticationSuccessHandler();
		successHandler.setTargetUrlParameter("redirectTo");
		successHandler.setDefaultTargetUrl(this.adminServer.getContextPath() + "/wallboard");

		http.authorizeRequests(authorizeRequests -> authorizeRequests
			.antMatchers(this.adminServer.path("/assets/**")).permitAll()
			.antMatchers(this.adminServer.path("/actuator/info")).permitAll()
			.antMatchers(this.adminServer.path("/actuator/health")).permitAll()
			.antMatchers(this.adminServer.path("/wallboard")).permitAll()
			.antMatchers(this.adminServer.path("/applications/**")).permitAll()
			.antMatchers(this.adminServer.path("/journal")).permitAll()
			.antMatchers(this.adminServer.path("/instances")).permitAll()
			.antMatchers(this.adminServer.path("/actuator/**")).permitAll()
			.antMatchers(this.adminServer.path("/instances/*/details")).permitAll()
			.antMatchers(this.adminServer.path("/login")).permitAll().anyRequest().authenticated())
			.formLogin(formLogin -> formLogin
				.loginPage(this.adminServer.path("/login"))
				.successHandler(successHandler)
				.and())
			.logout(logout -> logout.logoutUrl(this.adminServer.path("/logout")))
			.csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
				.ignoringRequestMatchers(
					new AntPathRequestMatcher(this.adminServer.path("/instances"),
						HttpMethod.POST.toString()),
					new AntPathRequestMatcher(this.adminServer.path("/instances/*"),
						HttpMethod.DELETE.toString()),
					new AntPathRequestMatcher(this.adminServer.path("/actuator/**"))))
			.rememberMe(rememberMe -> rememberMe.key(UUID.randomUUID().toString()).tokenValiditySeconds(1209600));

		return http.build();
	}

	@Bean
	public UserDetailsService userDetailsService() {

		final var passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();

		final var admin = User.withUsername(adminUser.name())
			.password(adminUser.password())
			.roles("ADMIN")
			.passwordEncoder(passwordEncoder::encode)
			.build();

		return new InMemoryUserDetailsManager(admin);
	}
}
