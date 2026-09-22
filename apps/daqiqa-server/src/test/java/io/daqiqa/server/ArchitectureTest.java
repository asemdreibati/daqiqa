package io.daqiqa.server;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.daqiqa.testing.architecture.ArchitectureRules;
import org.junit.jupiter.api.Test;

/** Cross-module rules, checked over the whole assembled application. */
class ArchitectureTest {

    private static final JavaClasses ALL = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ArchitectureRules.ROOT);

    @Test
    void modulesTalkOnlyThroughTheirApis() {
        ArchitectureRules.modulesTalkOnlyThroughTheirApis().check(ALL);
    }

    @Test
    void thereAreNoCyclesBetweenModules() {
        ArchitectureRules.noCyclesBetweenModules().check(ALL);
    }
}
