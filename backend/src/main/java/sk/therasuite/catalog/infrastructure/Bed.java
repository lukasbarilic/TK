package sk.therasuite.catalog.infrastructure;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import sk.therasuite.shared.BaseEntity;

@Entity
@Table(name = "bed")
public class Bed extends BaseEntity {

  private Long roomId;
  private int bedNumber;
  private String type;
  private boolean active;

  public Long getRoomId() {
    return roomId;
  }

  public int getBedNumber() {
    return bedNumber;
  }

  public String getType() {
    return type;
  }

  public boolean isActive() {
    return active;
  }
}
