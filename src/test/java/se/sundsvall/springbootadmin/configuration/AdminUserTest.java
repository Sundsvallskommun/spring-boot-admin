package se.sundsvall.springbootadmin.configuration;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import se.sundsvall.springbootadmin.Application;

@SpringBootTest(classes = Application.class, webEnvironment = RANDOM_PORT)
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
