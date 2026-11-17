package sk.therasuite.businessday.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import sk.therasuite.audit.application.AuditEventSpec;
import sk.therasuite.audit.application.AuditService;

/** Pri štarte otvorí prvý obchodný deň recepciám, ktoré ešte žiadny nemajú (po demo dátach). */
@Component
@Order(20)
public class BusinessDayInitializer implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(BusinessDayInitializer.class);

  private final BusinessDayService service;
  private final AuditService audit;

  public BusinessDayInitializer(BusinessDayService service, AuditService audit) {
    this.service = service;
    this.audit = audit;
  }

  @Override
  public void run(ApplicationArguments args) {
    audit.run(
        AuditEventSpec.system("BUSINESS_DAY_OPEN_INITIAL"),
        () -> {
          int opened = service.openMissingDays();
          if (opened > 0) {
            log.info("Otvorený prvý obchodný deň pre {} recepcií.", opened);
          }
        });
  }
}
