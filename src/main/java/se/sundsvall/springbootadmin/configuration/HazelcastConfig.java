package se.sundsvall.springbootadmin.configuration;

import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures the Hazelcast member used as the shared cluster store for Spring Boot Admin.
 * <p>
 * SBA's {@code AdminServerHazelcastAutoConfiguration} kicks in when a {@link com.hazelcast.core.HazelcastInstance}
 * bean is on the context, replacing the default in-memory event store with one backed by a Hazelcast IMap. Both
 * SBA replicas join the same cluster and share instance state, so status checks are coordinated and a rolling
 * restart preserves state on the surviving pod.
 * <p>
 * In Kubernetes, members discover each other via the headless service named by
 * {@code spring.hazelcast.kubernetes.service-name}. When that property is unset (local dev / tests), the cluster
 * runs as a single-member cluster on localhost.
 */
@Configuration
public class HazelcastConfig {

	private static final Logger LOGGER = LoggerFactory.getLogger(HazelcastConfig.class);

	@Bean
	public Config hazelcastClusterConfig(
		@Value("${spring.application.name}") final String applicationName,
		@Value("${spring.hazelcast.kubernetes.service-name:}") final String kubernetesServiceName,
		@Value("${spring.hazelcast.kubernetes.namespace:}") final String kubernetesNamespace) {

		final var config = new Config();
		config.setClusterName(applicationName);
		config.setProperty("hazelcast.shutdownhook.policy", "GRACEFUL");

		final var join = config.getNetworkConfig().getJoin();
		join.getMulticastConfig().setEnabled(false);

		if (!kubernetesServiceName.isBlank()) {
			LOGGER.info("Hazelcast: using Kubernetes member discovery via service '{}'", kubernetesServiceName);
			final var k8s = join.getKubernetesConfig().setEnabled(true)
				.setProperty("service-name", kubernetesServiceName);
			if (!kubernetesNamespace.isBlank()) {
				k8s.setProperty("namespace", kubernetesNamespace);
			}
		} else {
			LOGGER.info("Hazelcast: Kubernetes service name not set — running as single-member cluster (local mode)");
			enableLocalOnly(join);
		}

		return config;
	}

	private static void enableLocalOnly(final JoinConfig join) {
		join.getTcpIpConfig().setEnabled(false);
		join.getAwsConfig().setEnabled(false);
		join.getKubernetesConfig().setEnabled(false);
	}
}
