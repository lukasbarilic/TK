package sk.therasuite.pricing.infrastructure;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import sk.therasuite.shared.BaseEntity;

@Entity
@Table(name = "price_rate")
public class PriceRate extends BaseEntity {

  private Long priceSeasonId;
  private Long roomCategoryId;
  private Long packageId;
  private String priceType;
  private Integer occupancy;
  private BigDecimal amount;
  private BigDecimal extraBedAmount;
  private String unit;
  private Integer nights;

  public Long getPriceSeasonId() {
    return priceSeasonId;
  }

  public Long getRoomCategoryId() {
    return roomCategoryId;
  }

  public Long getPackageId() {
    return packageId;
  }

  public String getPriceType() {
    return priceType;
  }

  public Integer getOccupancy() {
    return occupancy;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public BigDecimal getExtraBedAmount() {
    return extraBedAmount;
  }

  public String getUnit() {
    return unit;
  }

  public Integer getNights() {
    return nights;
  }
}
