package se.sundsvall.springbootadmin;

import static org.springframework.boot.SpringApplication.run;

import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

import de.codecentric.boot.admin.server.config.EnableAdminServer;
import se.sundsvall.dept44.ServiceApplication;
import se.sundsvall.dept44.configuration.OpenApiConfiguration;
import se.sundsvall.dept44.configuration.SecurityConfiguration;
import se.sundsvall.dept44.configuration.WebConfiguration;
import se.sundsvall.dept44.util.jacoco.ExcludeFromJacocoGeneratedReport;

@ServiceApplication(exclude = { WebConfiguration.class, OpenApiConfiguration.class, SecurityConfiguration.class })
@EnableAdminServer
@EnableDiscoveryClient
@EnableScheduling
@ExcludeFromJacocoGeneratedReport
public class Application {
	public static void main(String... args) {
		run(Application.class, args);
	}
}
