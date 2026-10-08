package com.veritrade.reporting;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Layers point downwards only (api -> service -> repository); domain knows nothing about Spring. */
@AnalyzeClasses(packages = "com.veritrade.reporting", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule layersPointDownwards = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Api").definedBy("..reporting.api..")
            .layer("Messaging").definedBy("..reporting.messaging..")
            .layer("Service").definedBy("..reporting.service..")
            .layer("Repository").definedBy("..reporting.repository..")
            .layer("Domain").definedBy("..reporting.domain..")
            .layer("Config").definedBy("..reporting.config..")
            .whereLayer("Api").mayNotBeAccessedByAnyLayer()
            .whereLayer("Messaging").mayNotBeAccessedByAnyLayer()
            .whereLayer("Service").mayOnlyBeAccessedByLayers("Api", "Messaging")
            .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service")
            .whereLayer("Domain").mayOnlyBeAccessedByLayers("Api", "Messaging", "Service", "Repository")
            .whereLayer("Config").mayOnlyBeAccessedByLayers("Api", "Messaging", "Service");

    @ArchTest
    static final ArchRule domainHasNoSpringDependencies = noClasses()
            .that().resideInAPackage("..reporting.domain..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework..");

    @ArchTest
    static final ArchRule apiDoesNotUseRepositoriesOrMessaging = noClasses()
            .that().resideInAPackage("..reporting.api..")
            .should().dependOnClassesThat().resideInAnyPackage("..reporting.repository..", "..reporting.messaging..");
}
