package sk.therasuite.reservation.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sk.therasuite.accommodation.application.ReservationStayGateway;
import sk.therasuite.reservation.application.ReservationViews.CommentView;
import sk.therasuite.reservation.application.ReservationViews.Detail;
import sk.therasuite.reservation.application.ReservationViews.GuestView;
import sk.therasuite.reservation.application.ReservationViews.ListItem;
import sk.therasuite.reservation.application.ReservationViews.PageView;
import sk.therasuite.reservation.domain.ReservationStatus;
import sk.therasuite.reservation.domain.StayPeriod;
import sk.therasuite.reservation.infrastructure.Client;
import sk.therasuite.reservation.infrastructure.ClientRepository;
import sk.therasuite.reservation.infrastructure.Reservation;
import sk.therasuite.reservation.infrastructure.ReservationGuest;
import sk.therasuite.reservation.infrastructure.ReservationGuestRepository;
import sk.therasuite.reservation.infrastructure.ReservationQueries;
import sk.therasuite.reservation.infrastructure.ReservationQueries.CommentRow;
import sk.therasuite.reservation.infrastructure.ReservationQueries.Result;
import sk.therasuite.reservation.infrastructure.ReservationQueries.Row;
import sk.therasuite.reservation.infrastructure.ReservationRepository;
import sk.therasuite.shared.AuditedOperation;
import sk.therasuite.shared.AuditedRead;
import sk.therasuite.shared.AuditedReadResult;
import sk.therasuite.shared.DataScope;
import sk.therasuite.shared.application.ApiException;

/**
 * Rezervačné karty: vyhľadávanie, detail, založenie a úprava (oprávnenia kontroluje controller).
 */
@Service
public class ReservationService {

  static final int MAX_PAGE_SIZE = 100;
  public static final int MAX_NOTE_LENGTH = 2000;

  /** Počet komentárov v detaile karty (ďalšie sú dostupné cez endpoint komentárov). */
  public static final int DETAIL_COMMENTS = 20;

  /** Hosť pri zakladaní karty: buď existujúci klient ({@code clientId}), alebo nový klient. */
  public record GuestInput(
      Long clientId,
      String firstName,
      String lastName,
      LocalDate birthDate,
      String nationality,
      String email,
      String phone) {}

  public record CreateCommand(
      LocalDate arrivalDate,
      LocalDate departureDate,
      Long bookedCategoryId,
      Long payerId,
      int adults,
      int children,
      boolean exclusiveRoomUse,
      List<GuestInput> guests,
      Long attendingDoctorId,
      boolean earlyCheckIn,
      boolean lateCheckOut,
      boolean doNotMove,
      String note,
      boolean noteInPlan) {

    /** Založenie bez lekára, príznakov a poznámky. */
    public CreateCommand(
        LocalDate arrivalDate,
        LocalDate departureDate,
        Long bookedCategoryId,
        Long payerId,
        int adults,
        int children,
        boolean exclusiveRoomUse,
        List<GuestInput> guests) {
      this(
          arrivalDate,
          departureDate,
          bookedCategoryId,
          payerId,
          adults,
          children,
          exclusiveRoomUse,
          guests,
          null,
          false,
          false,
          false,
          null,
          false);
    }
  }

  /** Neuvedené (null) polia sa nemenia; {@code version} je verzia, z ktorej klient vychádzal. */
  public record UpdateCommand(
      long version,
      LocalDate arrivalDate,
      LocalDate departureDate,
      Long bookedCategoryId,
      Long payerId,
      Boolean doNotMove,
      Boolean earlyCheckIn,
      Boolean lateCheckOut,
      Boolean exclusiveRoomUse,
      String note,
      Boolean noteInPlan) {

    /** Úprava bez zmeny poznámky. */
    public UpdateCommand(
        long version,
        LocalDate arrivalDate,
        LocalDate departureDate,
        Long bookedCategoryId,
        Long payerId,
        Boolean doNotMove,
        Boolean earlyCheckIn,
        Boolean lateCheckOut,
        Boolean exclusiveRoomUse) {
      this(
          version,
          arrivalDate,
          departureDate,
          bookedCategoryId,
          payerId,
          doNotMove,
          earlyCheckIn,
          lateCheckOut,
          exclusiveRoomUse,
          null,
          null);
    }
  }

