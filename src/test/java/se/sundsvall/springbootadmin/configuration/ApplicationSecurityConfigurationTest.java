package se.sundsvall.springbootadmin.configuration;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import se.sundsvall.springbootadmin.Application;

@SpringBootTest(classes = Application.class, webEnvironment = RANDOM_PORT)
@ActiveProfiles("junit")
class ApplicationSecurityConfigurationTest {

	@Autowired
	private UserDetailsService userDetailsService;

	@Test
	void userDetailsService() {
		assertThat(userDetailsService.loadUserByUsername("test-username"))
			.isNotNull()
			.hasFieldOrPropertyWithValue("username", "test-username");
	}

	@Nested
	@SpringBootTest(properties = "spring.security.enabled=true")
	class SecurityEnabled {

		@Autowired
		private SecurityFilterChain securityFilterChain;

		@Test
		void securityFilterChainWhenEnabled() {
			assertThat(securityFilterChain).isNotNull();
		}
	}

	@Nested
	@SpringBootTest(properties = "spring.security.enabled=false")
	class SecurityDisabled {

		@Autowired
		private SecurityFilterChain securityFilterChain;

		@Test
		void securityFilterChainWhenDisabled() {
			assertThat(securityFilterChain).isNotNull();
		}
	}
}
