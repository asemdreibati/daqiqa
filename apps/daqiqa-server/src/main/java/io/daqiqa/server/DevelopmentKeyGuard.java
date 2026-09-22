package io.daqiqa.server;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.core.env.Environment;

/**
 * Refuses to start while the published development signing key is configured, unless the {@code dev} profile is
 * active.
 *
 * <p>The development key is in this repository ({@code application-dev.yaml}), so anyone can sign promise tokens with
 * it. Configured under any key id, even one that only verifies, it lets forged tokens through checkout: the review
 * signed a one-minute ETA valid for a day with it and placed an order. The guard compares the decoded key bytes, so the
 * key copied under another id or with other whitespace is refused too.
 *
 * <p>A bean factory post-processor runs before any bean is created: nothing (migrations, the web server) happens before
 * the refusal.
 */
final class DevelopmentKeyGuard implements BeanFactoryPostProcessor {

    static final String DEV_PROFILE = "dev";

    /** The published development key, base64-encoded exactly as in {@code application-dev.yaml}. */
    static final String DEVELOPMENT_KEY_BASE64 = "ZGFxaXFhLWRldi1vbmx5LXByb21pc2Utc2lnbmluZy1rZXkh";

    private static final String KEYS = "daqiqa.promise.token.keys";
    private static final byte[] DEVELOPMENT_KEY = Base64.getDecoder().decode(DEVELOPMENT_KEY_BASE64);
    private static final Logger log = LoggerFactory.getLogger(DevelopmentKeyGuard.class);

    private final Environment environment;

    DevelopmentKeyGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        check();
    }

    /**
     * Passes when no configured key is the development key; warns when one is and the dev profile is active.
     *
     * @throws DevelopmentKeyConfiguredException when one is and the dev profile is not active
     */
    void check() {
        List<String> properties = developmentKeyProperties();
        if (properties.isEmpty()) {
            return;
        }
        if (!environment.matchesProfiles(DEV_PROFILE)) {
            throw new DevelopmentKeyConfiguredException(properties);
        }
        log.warn(
                "The published development key is configured as {}: anyone can sign promise tokens this instance "
                        + "accepts. Never expose an instance running the '{}' profile.",
                String.join(", ", properties),
                DEV_PROFILE);
    }

    /** The configured key properties whose value is the development key, sorted. */
    private List<String> developmentKeyProperties() {
        Map<String, String> keys = Binder.get(environment)
                .bind(KEYS, Bindable.mapOf(String.class, String.class))
                .orElseGet(Map::of);
        return keys.entrySet().stream()
                .filter(key -> isDevelopmentKey(key.getValue()))
                .map(key -> KEYS + "." + key.getKey())
                .sorted()
                .toList();
    }

    private static boolean isDevelopmentKey(String base64) {
        try {
            // Decoded the way the promise module decodes keys, so that every spelling of the same bytes is caught.
            return Arrays.equals(DEVELOPMENT_KEY, Base64.getDecoder().decode(base64.strip()));
        } catch (IllegalArgumentException notBase64) {
            return false; // not a key at all: the promise module refuses it with its own message
        }
    }

    /** The published development key is configured and the dev profile is not active. */
    static final class DevelopmentKeyConfiguredException extends IllegalStateException {

        private final List<String> properties;

        DevelopmentKeyConfiguredException(List<String> properties) {
            super("Refusing to start: the published development key, which anyone can sign promise tokens with, is "
                    + "configured as " + String.join(", ", properties) + ". Configure your own keys, or activate the '"
                    + DEV_PROFILE + "' profile for local development.");
            this.properties = List.copyOf(properties);
        }

        List<String> properties() {
            return properties;
        }
    }

    /** Explains the refusal in Spring Boot's "APPLICATION FAILED TO START" report (registered in spring.factories). */
    static final class Analyzer extends AbstractFailureAnalyzer<DevelopmentKeyConfiguredException> {

        @Override
        protected FailureAnalysis analyze(Throwable rootFailure, DevelopmentKeyConfiguredException cause) {
            return new FailureAnalysis(
                    "The published development signing key is configured as " + String.join(", ", cause.properties())
                            + ". Anyone can sign promise tokens with it, so it is accepted only while the '"
                            + DEV_PROFILE + "' profile is active.",
                    "Remove it and configure a key of your own, for example daqiqa.promise.token.keys.<id>="
                            + "$(openssl rand -base64 32) with daqiqa.promise.token.active-key-id=<id>. For local "
                            + "development, run with the '" + DEV_PROFILE + "' profile "
                            + "(--spring.profiles.active=" + DEV_PROFILE + ").",
                    cause);
        }
    }
}
