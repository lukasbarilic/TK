package sk.therasuite.accommodation.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sk.therasuite.accommodation.infrastructure.AccommodationQueries;
import sk.therasuite.accommodation.infrastructure.AccommodationQueries.StayRef;
import sk.therasuite.accommodation.infrastructure.Stay;
import sk.therasuite.accommodation.infrastructure.StayRepository;
import sk.therasuite.audit.application.AuditService;
import sk.therasuite.businessday.application.BusinessDateProvider;
import sk.therasuite.notification.application.LiveEvent;
import sk.therasuite.shared.application.ApiException;

/**
 * Pobyty karty z pohľadu zmeny jej stavu (príchod, odchod, storno, neprišiel): kto má lôžko, aký je
 * obchodný deň, uvoľnenie pobytov a živá udalosť pre dotknuté izby.
 */
@Service
public class ReservationStayGateway {

  /** Pobyty karty v čase pred zmenou stavu a počet pridelených lôžok. */
  public record Snapshot(List<StayRef> stays, int assignedBeds, int activeGuests) {

    public Set<Long> receptionIds() {
      return stays.stream().map(StayRef::receptionId).collect(Collectors.toSet());
    }
  }

  private final AccommodationQueries queries;
  private final StayRepository stays;
  private final BusinessDateProvider businessDates;
  private final ApplicationEventPublisher events;

  public ReservationStayGateway(
      AccommodationQueries queries,
      StayRepository stays,
      BusinessDateProvider businessDates,
      ApplicationEventPublisher events) {
    this.queries = queries;
    this.stays = stays;
    this.businessDates = businessDates;
    this.events = events;
  }

  @Transactional(readOnly = true)
  public Snapshot snapshot(long reservationId) {
    return new Snapshot(
        queries.stayRefsOfReservation(reservationId),
        queries.assignedBedCount(reservationId),
        queries.activeGuestCount(reservationId));
  }

  /**
   * Obchodný deň karty: neskorší z obchodných dní recepcií jej pobytov. Karta bez pobytov nemá
   * recepciu, preto sa berie neskorší z otvorených obchodných dní všetkých recepcií.
   */
  @Transactional(readOnly = true)
  public LocalDate businessDate(Snapshot snapshot) {
    List<Long> receptions =
        snapshot.stays().isEmpty()
            ? queries.receptionIds()
            : new ArrayList<>(snapshot.receptionIds());
    LocalDate latest = null;
    for (long reception : receptions) {
      try {
        LocalDate day = businessDates.current(reception);
        if (latest == null || day.isAfter(latest)) {
          latest = day;
        }
      } catch (ApiException e) {
        if (!snapshot.stays().isEmpty()) {
          throw e;
        }
      }
    }
    if (latest == null) {
      throw new ApiException(
          HttpStatus.CONFLICT, "NO_BUSINESS_DAY", "Nie je otvorený žiadny obchodný deň.");
    }
    return latest;
  }

  /**
   * Uvoľní pobyty od obchodného dňa ich recepcie: pobyt, ktorý sa začína v tento deň alebo neskôr,
   * zmaže; pobyt, ktorý už trvá, skráti tak, aby sa končil v obchodný deň.
   */
  @Transactional
  public void releaseFromBusinessDay(Snapshot snapshot) {
    Map<Long, LocalDate> dayByReception = new HashMap<>();
    for (StayRef ref : snapshot.stays()) {
      LocalDate day = dayByReception.computeIfAbsent(ref.receptionId(), businessDates::current);
      if (!ref.dateTo().isAfter(day)) {
        continue;
      }
      if (!ref.dateFrom().isBefore(day)) {
        stays.deleteById(ref.id());
      } else {
        Stay stay = stays.findById(ref.id()).orElseThrow();
        stay.setDateTo(day);
        stays.save(stay);
      }
    }
    stays.flush();
  }

  /**
   * Pošle {@code plan-changed} pre izby pobytov viacerých kariet naraz (napr. po oprave údajov
   * klienta, ktorý je na viacerých kartách). Karty bez pobytov sa preskočia.
   */
  public void publishPlanChangedForReservations(Collection<Long> reservationIds) {
    List<StayRef> refs = new ArrayList<>();
    for (long reservationId : reservationIds) {
      refs.addAll(queries.stayRefsOfReservation(reservationId));
    }
    if (!refs.isEmpty()) {
      publishPlanChanged(new Snapshot(refs, 0, 0));
    }
  }

  /**
   * Pošle {@code plan-changed} pre izby pobytov karty (po COMMIT), po jednej udalosti na recepciu.
   */
  public void publishPlanChanged(Snapshot snapshot) {
    Map<Long, List<StayRef>> byReception = new LinkedHashMap<>();
    for (StayRef ref : snapshot.stays()) {
      byReception.computeIfAbsent(ref.receptionId(), k -> new ArrayList<>()).add(ref);
    }
    Long auditEventId = AuditService.currentEventId();
    byReception.forEach((reception, refs) ->
        events.publishEvent(
            new LiveEvent.PlanChanged(
                reception,
                refs.stream().map(StayRef::roomId).distinct().toList(),
                refs.stream().map(StayRef::dateFrom).min(LocalDate::compareTo).orElseThrow(),
                refs.stream().map(StayRef::dateTo).max(LocalDate::compareTo).orElseThrow(),
                auditEventId)));
  }
}
