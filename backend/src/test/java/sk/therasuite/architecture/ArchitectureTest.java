package sk.therasuite.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "sk.therasuite", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  static final ArchRule domainIsFrameworkFree =
      noClasses()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("org.springframework..", "jakarta.persistence..")
          .allowEmptyShould(true);

  /** Modul smie používať len vlastnú infraštruktúru (modul shared je spoločný). */
  @ArchTest
  static final ArchRule modulesDoNotUseForeignInfrastructure =
      slices()
          .matching("sk.therasuite.(*)..")
          .should(
              new com.tngtech.archunit.lang.ArchCondition<
                  com.tngtech.archunit.library.dependencies.Slice>(
                  "not use foreign infrastructure") {
                @Override
                public void check(
                    com.tngtech.archunit.library.dependencies.Slice slice,
                    com.tngtech.archunit.lang.ConditionEvents events) {
                  slice.getDependenciesFromSelf().stream()
                      .filter(d -> d.getTargetClass().getPackageName().contains(".infrastructure"))
                      .filter(d ->
                          !d.getTargetClass()
                              .getPackageName()
                              .startsWith("sk.therasuite." + slice.getDescription()))
                      .filter(d ->
                          !d.getTargetClass().getPackageName().startsWith("sk.therasuite.shared"))
                      .forEach(d ->
                          events.add(
                              com.tngtech.archunit.lang.SimpleConditionEvent.violated(
                                  d, d.getDescription())));
                }
              })
          .allowEmptyShould(true);
}
