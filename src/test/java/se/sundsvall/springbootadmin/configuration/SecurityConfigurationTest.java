package se.sundsvall.springbootadmin.configuration;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;

import se.sundsvall.springbootadmin.Application;

@SpringBootTest(classes = Application.class)
@ActiveProfiles("junit")
class SecurityConfigurationTest {

	@Autowired
	private SecurityFilterChain securityFilterChain;

	@Autowired
	private UserDetailsService userDetailsService;

	@Test
	void securityFilterChain() {
		assertThat(securityFilterChain).isNotNull();
	}

	@Test
	void userDetailsService() {
		assertThat(userDetailsService.loadUserByUsername("username"))
			.isNotNull()
			.hasFieldOrPropertyWithValue("username", "username");
	}
}
