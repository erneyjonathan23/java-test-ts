package com.meridian.payments.intake;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Hexagonal (ports and adapters) rules, checked on every build so the structure cannot erode:
 * the domain and the use case know nothing about Spring, HTTP, Azure or a specific provider.
 */
@AnalyzeClasses(packages = "com.meridian.payments.intake", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String BASE = "com.meridian.payments.intake";

    @ArchTest
    static final ArchRule layers = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Domain").definedBy(BASE + ".domain..")
            .layer("Application").definedBy(BASE + ".application..")
            .layer("Adapters").definedBy(BASE + ".adapter..")
            .layer("Config").definedBy(BASE + ".config..")
            .whereLayer("Config").mayNotBeAccessedByAnyLayer()
            .whereLayer("Adapters").mayOnlyBeAccessedByLayers("Config")
            .whereLayer("Application").mayOnlyBeAccessedByLayers("Adapters", "Config")
            .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Adapters", "Config");

    @ArchTest
    static final ArchRule domainIsPlainJava = classes()
            .that().resideInAPackage(BASE + ".domain..")
            .should().onlyDependOnClassesThat()
            // Jackson annotations only name the CloudEvents attributes; they carry no behaviour.
            .resideInAnyPackage(BASE + ".domain..", "java..", "com.fasterxml.jackson.annotation..");

    @ArchTest
    static final ArchRule applicationIsFrameworkFree = noClasses()
            .that().resideInAPackage(BASE + ".application..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "jakarta..", "com.azure..", "io.micrometer..",
                    "com.fasterxml.jackson..");

    @ArchTest
    static final ArchRule providersAreIsolatedFromEachOther = slices()
            .matching(BASE + ".adapter.in.provider.(*)..")
            .should().notDependOnEachOther()
            .ignoreDependency(com.tngtech.archunit.base.DescribedPredicate.alwaysTrue(),
                    com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage(BASE + ".adapter.in.provider.support.."));

    @ArchTest
    static final ArchRule inboundAndOutboundAdaptersDoNotKnowEachOther = noClasses()
            .that().resideInAPackage(BASE + ".adapter.in..")
            .should().dependOnClassesThat().resideInAPackage(BASE + ".adapter.out..");
}
