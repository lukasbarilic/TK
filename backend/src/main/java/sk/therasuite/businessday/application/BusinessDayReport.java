package sk.therasuite.businessday.application;

import java.time.LocalDate;
import java.util.List;

/** Report dňa (HLD kap. 5.8): len počty osôb a lôžok, bez mien hostí. */
public record BusinessDayReport(
    LocalDate businessDate,
    Arrivals arrivals,
    Departures departures,
    InHouse inHouse,
    Occupancy occupancy,
    List<ForecastDay> forecast) {

  public record Arrivals(int expectedPersons, int arrivedPersons, int noShowPersons) {}

  public record Departures(int expectedPersons, int departedPersons) {}

  public record InHouse(int startOfDay, int endOfDay) {}

  /** Obsadenosť lôžok; {@code percent} je obsadené / (lôžka − blokované) v %, na 1 desatinné. */
  public record BedStats(int beds, int occupied, int blocked, int free, double percent) {}

  public record BuildingOccupancy(long buildingId, String name, BedStats stats) {}

  public record Occupancy(BedStats complex, List<BuildingOccupancy> buildings) {}

  public record ForecastDay(LocalDate date, double percent) {}
}
