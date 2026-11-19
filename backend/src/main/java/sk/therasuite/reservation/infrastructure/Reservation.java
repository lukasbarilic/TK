package sk.therasuite.reservation.infrastructure;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import sk.therasuite.reservation.domain.PriceCategoryMode;
import sk.therasuite.reservation.domain.PriceType;
import sk.therasuite.reservation.domain.ReservationStatus;
import sk.therasuite.shared.BaseEntity;

@Entity
@Table(name = "reservation")
public class Reservation extends BaseEntity {

  private String reservationNumber;

  @Enumerated(EnumType.STRING)
  private ReservationStatus status = ReservationStatus.RESERVED;

  private LocalDate arrivalDate;
  private LocalDate departureDate;
  private Long bookedCategoryId;
  private Long payerId;
  private Long groupId;
  private Long partyId;
  private boolean exclusiveRoomUse;

  @Enumerated(EnumType.STRING)
  private PriceCategoryMode priceCategoryMode = PriceCategoryMode.FOLLOW_ROOM;

  @Enumerated(EnumType.STRING)
  private PriceType priceType = PriceType.PER_BED;

  private boolean doNotMove;
  private boolean earlyCheckIn;
  private boolean lateCheckOut;
  private int adults = 1;
  private int children;
  private Instant checkedInAt;
  private Instant checkedOutAt;
  private Instant cancelledAt;
  private String cancelReason;

  protected Reservation() {}

  public Reservation(
      String reservationNumber,
      LocalDate arrivalDate,
      LocalDate departureDate,
      Long bookedCategoryId,
      Long payerId,
      int adults,
      int children,
      boolean exclusiveRoomUse) {
    this.reservationNumber = reservationNumber;
    this.arrivalDate = arrivalDate;
    this.departureDate = departureDate;
    this.bookedCategoryId = bookedCategoryId;
    this.payerId = payerId;
    this.adults = adults;
    this.children = children;
    this.exclusiveRoomUse = exclusiveRoomUse;
  }

  public String getReservationNumber() {
    return reservationNumber;
  }

  public ReservationStatus getStatus() {
    return status;
  }

  public LocalDate getArrivalDate() {
    return arrivalDate;
  }

  public void setArrivalDate(LocalDate arrivalDate) {
    this.arrivalDate = arrivalDate;
  }

  public LocalDate getDepartureDate() {
    return departureDate;
  }

  public void setDepartureDate(LocalDate departureDate) {
    this.departureDate = departureDate;
  }

  public Long getBookedCategoryId() {
    return bookedCategoryId;
  }

  public void setBookedCategoryId(Long bookedCategoryId) {
    this.bookedCategoryId = bookedCategoryId;
  }

  public Long getPayerId() {
    return payerId;
  }

  public void setPayerId(Long payerId) {
    this.payerId = payerId;
  }

  public Long getGroupId() {
    return groupId;
  }

  public Long getPartyId() {
    return partyId;
  }

  public boolean isExclusiveRoomUse() {
    return exclusiveRoomUse;
  }

  public void setExclusiveRoomUse(boolean exclusiveRoomUse) {
    this.exclusiveRoomUse = exclusiveRoomUse;
  }

  public PriceCategoryMode getPriceCategoryMode() {
    return priceCategoryMode;
  }

  public PriceType getPriceType() {
    return priceType;
  }

  public boolean isDoNotMove() {
    return doNotMove;
  }

  public void setDoNotMove(boolean doNotMove) {
    this.doNotMove = doNotMove;
  }

  public boolean isEarlyCheckIn() {
    return earlyCheckIn;
  }

  public void setEarlyCheckIn(boolean earlyCheckIn) {
    this.earlyCheckIn = earlyCheckIn;
  }

  public boolean isLateCheckOut() {
    return lateCheckOut;
  }

  public void setLateCheckOut(boolean lateCheckOut) {
    this.lateCheckOut = lateCheckOut;
  }

  public int getAdults() {
    return adults;
  }

  public int getChildren() {
    return children;
  }

  public Instant getCheckedInAt() {
    return checkedInAt;
  }

  public Instant getCheckedOutAt() {
    return checkedOutAt;
  }

  public Instant getCancelledAt() {
    return cancelledAt;
  }

  public String getCancelReason() {
    return cancelReason;
  }

  public void checkIn(Instant now) {
    status = ReservationStatus.CHECKED_IN;
    checkedInAt = now;
  }

  public void undoCheckIn() {
    status = ReservationStatus.RESERVED;
    checkedInAt = null;
  }

  public void checkOut(Instant now) {
    status = ReservationStatus.CHECKED_OUT;
    checkedOutAt = now;
  }

  public void cancel(Instant now, String reason) {
    status = ReservationStatus.CANCELLED;
    cancelledAt = now;
    cancelReason = reason;
  }

  public void markNoShow() {
    status = ReservationStatus.NO_SHOW;
  }

  public void revertNoShow() {
    status = ReservationStatus.RESERVED;
  }
}
