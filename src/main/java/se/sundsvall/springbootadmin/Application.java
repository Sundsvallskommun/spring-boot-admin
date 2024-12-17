package se.sundsvall.springbootadmin;

import static org.springframework.boot.SpringApplication.run;

import de.codecentric.boot.admin.server.config.EnableAdminServer;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import se.sundsvall.dept44.ServiceApplication;
import se.sundsvall.dept44.configuration.OpenApiConfiguration;
import se.sundsvall.dept44.configuration.SecurityConfiguration;
import se.sundsvall.dept44.configuration.WebConfiguration;
import se.sundsvall.dept44.util.jacoco.ExcludeFromJacocoGeneratedCoverageReport;

@ServiceApplication(exclude = {
	WebConfiguration.class, OpenApiConfiguration.class, SecurityConfiguration.class
})
@EnableAdminServer
@EnableDiscoveryClient
@EnableScheduling
@ExcludeFromJacocoGeneratedCoverageReport
public class Application {
	public static void main(String... args) {
		run(Application.class, args);
	}
}
