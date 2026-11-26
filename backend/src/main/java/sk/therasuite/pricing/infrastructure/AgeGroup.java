package sk.therasuite.pricing.infrastructure;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import sk.therasuite.shared.BaseEntity;

@Entity
@Table(name = "age_group")
public class AgeGroup extends BaseEntity {

  private String code;
  private String name;
  private int ageFrom;
  private Integer ageTo;

  public String getCode() {
    return code;
  }

  public String getName() {
    return name;
  }

  public int getAgeFrom() {
    return ageFrom;
  }

  public Integer getAgeTo() {
    return ageTo;
  }
}
