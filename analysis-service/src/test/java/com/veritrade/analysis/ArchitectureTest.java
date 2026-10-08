package com.veritrade.analysis;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Layering of the Analysis service: config wires everything; messaging is the edge to the broker;
 * the engine and the domain are pure Java with no Spring, broker or JSON dependency.
 */
@AnalyzeClasses(packages = "com.veritrade.analysis", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule layersDependOnlyDownwards = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Config").definedBy("..analysis.config..")
            .layer("Messaging").definedBy("..analysis.messaging..")
            .layer("Engine").definedBy("..analysis.engine..")
            .layer("Domain").definedBy("..analysis.domain..")
            .whereLayer("Config").mayNotBeAccessedByAnyLayer()
            .whereLayer("Messaging").mayOnlyBeAccessedByLayers("Config")
            .whereLayer("Engine").mayOnlyBeAccessedByLayers("Messaging", "Config")
            .whereLayer("Domain").mayOnlyBeAccessedByLayers("Engine", "Messaging", "Config");

    @ArchTest
    static final ArchRule engineAndDomainAreFreeOfFrameworks = noClasses()
            .that().resideInAnyPackage("..analysis.engine..", "..analysis.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "tools.jackson..", "com.rabbitmq..", "jakarta..");

    @ArchTest
    static final ArchRule domainDoesNotDependOnTheEngine = noClasses()
            .that().resideInAPackage("..analysis.domain..")
            .should().dependOnClassesThat().resideInAPackage("..analysis.engine..");
}
