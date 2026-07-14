package com.omc.arch;

import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;
import jakarta.persistence.Entity;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

public class CommonArchRules {

    // 1. 레이어 의존성 (Layered Architecture)
    public static ArchRule layerDependencyRule() {
        return layeredArchitecture()
                .consideringAllDependencies()
                .withOptionalLayers(true)
                .layer("Controllers").definedBy("..controller..")
                .layer("Services").definedBy("..service..")
                .layer("Processors").definedBy("..processor..")
                .layer("Repositories").definedBy("..repository..")
                .layer("Entities").definedBy("..entity..")
                .layer("DTOs").definedBy("..dto..")
                .layer("Schedulers").definedBy("..scheduler..")
                .layer("Events").definedBy("..event..")
                .layer("Consumers").definedBy("..consumer..")
                .layer("Warmup").definedBy("..warmup..")
                .layer("Infrastructure").definedBy("..infrastructure..")

                .whereLayer("Controllers").mayNotBeAccessedByAnyLayer()
                .whereLayer("Services").mayOnlyBeAccessedByLayers("Controllers", "Services", "Schedulers", "Events", "Consumers", "Warmup")
                .whereLayer("Processors").mayOnlyBeAccessedByLayers("Services")
                .whereLayer("Repositories").mayOnlyBeAccessedByLayers("Services", "Processors", "Repositories", "Schedulers", "Events", "Consumers", "Warmup")
                .whereLayer("Entities").mayOnlyBeAccessedByLayers("Controllers", "Services", "Processors", "Repositories", "Entities", "DTOs", "Schedulers", "Events", "Consumers", "Infrastructure", "Warmup")
                .whereLayer("DTOs").mayOnlyBeAccessedByLayers("Controllers", "Services", "Processors", "Repositories", "Entities", "Schedulers", "Events", "Consumers", "Infrastructure", "Warmup")
                .whereLayer("Consumers").mayNotBeAccessedByAnyLayer()
                .whereLayer("Schedulers").mayNotBeAccessedByAnyLayer()
                .whereLayer("Warmup").mayNotBeAccessedByAnyLayer()
                .whereLayer("Infrastructure").mayOnlyBeAccessedByLayers("Controllers", "Services", "Repositories", "Schedulers", "Events", "Consumers", "Infrastructure", "Warmup");
    }

    // 2. MSA 직접 침범 금지 (No direct import across domains)
    public static ArchRule noDirectServiceCrossImport(String sourceDomain, String forbiddenDomain) {
        return noClasses()
                .that().resideInAPackage("..com.omc." + sourceDomain + "..")
                .should().dependOnClassesThat().resideInAPackage("..com.omc." + forbiddenDomain + "..");
    }

    // 3. 네이밍 규칙 (Naming Rules)
    public static ArchRule controllerNamingRule() {
        return classes()
                .that().resideInAPackage("..controller..")
                .should().haveSimpleNameEndingWith("Controller");
    }

    public static ArchRule serviceNamingRule() {
        return classes()
                .that().resideInAPackage("..service..")
                .should().haveSimpleNameEndingWith("Service");
    }

    public static ArchRule repositoryNamingRule() {
        return classes()
                .that().resideInAPackage("..repository..")
                .should().haveSimpleNameEndingWith("Repository");
    }

    public static ArchRule processorNamingRule() {
        return classes()
                .that().resideInAPackage("..processor..")
                .should().haveSimpleNameEndingWith("Processor")
                .orShould().haveSimpleNameEndingWith("Handler");
    }

    public static ArchRule requestDtoNamingRule() {
        return classes()
                .that().resideInAPackage("..dto.request..")
                .should().haveSimpleNameEndingWith("Request");
    }

    public static ArchRule responseDtoNamingRule() {
        return classes()
                .that().resideInAPackage("..dto.response..")
                .should().haveSimpleNameEndingWith("Response");
    }

    // 4. 어노테이션 규칙 (Annotation Rules)
    public static ArchRule controllerAnnotationRule() {
        return classes()
                .that().resideInAPackage("..controller..")
                .should().beAnnotatedWith(RestController.class)
                .orShould().beAnnotatedWith(Controller.class);
    }

    public static ArchRule serviceAnnotationRule() {
        return classes()
                .that().resideInAPackage("..service..")
                .and().areNotInterfaces()
                .should().beAnnotatedWith(Service.class);
    }

    public static ArchRule processorAnnotationRule() {
        return classes()
                .that().resideInAPackage("..processor..")
                .and().areNotInterfaces()
                .should().beAnnotatedWith(Component.class);
    }

    public static ArchRule repositoryAnnotationRule() {
        return classes()
                .that().resideInAPackage("..repository..")
                .and().areInterfaces()
                .should().beAnnotatedWith(Repository.class)
                .orShould().beAssignableTo(org.springframework.data.repository.Repository.class);
    }

    public static ArchRule entityAnnotationRule() {
        return classes()
                .that().resideInAPackage("..entity..")
                .and().areTopLevelClasses()
                .and().doNotHaveModifier(JavaModifier.ABSTRACT)
                .should().beAnnotatedWith(Entity.class);
    }
}
