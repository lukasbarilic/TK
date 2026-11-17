package sk.therasuite.accommodation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import sk.therasuite.accommodation.application.AccommodationPlanService;
import sk.therasuite.accommodation.application.AccommodationPlanService.PlanFilter;
import sk.therasuite.accommodation.application.PlanViews.HeldBedBar;
import sk.therasuite.accommodation.application.PlanViews.Plan;
import sk.therasuite.accommodation.application.PlanViews.RoomBlockBar;
import sk.therasuite.accommodation.application.PlanViews.StayBar;
import sk.therasuite.accommodation.domain.StayPeriod;
import sk.therasuite.accommodation.infrastructure.AccommodationQueries;
import sk.therasuite.accommodation.infrastructure.AccommodationQueries.FreeBed;
import sk.therasuite.accommodation.infrastructure.AccommodationQueries.PlanBlockRow;
import sk.therasuite.accommodation.infrastructure.AccommodationQueries.PlanData;
import sk.therasuite.accommodation.infrastructure.AccommodationQueries.PlanRoomRow;
import sk.therasuite.accommodation.infrastructure.AccommodationQueries.PlanStayRow;
import sk.therasuite.iam.application.PermissionChecker;
import sk.therasuite.iam.domain.Permissions;
import sk.therasuite.shared.AuditedReadResult;
import sk.therasuite.shared.application.ApiException;

class AccommodationPlanServiceTest {

  private static final LocalDate FROM = LocalDate.parse("2030-01-10");
  private static final LocalDate TO = LocalDate.parse("2030-01-20");
  private static final PlanFilter ALL = new PlanFilter(null, null, null, false, false);

  private final AccommodationQueries queries = mock(AccommodationQueries.class);
  private final PermissionChecker permissions = mock(PermissionChecker.class);
  private final AccommodationPlanService service =
      new AccommodationPlanService(queries, permissions);

  private static PlanRoomRow room(long id, String number) {
    // lôžka: id*10+1, id*10+2 riadne, id*10+3 prístelka
    return new PlanRoomRow(
        id,
        1,
        "ALF",
        7,
        LocalDate.parse("2030-01-12"),
        number,
        "1",
        2,
        "K2",
        new ArrayList<>(
            List.of(
                new FreeBed(id * 10 + 1, 1, "REGULAR"),
                new FreeBed(id * 10 + 2, 2, "REGULAR"),
                new FreeBed(id * 10 + 3, 3, "EXTRA"))));
  }

  private static PlanStayRow stay(
      long id, long reservation, long bed, long room, String from, String to, long client) {
    return new PlanStayRow(
        id,
        reservation,
        bed,
        room,
        LocalDate.parse(from),
        LocalDate.parse(to),
        false,
        "RESERVED",
        LocalDate.parse(from),
        LocalDate.parse(to),
        false,
        false,
        null,
        client,
        "Testovský",
        "anna");
  }

  private AuditedReadResult<Plan> plan(PlanData data, PlanFilter filter) {
    when(queries.loadPlan(any(StayPeriod.class), any(), any(), any(), any())).thenReturn(data);
    return service.plan(FROM, TO, filter);
  }

  @Test
  void clipsBarsToPeriodButKeepsActualDatesAndLabel() {
    var stay = stay(1, 100, 11, 1, "2030-01-05", "2030-01-12", 500);
    var block =
        new PlanBlockRow(
            9,
            1,
            "Maľovanie",
            "poznámka",
            LocalDate.parse("2030-01-18"),
            LocalDate.parse("2030-01-25"));

    var result =
        plan(new PlanData(List.of(room(1, "101")), List.of(stay), List.of(block), Set.of()), ALL);

    var bars = result.value().rooms().get(0).bars();
    var stayBar = (StayBar) bars.get(0);
    assertThat(stayBar.from()).isEqualTo(FROM);
    assertThat(stayBar.to()).isEqualTo(LocalDate.parse("2030-01-12"));
    assertThat(stayBar.stayFrom()).isEqualTo(LocalDate.parse("2030-01-05"));
    assertThat(stayBar.label()).isEqualTo("Testovský A.");
    var blockBar = (RoomBlockBar) bars.get(1);
    assertThat(blockBar.to()).isEqualTo(TO);
    assertThat(blockBar.blockTo()).isEqualTo(LocalDate.parse("2030-01-25"));
    assertThat(blockBar.reason()).isEqualTo("Maľovanie");
    assertThat(result.clientIds()).containsExactly(500L);
    assertThat(result.value().receptions())
        .singleElement()
        .satisfies(r -> assertThat(r.businessDate()).isEqualTo(LocalDate.parse("2030-01-12")));
  }

