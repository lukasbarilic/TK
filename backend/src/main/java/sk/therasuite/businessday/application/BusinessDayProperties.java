package sk.therasuite.businessday.application;

import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Nastavenia: časové pásmo inštalácie a voliteľný prvý obchodný deň (app.business-day.initial). */
@Component
public class BusinessDayProperties {

  private final String timezone;
  private final LocalDate initial;

  public BusinessDayProperties(
      @Value("${organization.timezone:Europe/Bratislava}") String timezone,
      @Value("${app.business-day.initial:#{null}}") LocalDate initial) {
    this.timezone = timezone;
    this.initial = initial;
  }

  public String timezone() {
    return timezone;
  }

  public LocalDate initial() {
    return initial;
  }
}
