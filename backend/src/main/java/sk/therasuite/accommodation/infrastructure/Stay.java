package sk.therasuite.accommodation.infrastructure;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import sk.therasuite.shared.BaseEntity;

/** Pobyt: jeden pásik v pláne, polootvorený interval nocí {@code [dateFrom, dateTo)}. */
@Entity
@Table(name = "stay")
public class Stay extends BaseEntity {

  private Long reservationId;
  private Long bedId;
  private LocalDate dateFrom;
  private LocalDate dateTo;
  private Long priceCategoryId;
  private Long surchargeCategoryId;
  private boolean locked;

  protected Stay() {}

  public Stay(
      Long reservationId, Long bedId, LocalDate dateFrom, LocalDate dateTo, Long priceCategoryId) {
    this.reservationId = reservationId;
    this.bedId = bedId;
    this.dateFrom = dateFrom;
    this.dateTo = dateTo;
    this.priceCategoryId = priceCategoryId;
  }

  public Long getReservationId() {
    return reservationId;
  }

  public Long getBedId() {
    return bedId;
  }

  public LocalDate getDateFrom() {
    return dateFrom;
  }

  public LocalDate getDateTo() {
    return dateTo;
  }

  public Long getPriceCategoryId() {
    return priceCategoryId;
  }

  public Long getSurchargeCategoryId() {
    return surchargeCategoryId;
  }

  public boolean isLocked() {
    return locked;
  }
}