  @Test
  void heldBedsOnlyForExclusiveCards() {
    var normal = stay(1, 100, 11, 1, "2030-01-10", "2030-01-15", 500);
    var exclusive = stay(2, 200, 21, 2, "2030-01-10", "2030-01-15", 501);

    var result =
        plan(
            new PlanData(
                List.of(room(1, "101"), room(2, "102")),
                List.of(normal, exclusive),
                List.of(),
                Set.of(200L)),
            ALL);

    var rooms = result.value().rooms();
    assertThat(rooms.get(0).bars()).noneMatch(b -> b instanceof HeldBedBar);
    assertThat(rooms.get(1).bars())
        .filteredOn(b -> b instanceof HeldBedBar)
        .singleElement()
        .satisfies(b -> {
          var held = (HeldBedBar) b;
          assertThat(held.bedId()).isEqualTo(22);
          assertThat(held.byStayId()).isEqualTo(2L);
        });
  }

  @Test
  void freeOnlyKeepsRoomsWithFreeRegularBedForWholePeriod() {
    // izba 1: obe riadne lôžka obsadené v období, izba 2: jedno voľné, izba 3: blokovaná,
    // izba 4: voľné lôžko je držané výhradným užívaním
    var stays =
        List.of(
            stay(1, 100, 11, 1, "2030-01-10", "2030-01-15", 500),
            stay(2, 101, 12, 1, "2030-01-15", "2030-01-20", 501),
            stay(3, 102, 21, 2, "2030-01-10", "2030-01-12", 502),
            stay(4, 103, 41, 4, "2030-01-10", "2030-01-12", 503));
    var block =
        new PlanBlockRow(
            5, 3, "Oprava", null, LocalDate.parse("2030-01-12"), LocalDate.parse("2030-01-13"));
    var data =
        new PlanData(
            List.of(room(1, "1"), room(2, "2"), room(3, "3"), room(4, "4")),
            stays,
            List.of(block),
            Set.of(103L));

    var result = plan(data, new PlanFilter(null, null, null, true, false));

    assertThat(result.value().rooms()).extracting(r -> r.number()).containsExactly("2");
    // klienti a pásiky len pre vrátené izby
    assertThat(result.clientIds()).containsExactly(502L);
  }

  @Test
  void fullyFreeKeepsOnlyRoomsWithoutAnyStayOrBlock() {
    var data =
        new PlanData(
            List.of(room(1, "1"), room(2, "2"), room(3, "3")),
            List.of(stay(1, 100, 11, 1, "2030-01-19", "2030-01-22", 500)),
            List.of(
                new PlanBlockRow(
                    5,
                    2,
                    "Oprava",
                    null,
                    LocalDate.parse("2030-01-12"),
                    LocalDate.parse("2030-01-13"))),
            Set.of());

    var result = plan(data, new PlanFilter(null, null, null, false, true));

    assertThat(result.value().rooms()).extracting(r -> r.number()).containsExactly("3");
    assertThat(result.value().rooms().get(0).bars()).isEmpty();
  }

  @Test
  void requiresPlanViewForRequestedReception() {
    org.mockito.Mockito.doThrow(new org.springframework.security.access.AccessDeniedException("x"))
        .when(permissions)
        .require(eq(Permissions.PLAN_VIEW), eq(7L));

    assertThatThrownBy(() -> service.plan(FROM, TO, new PlanFilter(null, null, 7L, false, false)))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @Test
  void rejectsInvalidPeriod() {
    assertThatThrownBy(() -> service.plan(TO, FROM, ALL)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.plan(FROM, FROM.plusDays(400), ALL))
        .isInstanceOf(ApiException.class);
  }
}
