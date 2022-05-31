package se.sundsvall.springbootadmin;

import de.codecentric.boot.admin.server.config.EnableAdminServer;
import org.springframework.boot.SpringApplication;

import se.sundsvall.dept44.ServiceApplication;
import se.sundsvall.dept44.configuration.OpenApiConfiguration;
import se.sundsvall.dept44.configuration.WebConfiguration;


@ServiceApplication(exclude = {WebConfiguration.class, OpenApiConfiguration.class})
@EnableAdminServer
public class Application {

	public static void main(String[] args) {
		SpringApplication.run(Application.class, args);
	}

}
