package io.daqiqa.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.daqiqa.server.DevelopmentKeyGuard.DevelopmentKeyConfiguredException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ListAssert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.io.support.SpringFactoriesLoader.FailureHandler;
import org.springframework.mock.env.MockEnvironment;

/** The published development signing key is accepted only by an instance running the dev profile. */
@ExtendWith(OutputCaptureExtension.class)
class DevelopmentKeyGuardTest {

    private static final String DEV_KEY = DevelopmentKeyGuard.DEVELOPMENT_KEY_BASE64;
    private static final String OWN_KEY =
            Base64.getEncoder().encodeToString("a key only this deployment knows!".getBytes(StandardCharsets.US_ASCII));

    @Test
    void refusesTheDevelopmentKeyOutsideTheDevProfile() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("daqiqa.promise.token.active-key-id", "dev")
                .withProperty("daqiqa.promise.token.keys.dev", DEV_KEY);

        assertThatExceptionOfType(DevelopmentKeyConfiguredException.class)
                .isThrownBy(() -> new DevelopmentKeyGuard(environment).check())
                .withMessageContaining("configured as daqiqa.promise.token.keys.dev")
                .withMessageContaining("activate the 'dev' profile");
    }

    /** The review's case: a deployment signs with a key of its own but still trusts the development key. */
    @Test
    void refusesTheDevelopmentKeyAsAVerifyOnlyKeyNextToAnActiveKeyOfTheDeploymentsOwn() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("daqiqa.promise.token.active-key-id", "k1")
                .withProperty("daqiqa.promise.token.keys.k1", OWN_KEY)
                .withProperty("daqiqa.promise.token.keys.dev", DEV_KEY);

        assertRefused(environment).containsExactly("daqiqa.promise.token.keys.dev");
    }

    @Test
    void recognisesTheKeyByItsBytesUnderAnyIdAndFromEnvironmentVariables() {
        MockEnvironment environment =
                new MockEnvironment().withProperty("daqiqa.promise.token.keys.legacy", " " + DEV_KEY + "\n");
        environment
                .getPropertySources()
                .addFirst(new SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.<String, Object>of("DAQIQA_PROMISE_TOKEN_KEYS_COPY", DEV_KEY)));

        assertRefused(environment)
                .containsExactly("daqiqa.promise.token.keys.copy", "daqiqa.promise.token.keys.legacy");
    }

    @Test
    void onlyWarnsWhenTheDevProfileIsActive(CapturedOutput output) {
        MockEnvironment environment = new MockEnvironment().withProperty("daqiqa.promise.token.keys.dev", DEV_KEY);
        environment.setActiveProfiles("dev");

        assertThatNoException().isThrownBy(() -> new DevelopmentKeyGuard(environment).check());
        assertThat(output)
                .contains("WARN")
                .contains("The published development key is configured as daqiqa.promise.token.keys.dev");
    }

    @Test
    void acceptsKeysOfTheDeploymentsOwnWithoutAnyProfile() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("daqiqa.promise.token.active-key-id", "k1")
                .withProperty("daqiqa.promise.token.keys.k1", OWN_KEY);

        assertThatNoException().isThrownBy(() -> new DevelopmentKeyGuard(environment).check());
    }

    @Test
    void leavesAValueThatIsNotAKeyToThePromiseModulesOwnCheck() {
        MockEnvironment environment = new MockEnvironment().withProperty("daqiqa.promise.token.keys.k1", "not*base64");

        assertThatNoException().isThrownBy(() -> new DevelopmentKeyGuard(environment).check());
    }

    @Test
    void theDevProfileShipsTheKeyTheGuardRefusesEverywhereElse() throws IOException {
        MockEnvironment environment = configuredBy("application.yaml", "application-dev.yaml");

        assertRefused(environment).containsExactly("daqiqa.promise.token.keys.dev");
        environment.setActiveProfiles("dev");
        assertThatNoException().isThrownBy(() -> new DevelopmentKeyGuard(environment).check());
        assertThat(Binder.get(environment)
                        .bind("spring.flyway.locations", String.class)
                        .get())
                .isEqualTo("classpath:db/migration,classpath:db/demo");
    }

    @Test
    void theDefaultConfigurationShipsNeitherAKeyNorDemoData() throws IOException {
        Binder configuration = Binder.get(configuredBy("application.yaml"));

        assertThat(configuration
                        .bind("daqiqa.promise.token.keys", Bindable.mapOf(String.class, String.class))
                        .isBound())
                .isFalse();
        assertThat(configuration
                        .bind("daqiqa.promise.token.active-key-id", String.class)
                        .orElse(""))
                .as("none: the promise module refuses to start without one")
                .isEmpty();
        assertThat(configuration.bind("spring.flyway.locations", String.class).get())
                .isEqualTo("classpath:db/migration");
    }

    @Test
    void theServerConfigurationRefusesToStartWithTheDevelopmentKeyOutsideTheDevProfile() {
        ApplicationContextRunner server = new ApplicationContextRunner()
                .withUserConfiguration(ServerConfiguration.class)
                .withPropertyValues("daqiqa.promise.token.keys.dev=" + DEV_KEY);

        server.run(context -> assertThat(context).getFailure().isInstanceOf(DevelopmentKeyConfiguredException.class));
        server.withInitializer(context -> context.getEnvironment().setActiveProfiles("dev"))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void explainsTheRefusalInSpringBootsStartupFailureReport() {
        DevelopmentKeyConfiguredException refusal =
                new DevelopmentKeyConfiguredException(List.of("daqiqa.promise.token.keys.dev"));

        FailureAnalysis analysis =
                new DevelopmentKeyGuard.Analyzer().analyze(new IllegalStateException("Context failed", refusal));

        assertThat(analysis.getDescription())
                .contains("configured as daqiqa.promise.token.keys.dev")
                .contains("'dev' profile");
        assertThat(analysis.getAction()).contains("openssl rand -base64 32").contains("--spring.profiles.active=dev");
        assertThat(SpringFactoriesLoader.forDefaultResourceLocation()
                        .load(FailureAnalyzer.class, null, FailureHandler.handleMessage((message, failure) -> {})))
                .as("registered in META-INF/spring.factories")
                .hasAtLeastOneElementOfType(DevelopmentKeyGuard.Analyzer.class);
    }

    private static ListAssert<String> assertRefused(MockEnvironment environment) {
        DevelopmentKeyConfiguredException refusal = catchThrowableOfType(
                DevelopmentKeyConfiguredException.class, () -> new DevelopmentKeyGuard(environment).check());
        assertThat(refusal).as("refused").isNotNull();
        return assertThat(refusal.properties());
    }

    /** An environment configured by the server's own configuration files; later files take precedence. */
    private static MockEnvironment configuredBy(String... files) throws IOException {
        MockEnvironment environment = new MockEnvironment();
        for (String file : files) {
            new YamlPropertySourceLoader()
                    .load(file, new ClassPathResource(file))
                    .forEach(source -> environment.getPropertySources().addFirst(source));
        }
        return environment;
    }
}
