package se.sundsvall.springbootadmin.configuration;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

@SpringBootTest
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
