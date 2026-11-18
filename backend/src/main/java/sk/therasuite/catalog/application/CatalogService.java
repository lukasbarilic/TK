package sk.therasuite.catalog.application;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sk.therasuite.catalog.application.CatalogViews.BedView;
import sk.therasuite.catalog.application.CatalogViews.BuildingView;
import sk.therasuite.catalog.application.CatalogViews.CentreView;
import sk.therasuite.catalog.application.CatalogViews.PayerView;
import sk.therasuite.catalog.application.CatalogViews.ReceptionView;
import sk.therasuite.catalog.application.CatalogViews.RoomCategoryView;
import sk.therasuite.catalog.application.CatalogViews.RoomView;
import sk.therasuite.catalog.domain.BedType;
import sk.therasuite.catalog.domain.PayerType;
import sk.therasuite.catalog.infrastructure.Bed;
import sk.therasuite.catalog.infrastructure.BedRepository;
import sk.therasuite.catalog.infrastructure.BuildingRepository;
import sk.therasuite.catalog.infrastructure.CentreRepository;
import sk.therasuite.catalog.infrastructure.PayerRepository;
import sk.therasuite.catalog.infrastructure.ReceptionRepository;
import sk.therasuite.catalog.infrastructure.Room;
import sk.therasuite.catalog.infrastructure.RoomCategoryRepository;
import sk.therasuite.catalog.infrastructure.RoomRepository;
import sk.therasuite.shared.application.CacheConfig;

/** Čítanie katalógu ubytovacích kapacít. */
@Service
@Transactional(readOnly = true)
public class CatalogService {

  private final CentreRepository centres;
  private final ReceptionRepository receptions;
  private final BuildingRepository buildings;
  private final RoomRepository rooms;
  private final BedRepository beds;
  private final RoomCategoryRepository roomCategories;
  private final PayerRepository payers;

  public CatalogService(
      CentreRepository centres,
      ReceptionRepository receptions,
      BuildingRepository buildings,
      RoomRepository rooms,
      BedRepository beds,
      RoomCategoryRepository roomCategories,
      PayerRepository payers) {
    this.centres = centres;
    this.receptions = receptions;
    this.buildings = buildings;
    this.rooms = rooms;
    this.beds = beds;
    this.roomCategories = roomCategories;
    this.payers = payers;
  }

  @Cacheable(cacheNames = CacheConfig.CATALOG, key = "'centres'")
  public List<CentreView> centres() {
    return centres.findAll().stream()
        .sorted(Comparator.comparing(c -> c.getId()))
        .map(c -> new CentreView(c.getId(), c.getCode(), c.getName(), c.isActive()))
        .toList();
  }

  @Cacheable(cacheNames = CacheConfig.CATALOG, key = "'receptions'")
  public List<ReceptionView> receptions() {
    return receptions.findAll().stream()
        .sorted(Comparator.comparing(r -> r.getId()))
        .map(
            r -> new ReceptionView(r.getId(), r.getCode(), r.getName(), r.getEmail(), r.isActive()))
        .toList();
  }

  @Cacheable(cacheNames = CacheConfig.CATALOG, key = "'buildings'")
  public List<BuildingView> buildings() {
    return buildings.findAll().stream()
        .sorted(Comparator.comparingInt(b -> b.getSortOrder()))
        .map(b ->
            new BuildingView(
                b.getId(),
                b.getCentreId(),
                b.getReceptionId(),
                b.getCode(),
                b.getName(),
                b.getSite(),
                b.getSortOrder(),
                b.isActive()))
        .toList();
  }

  @Cacheable(cacheNames = CacheConfig.CATALOG, key = "'roomCategories'")
  public List<RoomCategoryView> roomCategories() {
    return roomCategories.findAll().stream()
        .sorted(Comparator.comparing(c -> c.getId()))
        .map(c ->
            new RoomCategoryView(
                c.getId(),
                c.getCentreId(),
                c.getBaseCategoryId(),
                c.getCode(),
                c.getName(),
                c.getBedCount(),
                c.isActive()))
        .toList();
  }

  @Cacheable(cacheNames = CacheConfig.CATALOG, key = "'payers'")
  public List<PayerView> payers() {
    return payers.findAll().stream()
        .sorted(Comparator.comparing(p -> p.getId()))
        .map(p ->
            new PayerView(
                p.getId(), p.getCode(), p.getName(), PayerType.valueOf(p.getType()), p.isActive()))
        .toList();
  }

  /** Izby s lôžkami; ak je zadaná budova, len jej izby. */
  @Cacheable(cacheNames = CacheConfig.CATALOG, key = "'rooms:' + #buildingId")
  public List<RoomView> rooms(Long buildingId) {
    List<Room> found =
        buildingId == null
            ? rooms.findAll().stream().sorted(Comparator.comparing(r -> r.getId())).toList()
            : rooms.findByBuildingIdOrderByNumber(buildingId);
    return toViews(found);
  }

  @Cacheable(cacheNames = CacheConfig.CATALOG, key = "'room:' + #id")
  public Optional<RoomView> room(Long id) {
    return rooms.findById(id).map(r -> toViews(List.of(r)).getFirst());
  }

  private List<RoomView> toViews(List<Room> found) {
    Map<Long, List<BedView>> bedsByRoom =
        found.isEmpty()
            ? Map.of()
            : beds
                .findByRoomIdInOrderByRoomIdAscBedNumberAsc(
                    found.stream().map(Room::getId).toList())
                .stream()
                .collect(
                    Collectors.groupingBy(
                        Bed::getRoomId,
                        Collectors.mapping(
                            b ->
                                new BedView(
                                    b.getId(),
                                    b.getBedNumber(),
                                    BedType.valueOf(b.getType()),
                                    b.isActive()),
                            Collectors.toList())));
    return found.stream()
        .map(r ->
            new RoomView(
                r.getId(),
                r.getBuildingId(),
                r.getNumber(),
                r.getFloor(),
                r.getCategoryId(),
                r.getSecondaryCategoryId(),
                r.getDepartmentId(),
                r.getRegularBeds(),
                r.getExtraBeds(),
                r.getExtraBedMaxAge(),
                r.isBookable(),
                r.isActive(),
                bedsByRoom.getOrDefault(r.getId(), List.of())))
        .toList();
  }
}
