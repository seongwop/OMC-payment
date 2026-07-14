package com.omc.payment.arch;

import com.omc.arch.CommonArchRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.stereotype.Service;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

@AnalyzeClasses(packages = "com.omc.payment", importOptions = ImportOption.DoNotIncludeTests.class)
public class PaymentServiceArchTest {

    @ArchTest
    static final ArchRule layer_dependency_rule = CommonArchRules.layerDependencyRule();

    @ArchTest
    static final ArchRule no_direct_user_import =
            CommonArchRules.noDirectServiceCrossImport("payment", "user");

    @ArchTest
    static final ArchRule no_direct_product_import =
            CommonArchRules.noDirectServiceCrossImport("payment", "product");

    @ArchTest
    static final ArchRule no_direct_drop_import =
            CommonArchRules.noDirectServiceCrossImport("payment", "drop");

    @ArchTest
    static final ArchRule no_direct_raffle_import =
            CommonArchRules.noDirectServiceCrossImport("payment", "raffle");

    @ArchTest
    static final ArchRule no_direct_order_import =
            CommonArchRules.noDirectServiceCrossImport("payment", "order");

    @ArchTest
    static final ArchRule no_direct_coupon_import =
            CommonArchRules.noDirectServiceCrossImport("payment", "coupon");

    @ArchTest
    static final ArchRule no_direct_notification_import =
            CommonArchRules.noDirectServiceCrossImport("payment", "notification");

    @ArchTest
    static final ArchRule controller_naming = CommonArchRules.controllerNamingRule();

    @ArchTest
    static final ArchRule service_naming = classes()
            .that().resideInAPackage("..service..")
            .and().areTopLevelClasses()
            .should().haveSimpleNameEndingWith("Service");

    @ArchTest
    static final ArchRule repository_naming = CommonArchRules.repositoryNamingRule();

    @ArchTest
    static final ArchRule request_dto_naming = CommonArchRules.requestDtoNamingRule();

    @ArchTest
    static final ArchRule response_dto_naming = CommonArchRules.responseDtoNamingRule();

    @ArchTest
    static final ArchRule controller_annotation = CommonArchRules.controllerAnnotationRule();

    @ArchTest
    static final ArchRule service_annotation = classes()
            .that().resideInAPackage("..service..")
            .and().areTopLevelClasses()
            .and().areNotInterfaces()
            .should().beAnnotatedWith(Service.class);

    @ArchTest
    static final ArchRule repository_annotation = CommonArchRules.repositoryAnnotationRule();

    @ArchTest
    static final ArchRule entity_annotation = CommonArchRules.entityAnnotationRule();
}