  private final ReservationRepository reservations;
  private final ReservationGuestRepository guests;
  private final ClientRepository clients;
  private final ReservationQueries queries;
  private final ReservationStayGateway stays;

  public ReservationService(
      ReservationRepository reservations,
      ReservationGuestRepository guests,
      ClientRepository clients,
      ReservationQueries queries,
      ReservationStayGateway stays) {
    this.reservations = reservations;
    this.guests = guests;
    this.clients = clients;
    this.queries = queries;
    this.stays = stays;
  }

  @AuditedRead(operation = "RESERVATION_VIEW", scope = DataScope.NAME)
  @Transactional(readOnly = true)
  public AuditedReadResult<PageView<ListItem>> search(
      String q,
      ReservationStatus status,
      LocalDate from,
      LocalDate to,
      boolean withoutStay,
      int page,
      int size) {
    if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
      throw badRequest("Stránkovanie: page ≥ 0 a size 1–" + MAX_PAGE_SIZE + ".");
    }
    if (from != null && to != null && !from.isBefore(to)) {
      throw badRequest("Parameter 'from' musí byť pred 'to'.");
    }
    Result result = queries.search(q, status, from, to, withoutStay, page, size);
    List<ListItem> items =
        result.rows().stream()
            .map(r ->
                new ListItem(
                    r.id(),
                    r.reservationNumber(),
                    r.guestFirstName(),
                    r.guestLastName(),
                    r.guestBirthYear(),
                    r.arrivalDate(),
                    r.departureDate(),
                    r.status(),
                    r.bookedCategoryId(),
                    r.bookedCategoryName()))
            .toList();
    List<Long> clientIds =
        result.rows().stream().map(Row::guestClientId).filter(Objects::nonNull).toList();
    return new AuditedReadResult<>(new PageView<>(items, page, size, result.total()), clientIds);
  }

  @AuditedRead(operation = "RESERVATION_VIEW", scope = DataScope.CONTACT)
  @Transactional(readOnly = true)
  public AuditedReadResult<Detail> get(long id) {
    Detail detail = detail(load(id));
    return new AuditedReadResult<>(
        detail, detail.guests().stream().map(GuestView::clientId).toList());
  }

  @AuditedOperation(operation = "RESERVATION_CREATE", useCase = "UC1")
  @Transactional
  public Detail create(CreateCommand cmd) {
    validatePeriod(cmd.arrivalDate(), cmd.departureDate());
    if (cmd.adults() < 1) {
      throw badRequest("Počet dospelých musí byť aspoň 1.");
    }
    if (cmd.children() < 0) {
      throw badRequest("Počet detí nemôže byť záporný.");
    }
    if (cmd.guests() == null || cmd.guests().isEmpty()) {
      throw badRequest("Karta musí mať aspoň jedného hosťa.");
    }
    checkReferences(cmd.bookedCategoryId(), cmd.payerId());
    String note = normalizeNote(cmd.note());
    if (cmd.attendingDoctorId() != null && !queries.doctorExists(cmd.attendingDoctorId())) {
      throw badRequest("Lekár neexistuje alebo nemá rolu lekára.");
    }

    List<Client> resolved = new ArrayList<>();
    Set<Long> seen = new HashSet<>();
    for (GuestInput g : cmd.guests()) {
      Client client = resolveClient(g);
      if (client.getId() != null && !seen.add(client.getId())) {
        throw badRequest("Ten istý klient je na karte viackrát.");
      }
      resolved.add(client);
    }

    Reservation card =
        new Reservation(
            queries.nextReservationNumber(),
            cmd.arrivalDate(),
            cmd.departureDate(),
            cmd.bookedCategoryId(),
            cmd.payerId(),
            cmd.adults(),
            cmd.children(),
            cmd.exclusiveRoomUse());
    card.setAttendingDoctorId(cmd.attendingDoctorId());
    card.setEarlyCheckIn(cmd.earlyCheckIn());
    card.setLateCheckOut(cmd.lateCheckOut());
    card.setDoNotMove(cmd.doNotMove());
    card.setNote(note);
    card.setNoteInPlan(cmd.noteInPlan());
    Reservation reservation = reservations.saveAndFlush(card);
    for (int i = 0; i < resolved.size(); i++) {
      Client client = resolved.get(i);
      if (client.getId() == null) {
        client = clients.saveAndFlush(client);
      }
      guests.save(new ReservationGuest(reservation.getId(), client.getId(), i == 0));
    }
    guests.flush();
    return detail(reservation);
  }

  @AuditedOperation(operation = "RESERVATION_UPDATE", useCase = "UC1")
  @Transactional
  public Detail update(long id, UpdateCommand cmd) {
    Reservation r = load(id);
    if (r.getVersion() != cmd.version()) {
      throw conflict();
    }
    LocalDate arrival = cmd.arrivalDate() != null ? cmd.arrivalDate() : r.getArrivalDate();
    LocalDate departure = cmd.departureDate() != null ? cmd.departureDate() : r.getDepartureDate();
    validatePeriod(arrival, departure);
    checkReferences(
        cmd.bookedCategoryId() != null ? cmd.bookedCategoryId() : r.getBookedCategoryId(),
        cmd.payerId() != null ? cmd.payerId() : r.getPayerId());

    r.setArrivalDate(arrival);
    r.setDepartureDate(departure);
    if (cmd.bookedCategoryId() != null) {
      r.setBookedCategoryId(cmd.bookedCategoryId());
    }
    if (cmd.payerId() != null) {
      r.setPayerId(cmd.payerId());
    }
    if (cmd.doNotMove() != null) {
      r.setDoNotMove(cmd.doNotMove());
    }
    if (cmd.earlyCheckIn() != null) {
      r.setEarlyCheckIn(cmd.earlyCheckIn());
    }
    if (cmd.lateCheckOut() != null) {
      r.setLateCheckOut(cmd.lateCheckOut());
    }
    // výhradné užívanie mení držané lôžka v pláne, preto treba plán obnoviť aj ostatným
    boolean exclusiveChanged =
        cmd.exclusiveRoomUse() != null && cmd.exclusiveRoomUse() != r.isExclusiveRoomUse();
    if (cmd.exclusiveRoomUse() != null) {
      r.setExclusiveRoomUse(cmd.exclusiveRoomUse());
    }
    boolean planNoteChanged = false;
    if (cmd.note() != null) {
      String note = normalizeNote(cmd.note());
      planNoteChanged = !Objects.equals(note, r.getNote());
      r.setNote(note);
    }
    if (cmd.noteInPlan() != null) {
      planNoteChanged |= cmd.noteInPlan() != r.isNoteInPlan();
      r.setNoteInPlan(cmd.noteInPlan());
    }
    Reservation saved = reservations.saveAndFlush(r);
    if (planNoteChanged || exclusiveChanged) {
      stays.publishPlanChanged(stays.snapshot(id));
    }
    return detail(saved);
  }

  private static String normalizeNote(String raw) {
    String note = raw == null || raw.isBlank() ? null : raw.strip();
    if (note != null && note.length() > MAX_NOTE_LENGTH) {
      throw badRequest("Poznámka môže mať najviac " + MAX_NOTE_LENGTH + " znakov.");
    }
    return note;
  }

  private Client resolveClient(GuestInput g) {
    if (g.clientId() != null) {
      return clients
          .findById(g.clientId())
          .orElseThrow(() -> badRequest("Klient " + g.clientId() + " neexistuje."));
    }
    if (isBlank(g.firstName()) || isBlank(g.lastName())) {
      throw badRequest("Nový klient musí mať meno a priezvisko (alebo uveďte clientId).");
    }
    return new Client(
        g.firstName().trim(),
        g.lastName().trim(),
        g.birthDate(),
        g.nationality(),
        g.email(),
        g.phone());
  }

  private void checkReferences(Long categoryId, Long payerId) {
    if (categoryId == null || !queries.categoryExists(categoryId)) {
      throw badRequest("Kategória izby neexistuje.");
    }
    if (payerId == null || !queries.payerExists(payerId)) {
      throw badRequest("Platiteľ neexistuje.");
    }
  }

  private static void validatePeriod(LocalDate arrival, LocalDate departure) {
    String violation = StayPeriod.violation(arrival, departure);
    if (violation != null) {
      throw badRequest(violation);
    }
  }

  Reservation load(long id) {
    return reservations
        .findById(id)
        .orElseThrow(
            () -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Karta neexistuje."));
  }

  /**
   * Detail viacerých kariet naraz (offline režim): štyri dotazy bez ohľadu na počet kariet.
   * Neexistujúce ID sa vynechajú, výsledok je zoradený podľa ID.
   */
  @AuditedRead(operation = "RESERVATION_VIEW", scope = DataScope.CONTACT)
  @Transactional(readOnly = true)
  public AuditedReadResult<List<Detail>> getAll(Collection<Long> ids) {
    if (ids.isEmpty()) {
      return new AuditedReadResult<>(List.of(), List.of());
    }
    List<Reservation> found =
        reservations.findAllById(ids).stream()
            .sorted(Comparator.comparing(Reservation::getId))
            .toList();
    Map<Long, List<ReservationGuest>> guestsByCard =
        guests
            .findByReservationIdInOrderById(found.stream().map(Reservation::getId).toList())
            .stream()
            .collect(Collectors.groupingBy(ReservationGuest::getReservationId));
    Map<Long, Client> clientsById =
        clients
            .findAllById(
                guestsByCard.values().stream()
                    .flatMap(List::stream)
                    .map(ReservationGuest::getClientId)
                    .collect(Collectors.toSet()))
            .stream()
            .collect(Collectors.toMap(Client::getId, Function.identity()));
    Map<Long, List<CommentView>> commentsByCard =
        queries
            .lastComments(found.stream().map(Reservation::getId).toList(), DETAIL_COMMENTS)
            .stream()
            .collect(
                Collectors.groupingBy(
                    CommentRow::reservationId,
                    Collectors.mapping(ReservationService::commentView, Collectors.toList())));
    List<Detail> details =
        found.stream()
            .map(r ->
                detail(
                    r,
                    guestsByCard.getOrDefault(r.getId(), List.of()),
                    clientsById::get,
                    commentsByCard.getOrDefault(r.getId(), List.of())))
            .toList();
    return new AuditedReadResult<>(
        details,
        details.stream()
            .flatMap(d -> d.guests().stream())
            .map(GuestView::clientId)
            .distinct()
            .toList());
  }

  /** Detail karty pre ostatné služby modulu (po zmene stavu). */
  Detail detail(Reservation r) {
    return detail(
        r,
        guests.findByReservationIdOrderById(r.getId()),
        clients::getReferenceById,
        queries.lastComments(List.of(r.getId()), DETAIL_COMMENTS).stream()
            .map(ReservationService::commentView)
            .toList());
  }

  static CommentView commentView(CommentRow c) {
    return new CommentView(c.id(), c.authorId(), c.createdAt(), c.text());
  }

  private Detail detail(
      Reservation r,
      List<ReservationGuest> reservationGuests,
      Function<Long, Client> client,
      List<CommentView> comments) {
    List<GuestView> views =
        reservationGuests.stream()
            .map(g -> {
              Client c = client.apply(g.getClientId());
              return new GuestView(
                  g.getId(),
                  c.getId(),
                  c.getFirstName(),
                  c.getLastName(),
                  c.getBirthDate(),
                  c.getNationality(),
                  c.getEmail(),
                  c.getPhone(),
                  g.isPrimaryGuest(),
                  g.isCancelled());
            })
            .toList();
    return new Detail(
        r.getId(),
        r.getReservationNumber(),
        r.getStatus(),
        r.getArrivalDate(),
        r.getDepartureDate(),
        r.getBookedCategoryId(),
        r.getPayerId(),
        r.getGroupId(),
        r.getPartyId(),
        r.isExclusiveRoomUse(),
        r.getPriceCategoryMode(),
        r.getPriceType(),
        r.isDoNotMove(),
        r.isEarlyCheckIn(),
        r.isLateCheckOut(),
        r.getAdults(),
        r.getChildren(),
        r.getVersion(),
        r.getCheckedInAt(),
        r.getCheckedOutAt(),
        r.getCancelledAt(),
        r.getCancelReason(),
        r.getNote(),
        r.isNoteInPlan(),
        comments,
        views);
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  private static ApiException badRequest(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
  }

  private static ApiException conflict() {
    return new ApiException(
        HttpStatus.CONFLICT,
        "CONFLICT",
        "Záznam medzitým zmenil iný používateľ. Načítajte ho znova.");
  }
}
