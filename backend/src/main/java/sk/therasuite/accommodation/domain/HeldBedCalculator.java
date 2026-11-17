package sk.therasuite.accommodation.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import sk.therasuite.accommodation.domain.OccupancyModel.Bed;
import sk.therasuite.accommodation.domain.OccupancyModel.ExistingStay;
import sk.therasuite.catalog.domain.BedType;

/**
 * Držané lôžka (HLD kap. 3): neobsadené lôžka izby, ktoré nemožno predať, lebo izbu užíva výhradne
 * karta so samostatným bývaním. Vo výsledku sú len aktívne riadne lôžka ({@code REGULAR});
 * prístelky sa nezobrazujú. Nie je to pobyt, výsledok sa len dopočíta pre plán.
 */
public final class HeldBedCalculator {

  /** Držané lôžko {@code bedId} počas {@code period} kvôli karte {@code reservationId}. */
  public record HeldBed(long bedId, StayPeriod period, long reservationId) {}

  /**
   * @param roomBeds lôžka danej izby
   * @param roomStays pobyty na lôžkach tejto izby (aj mimo intervalu, filtruje sa tu)
   */
  public List<HeldBed> calculate(
      List<Bed> roomBeds, List<ExistingStay> roomStays, StayPeriod interval) {
    List<HeldBed> raw = new ArrayList<>();
    for (ExistingStay s : roomStays) {
      if (!s.exclusiveRoomUse()) {
        continue;
      }
      var clipped = s.period().intersection(interval);
      if (clipped.isEmpty()) {
        continue;
      }
      for (Bed bed : roomBeds) {
        if (bed.active() && bed.type() == BedType.REGULAR && bed.id() != s.bedId()) {
          raw.add(new HeldBed(bed.id(), clipped.get(), s.reservationId()));
        }
      }
    }
    return merge(raw);
  }

  /** Spojí na seba nadväzujúce úseky toho istého lôžka a karty (sťahovanie karty v izbe). */
  private static List<HeldBed> merge(List<HeldBed> raw) {
    List<HeldBed> result = new ArrayList<>();
    Map<String, List<HeldBed>> groups =
        raw.stream().collect(Collectors.groupingBy(h -> h.bedId() + ":" + h.reservationId()));
    for (List<HeldBed> group : groups.values()) {
      group.sort(Comparator.comparing(h -> h.period().from()));
      HeldBed current = group.get(0);
      for (HeldBed next : group.subList(1, group.size())) {
        if (!next.period().from().isAfter(current.period().to())) {
          var end =
              next.period().to().isAfter(current.period().to())
                  ? next.period().to()
                  : current.period().to();
          current =
              new HeldBed(current.bedId(), current.period().withEnd(end), current.reservationId());
        } else {
          result.add(current);
          current = next;
        }
      }
      result.add(current);
    }
    result.sort(
        Comparator.comparing((HeldBed h) -> h.bedId()).thenComparing(h -> h.period().from()));
    return result;
  }
}
