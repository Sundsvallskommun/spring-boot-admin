package se.sundsvall.springbootadmin.configuration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HazelcastConfigTest {

	private final HazelcastConfig configFactory = new HazelcastConfig();

	@Test
	void localModeDisablesAllJoinMechanismsWhenServiceNameIsBlank() {
		final var config = configFactory.hazelcastClusterConfig("spring-boot-admin", "", "");

		assertThat(config.getClusterName()).isEqualTo("spring-boot-admin");
		final var join = config.getNetworkConfig().getJoin();
		assertThat(join.getMulticastConfig().isEnabled()).isFalse();
		assertThat(join.getKubernetesConfig().isEnabled()).isFalse();
		assertThat(join.getTcpIpConfig().isEnabled()).isFalse();
		assertThat(join.getAwsConfig().isEnabled()).isFalse();
	}

	@Test
	void kubernetesModeEnablesK8sDiscoveryWhenServiceNameIsSet() {
		final var config = configFactory.hazelcastClusterConfig("spring-boot-admin", "sba-headless", "monitoring");

		final var k8s = config.getNetworkConfig().getJoin().getKubernetesConfig();
		assertThat(k8s.isEnabled()).isTrue();
		assertThat(k8s.getProperty("service-name")).isEqualTo("sba-headless");
		assertThat(k8s.getProperty("namespace")).isEqualTo("monitoring");
		assertThat(config.getNetworkConfig().getJoin().getMulticastConfig().isEnabled()).isFalse();
	}

	@Test
	void kubernetesModeOmitsNamespaceWhenBlank() {
		final var config = configFactory.hazelcastClusterConfig("spring-boot-admin", "sba-headless", "");

		final var k8s = config.getNetworkConfig().getJoin().getKubernetesConfig();
		assertThat(k8s.isEnabled()).isTrue();
		assertThat(k8s.getProperty("service-name")).isEqualTo("sba-headless");
		assertThat(k8s.getProperty("namespace")).isNull();
	}
}
