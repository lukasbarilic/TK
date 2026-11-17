package sk.therasuite.reservation.infrastructure;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import sk.therasuite.shared.BaseEntity;

@Entity
@Table(name = "reservation_guest")
public class ReservationGuest extends BaseEntity {

  private Long reservationId;
  private Long clientId;
  private boolean primaryGuest;
  private boolean cancelled;

  protected ReservationGuest() {}

  public ReservationGuest(Long reservationId, Long clientId, boolean primaryGuest) {
    this.reservationId = reservationId;
    this.clientId = clientId;
    this.primaryGuest = primaryGuest;
  }

  public Long getReservationId() {
    return reservationId;
  }

  public Long getClientId() {
    return clientId;
  }

  public boolean isPrimaryGuest() {
    return primaryGuest;
  }

  public boolean isCancelled() {
    return cancelled;
  }
}
