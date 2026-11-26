package sk.therasuite.pricing.infrastructure;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import sk.therasuite.shared.BaseEntity;

@Entity
@Table(name = "price_list")
public class PriceList extends BaseEntity {

  private String name;
  private LocalDate validFrom;
  private LocalDate validTo;
  private String status;

  public String getName() {
    return name;
  }

  public LocalDate getValidFrom() {
    return validFrom;
  }

  public LocalDate getValidTo() {
    return validTo;
  }

  public String getStatus() {
    return status;
  }
}
