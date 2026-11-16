package sk.therasuite.catalog.infrastructure;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import sk.therasuite.shared.BaseEntity;

@Entity
@Table(name = "room_category")
public class RoomCategory extends BaseEntity {

  private Long centreId;
  private Long baseCategoryId;
  private String code;
  private String name;
  private int bedCount;
  private boolean active;

  public Long getCentreId() {
    return centreId;
  }

  public Long getBaseCategoryId() {
    return baseCategoryId;
  }

  public String getCode() {
    return code;
  }

  public String getName() {
    return name;
  }

  public int getBedCount() {
    return bedCount;
  }

  public boolean isActive() {
    return active;
  }
}
