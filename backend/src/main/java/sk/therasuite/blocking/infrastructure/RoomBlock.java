package sk.therasuite.blocking.infrastructure;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import sk.therasuite.shared.BaseEntity;

@Entity
@Table(name = "room_block")
public class RoomBlock extends BaseEntity {

  private Long roomId;
  private Long reasonId;
  private LocalDate dateFrom;
  private LocalDate dateTo;
  private String note;

  public Long getRoomId() {
    return roomId;
  }

  public Long getReasonId() {
    return reasonId;
  }

  public LocalDate getDateFrom() {
    return dateFrom;
  }

  public LocalDate getDateTo() {
    return dateTo;
  }

  public String getNote() {
    return note;
  }
}
