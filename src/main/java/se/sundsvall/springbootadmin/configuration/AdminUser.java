package se.sundsvall.springbootadmin.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("spring.security.user")
public record AdminUser(String name, String password) {
}
