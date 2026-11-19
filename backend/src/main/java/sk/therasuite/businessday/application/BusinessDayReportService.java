package sk.therasuite.businessday.application;

import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sk.therasuite.businessday.application.BusinessDayReport.Arrivals;
import sk.therasuite.businessday.application.BusinessDayReport.BedStats;
import sk.therasuite.businessday.application.BusinessDayReport.BuildingOccupancy;
import sk.therasuite.businessday.application.BusinessDayReport.Departures;
import sk.therasuite.businessday.application.BusinessDayReport.ForecastDay;
import sk.therasuite.businessday.application.BusinessDayReport.InHouse;
import sk.therasuite.businessday.application.BusinessDayReport.Occupancy;
import sk.therasuite.businessday.infrastructure.BusinessDayReportQueries;
import sk.therasuite.businessday.infrastructure.BusinessDayReportQueries.Archived;
import sk.therasuite.businessday.infrastructure.BusinessDayReportQueries.BuildingBeds;
import sk.therasuite.businessday.infrastructure.BusinessDayReportQueries.DayBeds;
import sk.therasuite.businessday.infrastructure.BusinessDayReportQueries.PersonCounts;
import sk.therasuite.shared.application.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Výpočet reportu dňa a čítanie archívu; oprávnenia kontroluje controller. */
@Service
public class BusinessDayReportService {

  static final int FORECAST_DAYS = 14;

  /** Položka zoznamu archívu (bez obsahu reportu). */
  public record ArchiveItem(LocalDate businessDate, String closedAt, long closedBy) {}

  /** Detail archívu s uloženým reportom. */
  public record ArchiveDetail(
      LocalDate businessDate, String closedAt, long closedBy, JsonNode report) {}

  private final BusinessDayReportQueries queries;
  private final JsonMapper json;

  public BusinessDayReportService(BusinessDayReportQueries queries, JsonMapper json) {
    this.queries = queries;
    this.json = json;
  }

  @Transactional(readOnly = true)
  public BusinessDayReport build(long receptionId, LocalDate day) {
    PersonCounts c = queries.personCounts(receptionId, day);
    int endOfDay = c.inHouse();
    int startOfDay = endOfDay - c.arrivedStillInHouse() + c.departedToday();
    List<BuildingBeds> buildings = queries.occupancyByBuilding(receptionId, day);
    BedStats complex =
        stats(
            buildings.stream().mapToInt(BuildingBeds::beds).sum(),
            buildings.stream().mapToInt(BuildingBeds::occupied).sum(),
            buildings.stream().mapToInt(BuildingBeds::blocked).sum());
    List<ForecastDay> forecast =
        queries.forecast(receptionId, day, FORECAST_DAYS).stream()
            .map((DayBeds d) ->
                new ForecastDay(d.date(), stats(d.beds(), d.occupied(), d.blocked()).percent()))
            .toList();
    return new BusinessDayReport(
        day,
        new Arrivals(c.arrivalsExpected(), c.arrivalsArrived(), c.arrivalsNoShow()),
        new Departures(c.departuresExpected(), c.departuresDeparted()),
        new InHouse(startOfDay, endOfDay),
        new Occupancy(
            complex,
            buildings.stream()
                .map(b ->
                    new BuildingOccupancy(
                        b.buildingId(), b.name(), stats(b.beds(), b.occupied(), b.blocked())))
                .toList()),
        forecast);
  }

  static BedStats stats(int beds, int occupied, int blocked) {
    int usable = beds - blocked;
    double percent = usable <= 0 ? 0 : Math.round(occupied * 1000.0 / usable) / 10.0;
    return new BedStats(beds, occupied, blocked, Math.max(0, usable - occupied), percent);
  }

  @Transactional(readOnly = true)
  public List<ArchiveItem> list(long receptionId, LocalDate from, LocalDate to) {
    return queries.reports(receptionId, from, to).stream()
        .map(a -> new ArchiveItem(a.businessDate(), a.closedAt(), a.closedBy()))
        .toList();
  }

  @Transactional(readOnly = true)
  public ArchiveDetail detail(long receptionId, LocalDate day) {
    Archived a =
        queries
            .report(receptionId, day)
            .orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Report dňa sa nenašiel."));
    return new ArchiveDetail(
        a.businessDate(), a.closedAt(), a.closedBy(), json.readTree(a.report()));
  }
}
