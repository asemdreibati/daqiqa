package io.daqiqa.testing.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import java.util.List;

/**
 * Executable versions of the architecture decisions (ADR-0002, ADR-0003). Every module runs the module rules in its
 * own {@code ArchitectureTest}; the server application runs the cross-module rules over the whole codebase.
 */
public final class ArchitectureRules {

    public static final String ROOT = "io.daqiqa";

    /** Bounded contexts. Their public surface is the {@code api} package; everything else is internal. */
    public static final List<String> MODULES = List.of("network", "catalog", "inventory", "promise", "ordering");

    private static final String[] FRAMEWORK_PACKAGES = {
        "org.springframework..",
        "jakarta..",
        "tools.jackson..",
        "com.fasterxml.jackson..",
        "java.sql..",
        "javax.sql..",
        "org.flywaydb..",
        "org.apache.kafka..",
        "io.micrometer.."
    };

    private ArchitectureRules() {}

    /** The domain model is plain Java: no frameworks, no JDBC, no JSON, no metrics. */
    public static ArchRule domainIsFrameworkFree(String module) {
        return noClasses()
                .that()
                .resideInAPackage(base(module) + ".domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(FRAMEWORK_PACKAGES)
                .as("the " + module + " domain model is framework-free");
    }

    /**
     * Hexagonal layering inside a module: adapters and config depend inwards, the domain depends on nothing, and the
     * public API never exposes domain types.
     */
    public static ArchRule layersAreRespected(String module) {
        String base = base(module);
        return layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                .withOptionalLayers(true)
                .layer("Api")
                .definedBy(base + ".api..")
                .layer("Domain")
                .definedBy(base + ".domain..")
                .layer("Application")
                .definedBy(base + ".application..")
                .layer("Adapters")
                .definedBy(base + ".adapter..")
                .layer("Config")
                .definedBy(base + ".config..")
                .whereLayer("Config")
                .mayNotBeAccessedByAnyLayer()
                .whereLayer("Adapters")
                .mayOnlyBeAccessedByLayers("Config")
                .whereLayer("Application")
                .mayOnlyBeAccessedByLayers("Adapters", "Config")
                .whereLayer("Domain")
                .mayOnlyBeAccessedByLayers("Application", "Adapters", "Config")
                .as("the " + module + " module respects its hexagonal layers");
    }

    /** Both module rules, for a module's own ArchitectureTest. */
    public static ArchRule moduleRules(String module) {
        return CompositeArchRule.of(domainIsFrameworkFree(module)).and(layersAreRespected(module));
    }

    /** Other modules may only use a module through its {@code api} package. */
    public static ArchRule modulesTalkOnlyThroughTheirApis() {
        List<ArchRule> rules = MODULES.stream()
                .<ArchRule>map(module -> noClasses()
                        .that()
                        .resideOutsideOfPackage(base(module) + "..")
                        .should()
                        .dependOnClassesThat()
                        .resideInAnyPackage(
                                base(module) + ".domain..",
                                base(module) + ".application..",
                                base(module) + ".adapter..",
                                base(module) + ".config..")
                        .as("other modules use " + module + " only through " + base(module) + ".api"))
                .toList();
        return CompositeArchRule.of(rules);
    }

    /** No dependency cycles between top-level packages (modules and platform components). */
    public static ArchRule noCyclesBetweenModules() {
        return slices().matching(ROOT + ".(*)..").should().beFreeOfCycles().as("modules are free of cycles");
    }

    private static String base(String module) {
        return ROOT + "." + module;
    }
}
