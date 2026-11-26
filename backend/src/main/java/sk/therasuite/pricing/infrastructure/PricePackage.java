package sk.therasuite.pricing.infrastructure;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import sk.therasuite.shared.BaseEntity;

@Entity
@Table(name = "package")
public class PricePackage extends BaseEntity {

  private String code;
  private String name;
  private String boardType;
  private int proceduresPerDay;
  private Integer minNights;
  private Integer maxNights;
  private boolean active;

  public String getCode() {
    return code;
  }

  public String getName() {
    return name;
  }

  public String getBoardType() {
    return boardType;
  }

  public int getProceduresPerDay() {
    return proceduresPerDay;
  }

  public Integer getMinNights() {
    return minNights;
  }

  public Integer getMaxNights() {
    return maxNights;
  }

  public boolean isActive() {
    return active;
  }
}
