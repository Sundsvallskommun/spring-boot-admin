package se.sundsvall.springbootadmin.configuration;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("junit")
class AdminUserTest {

	@Autowired
	private AdminUser adminUser;

	@Test
	void testProperties() {
		assertThat(adminUser.name()).isEqualTo("test-username");
		assertThat(adminUser.password()).isEqualTo("test-password");
	}
}
