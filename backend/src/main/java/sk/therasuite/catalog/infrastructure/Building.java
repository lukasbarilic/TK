package sk.therasuite.catalog.infrastructure;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import sk.therasuite.shared.BaseEntity;

@Entity
@Table(name = "building")
public class Building extends BaseEntity {

  private Long centreId;
  private Long receptionId;
  private String code;
  private String name;
  private String site;
  private int sortOrder;
  private boolean active;

  public Long getCentreId() {
    return centreId;
  }

  public Long getReceptionId() {
    return receptionId;
  }

  public String getCode() {
    return code;
  }

  public String getName() {
    return name;
  }

  public String getSite() {
    return site;
  }

  public int getSortOrder() {
    return sortOrder;
  }

  public boolean isActive() {
    return active;
  }
}
