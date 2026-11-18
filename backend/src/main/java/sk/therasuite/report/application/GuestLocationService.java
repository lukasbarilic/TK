package sk.therasuite.report.application;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sk.therasuite.businessday.application.BusinessDateProvider;
import sk.therasuite.iam.application.PermissionChecker;
import sk.therasuite.iam.domain.Permissions;
import sk.therasuite.report.infrastructure.GuestLocationQueries;
import sk.therasuite.report.infrastructure.GuestLocationQueries.LocationRow;
import sk.therasuite.shared.AuditedRead;
import sk.therasuite.shared.AuditedReadResult;
import sk.therasuite.shared.DataScope;
import sk.therasuite.shared.api.ConditionalGet;
import sk.therasuite.shared.application.ApiException;

/** Zoznam hostí s miestom ubytovania k danému dňu (HLD kap. 8.5). */
@Service
public class GuestLocationService {

  /** Riadok zoznamu; {@code movedOn} je vyplnený, ak karta v ten deň zmenila izbu. */
  public record GuestLocation(
      long reservationId,
      String guestName,
      Integer birthYear,
      String building,
      String floor,
      String room,
      Integer bedNumber,
      String bedType,
      LocalDate arrivalDate,
      LocalDate departureDate,
      String status,
      Long doctorId,
      String doctorName,
      LocalDate stayFrom,
      LocalDate stayTo,
      LocalDate movedOn) {}

  public record GuestLocations(LocalDate date, List<GuestLocation> guests) {}

  private static final String[] HEADER = {
    "Priezvisko a meno", "Rok narodenia", "Budova", "Poschodie", "Izba", "Lôžko", "Typ lôžka",
    "Príchod", "Odchod", "Stav karty", "Ošetrujúci lekár", "Pobyt od", "Pobyt do", "Presun dňa"
  };

  private final GuestLocationQueries queries;
  private final PermissionChecker permissions;
  private final BusinessDateProvider businessDate;

  public GuestLocationService(
      GuestLocationQueries queries,
      PermissionChecker permissions,
      BusinessDateProvider businessDate) {
    this.queries = queries;
    this.permissions = permissions;
    this.businessDate = businessDate;
  }

  /** Odtlačok pre ETag; kontroluje oprávnenie ako {@link #list}, nič nečíta ani neauditúje. */
  @Transactional(readOnly = true)
  public String etag(long userId, LocalDate date, Long centreId, Long buildingId, Long doctorId) {
    permissions.require(Permissions.GUEST_LOCATION_VIEW);
    Long effectiveDoctor = effectiveDoctor(userId, doctorId);
    return ConditionalGet.etag(
        "guest-locations",
        userId,
        resolve(userId, date),
        centreId,
        buildingId,
        effectiveDoctor,
        queries.changeToken());
  }

  @AuditedRead(operation = "GUEST_LOCATION_VIEW", scope = DataScope.NAME)
  @Transactional(readOnly = true)
  public AuditedReadResult<GuestLocations> list(
      long userId, LocalDate date, Long centreId, Long buildingId, Long doctorId) {
    permissions.require(Permissions.GUEST_LOCATION_VIEW);
    return load(userId, date, centreId, buildingId, doctorId);
  }

  /** CSV: UTF-8 s BOM a oddeľovačom {@code ;} (otvorí sa správne v Exceli). */
  @AuditedRead(operation = "GUEST_LOCATION_EXPORT", scope = DataScope.NAME)
  @Transactional(readOnly = true)
  public AuditedReadResult<byte[]> exportCsv(
      long userId, LocalDate date, Long centreId, Long buildingId, Long doctorId) {
    permissions.require(Permissions.GUEST_LOCATION_VIEW);
    permissions.require(Permissions.GUEST_LOCATION_EXPORT);
    AuditedReadResult<GuestLocations> data = load(userId, date, centreId, buildingId, doctorId);
    return new AuditedReadResult<>(csv(data.value().guests()), data.clientIds());
  }

