package sk.therasuite.pricing.infrastructure;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import sk.therasuite.shared.BaseEntity;

@Entity
@Table(name = "price_season")
public class PriceSeason extends BaseEntity {

  private Long priceListId;
  private String code;
  private String name;
  private LocalDate dateFrom;
  private LocalDate dateTo;
  private int priority;

  public Long getPriceListId() {
    return priceListId;
  }

  public String getCode() {
    return code;
  }

  public String getName() {
    return name;
  }

  public LocalDate getDateFrom() {
    return dateFrom;
  }

  public LocalDate getDateTo() {
    return dateTo;
  }

  public int getPriority() {
    return priority;
  }
}
