package sk.therasuite.accommodation.application;

import java.time.LocalDate;
import java.util.List;

/** Výstupné tvary plánu ubytovania (HLD kap. 6.2). */
public final class PlanViews {

  private PlanViews() {}

  /** Pásik v pláne; {@code type} je STAY, HELD_BED alebo ROOM_BLOCK. */
  public sealed interface Bar permits StayBar, HeldBedBar, RoomBlockBar {}

  /**
   * Pásik pobytu. {@code from}/{@code to} sú orezané na zobrazené obdobie, {@code stayFrom}/{@code
   * stayTo} sú skutočné dátumy pobytu. {@code label} je skrátené meno „Priezvisko M.“.
   */
  public record StayBar(
      String type,
      long stayId,
      long reservationId,
      long bedId,
      LocalDate from,
      LocalDate to,
      LocalDate stayFrom,
      LocalDate stayTo,
      String label,
      String status,
      boolean locked,
      List<String> flags,
      Long partyId)
      implements Bar {}

  /** Držané lôžko (dopočítané, v databáze nie je); drží ho karta s výhradným užívaním izby. */
  public record HeldBedBar(
      String type, Long byStayId, long byReservationId, long bedId, LocalDate from, LocalDate to)
      implements Bar {}

  /**
   * Blokácia izby; {@code from}/{@code to} sú orezané, {@code blockFrom}/{@code blockTo} skutočné.
   */
  public record RoomBlockBar(
      String type,
      long blockId,
      String reason,
      String note,
      LocalDate from,
      LocalDate to,
      LocalDate blockFrom,
      LocalDate blockTo)
      implements Bar {}

  public record CategoryRef(long id, String code) {}

  public record BedView(long bedId, int number, String type) {}

  public record PlanRoom(
      long roomId,
      long buildingId,
      String building,
      String number,
      String floor,
      CategoryRef category,
      List<BedView> beds,
      List<Bar> bars) {}

  /** Aktuálny obchodný deň recepcie ({@code null}, ak recepcia nemá otvorený deň). */
  public record ReceptionDay(long receptionId, LocalDate businessDate) {}

  public record Plan(
      LocalDate from, LocalDate to, List<ReceptionDay> receptions, List<PlanRoom> rooms) {}
}
