package io.daqiqa.ordering;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.daqiqa.testing.architecture.ArchitectureRules;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

    private static final ClassFileImporter IMPORTER =
            new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS);

    @Test
    void respectsModuleArchitecture() {
        ArchitectureRules.moduleRules("ordering").check(IMPORTER.importPackages("io.daqiqa.ordering"));
    }

    /**
     * Ordering calls the promise, inventory and catalog modules only through their api packages. Checked over
     * everything on this module's classpath: the server application checks the same rule over the whole system.
     */
    @Test
    void usesOtherModulesOnlyThroughTheirApis() {
        JavaClasses reachable = IMPORTER.importPackages(ArchitectureRules.ROOT);
        ArchitectureRules.modulesTalkOnlyThroughTheirApis().check(reachable);
    }
}
