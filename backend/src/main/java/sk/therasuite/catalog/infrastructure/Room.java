package sk.therasuite.catalog.infrastructure;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import sk.therasuite.shared.BaseEntity;

@Entity
@Table(name = "room")
public class Room extends BaseEntity {

  private Long buildingId;
  private String number;
  private String floor;
  private Long categoryId;
  private Long secondaryCategoryId;
  private Long departmentId;
  private int regularBeds;
  private int extraBeds;
  private Integer extraBedMaxAge;
  private boolean bookable;
  private boolean active;

  public Long getBuildingId() {
    return buildingId;
  }

  public String getNumber() {
    return number;
  }

  public String getFloor() {
    return floor;
  }

  public Long getCategoryId() {
    return categoryId;
  }

  public Long getSecondaryCategoryId() {
    return secondaryCategoryId;
  }

  public Long getDepartmentId() {
    return departmentId;
  }

  public int getRegularBeds() {
    return regularBeds;
  }

  public int getExtraBeds() {
    return extraBeds;
  }

  public Integer getExtraBedMaxAge() {
    return extraBedMaxAge;
  }

  public boolean isBookable() {
    return bookable;
  }

  public boolean isActive() {
    return active;
  }
}
