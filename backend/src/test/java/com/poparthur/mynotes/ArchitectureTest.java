package com.poparthur.mynotes;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.poparthur.mynotes", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
    @ArchTest
    static final ArchRule repoDoesNotDependOnUpperLayers = noClasses().that().resideInAPackage("..repo..")
            .should().dependOnClassesThat().resideInAnyPackage("..service..", "..http..");

    @ArchTest
    static final ArchRule entitiesDoNotDependOnUpperLayers = noClasses().that().resideInAPackage("..repo.entity..")
            .should().dependOnClassesThat().resideInAnyPackage("..service..", "..http..");

    @ArchTest
    static final ArchRule servicesDoNotDependOnHttp = noClasses().that().resideInAPackage("..service..")
            .should().dependOnClassesThat().resideInAPackage("com.poparthur.mynotes..http..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule httpDoesNotAccessRepositoriesOrEntities = noClasses().that().resideInAPackage("com.poparthur.mynotes..http..")
            .should().dependOnClassesThat().resideInAPackage("..repo..")
            .allowEmptyShould(true);
    @ArchTest
    static final ArchRule serviceResultsDoNotExposePersistenceTypes = noClasses().that().resideInAPackage("..service.result..")
            .should().dependOnClassesThat().resideInAPackage("..repo..");
    @ArchTest
    static final ArchRule servicesDoNotDependOnJpaRepositoryImplementations = noClasses().that().resideInAPackage("..service..")
            .should().dependOnClassesThat().haveSimpleNameStartingWith("Jpa")
            .allowEmptyShould(true);
}
