package com.veritrade.ingestion;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.veritrade.ingestion", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule layersPointDownwardsOnly = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Api").definedBy("..ingestion.api..")
            .layer("Messaging").definedBy("..ingestion.messaging..")
            .layer("Service").definedBy("..ingestion.service..")
            .layer("Repository").definedBy("..ingestion.repository..")
            .layer("Domain").definedBy("..ingestion.domain..")
            .layer("Config").definedBy("..ingestion.config..")
            .whereLayer("Api").mayOnlyBeAccessedByLayers("Config")
            .whereLayer("Messaging").mayOnlyBeAccessedByLayers("Config")
            .whereLayer("Service").mayOnlyBeAccessedByLayers("Api", "Messaging", "Config")
            .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service")
            .whereLayer("Domain").mayOnlyBeAccessedByLayers("Api", "Messaging", "Service", "Repository", "Config");

    @ArchTest
    static final ArchRule domainKnowsNothingAboutSpring = noClasses()
            .that().resideInAPackage("..ingestion.domain..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..");

    @ArchTest
    static final ArchRule apiDoesNotTalkToTheBroker = noClasses()
            .that().resideInAPackage("..ingestion.api..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.amqp..");

    @ArchTest
    static final ArchRule eventContractsAreNotRedefined = noClasses()
            .that().resideInAPackage("com.veritrade.ingestion..")
            .should().haveSimpleNameEndingWith("Payload")
            .orShould().haveSimpleName("EventEnvelope")
            .orShould().haveSimpleName("EventType");
}