  private AuditedReadResult<GuestLocations> load(
      long userId, LocalDate date, Long centreId, Long buildingId, Long doctorId) {
    Long effectiveDoctor = effectiveDoctor(userId, doctorId);
    LocalDate day = resolve(userId, date);
    List<LocationRow> rows = queries.find(day, centreId, buildingId, effectiveDoctor);
    Map<Long, Long> stays =
        rows.stream()
            .filter(r -> r.stayFrom() != null)
            .collect(Collectors.groupingBy(LocationRow::reservationId, Collectors.counting()));
    Set<Long> clientIds = new LinkedHashSet<>();
    List<GuestLocation> guests = new ArrayList<>();
    for (LocationRow r : rows) {
      if (r.clientId() != null) {
        clientIds.add(r.clientId());
      }
      boolean moved = stays.getOrDefault(r.reservationId(), 0L) > 1;
      guests.add(view(r, moved ? day : null));
    }
    return new AuditedReadResult<>(new GuestLocations(day, guests), clientIds);
  }

  /** Bez VIEW_ALL vidí lekár len svojich pacientov; cudzieho lekára si nemôže vypýtať. */
  private Long effectiveDoctor(long userId, Long doctorId) {
    if (permissions.hasAny(Permissions.GUEST_LOCATION_VIEW_ALL)) {
      return doctorId;
    }
    if (doctorId != null && doctorId != userId) {
      throw new AccessDeniedException("Chýba oprávnenie " + Permissions.GUEST_LOCATION_VIEW_ALL);
    }
    return userId;
  }

  /** Zadaný dátum, inak obchodný deň hlavnej recepcie používateľa. */
  private LocalDate resolve(long userId, LocalDate date) {
    if (date != null) {
      return date;
    }
    Long reception = queries.defaultReceptionId(userId);
    if (reception == null) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "NO_DEFAULT_RECEPTION",
          "Zadajte dátum, používateľ nemá nastavenú hlavnú recepciu.");
    }
    return businessDate.current(reception);
  }

  private static GuestLocation view(LocationRow r, LocalDate movedOn) {
    String name =
        ((r.lastName() == null ? "" : r.lastName())
                + " "
                + (r.firstName() == null ? "" : r.firstName()))
            .trim();
    return new GuestLocation(
        r.reservationId(),
        name,
        r.birthYear(),
        r.building(),
        r.floor(),
        r.room(),
        r.bedNumber(),
        r.bedType(),
        r.arrivalDate(),
        r.departureDate(),
        r.status(),
        r.doctorId(),
        r.doctorName(),
        r.stayFrom(),
        r.stayTo(),
        movedOn);
  }

  static byte[] csv(List<GuestLocation> guests) {
    StringBuilder sb = new StringBuilder("﻿");
    sb.append(String.join(";", HEADER)).append("\r\n");
    for (GuestLocation g : guests) {
      String[] cells = {
        g.guestName(),
        str(g.birthYear()),
        g.building(),
        g.floor(),
        g.room(),
        str(g.bedNumber()),
        g.bedType(),
        str(g.arrivalDate()),
        str(g.departureDate()),
        g.status(),
        g.doctorName(),
        str(g.stayFrom()),
        str(g.stayTo()),
        str(g.movedOn())
      };
      for (int i = 0; i < cells.length; i++) {
        if (i > 0) {
          sb.append(';');
        }
        sb.append(cell(cells[i]));
      }
      sb.append("\r\n");
    }
    return sb.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static String str(Object o) {
    return o == null ? "" : o.toString();
  }

  /** Uvodzovky pri oddeľovači a zalomení; úvodný vzorec (=, +, -, @) sa neutralizuje. */
  private static String cell(String v) {
    if (v == null || v.isEmpty()) {
      return "";
    }
    String s = v;
    char c = s.charAt(0);
    if (c == '=' || c == '+' || c == '-' || c == '@') {
      s = "'" + s;
    }
    if (s.contains(";") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
      s = "\"" + s.replace("\"", "\"\"") + "\"";
    }
    return s;
  }
}
