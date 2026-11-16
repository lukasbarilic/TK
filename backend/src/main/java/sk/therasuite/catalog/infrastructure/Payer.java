package sk.therasuite.catalog.infrastructure;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import sk.therasuite.shared.BaseEntity;

@Entity
@Table(name = "payer")
public class Payer extends BaseEntity {

  private String code;
  private String name;
  private String type;
  private boolean active;

  public String getCode() {
    return code;
  }

  public String getName() {
    return name;
  }

  public String getType() {
    return type;
  }

  public boolean isActive() {
    return active;
  }
}
