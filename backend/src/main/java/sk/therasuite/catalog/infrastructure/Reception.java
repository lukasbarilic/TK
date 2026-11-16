package sk.therasuite.catalog.infrastructure;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import sk.therasuite.shared.BaseEntity;

@Entity
@Table(name = "reception")
public class Reception extends BaseEntity {

  private String code;
  private String name;
  private String email;
  private boolean active;

  public String getCode() {
    return code;
  }

  public String getName() {
    return name;
  }

  public String getEmail() {
    return email;
  }

  public boolean isActive() {
    return active;
  }
}
