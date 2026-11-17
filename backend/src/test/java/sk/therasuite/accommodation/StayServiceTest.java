package sk.therasuite.accommodation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import sk.therasuite.accommodation.application.StayService;
import sk.therasuite.accommodation.application.StayService.CreateCommand;
import sk.therasuite.accommodation.domain.OccupancyModel.Bed;
import sk.therasuite.accommodation.infrastructure.AccommodationQueries;
import sk.therasuite.accommodation.infrastructure.AccommodationQueries.BedRow;
import sk.therasuite.accommodation.infrastructure.AccommodationQueries.CardRow;
import sk.therasuite.accommodation.infrastructure.AccommodationQueries.RoomRow;
import sk.therasuite.accommodation.infrastructure.Stay;
import sk.therasuite.accommodation.infrastructure.StayRepository;
import sk.therasuite.businessday.application.BusinessDateProvider;
import sk.therasuite.catalog.domain.BedType;
import sk.therasuite.iam.application.PermissionChecker;
import sk.therasuite.reservation.application.ReservationService;
import sk.therasuite.shared.application.ApiException;

class StayServiceTest {

  private static final LocalDate DAY = LocalDate.of(2030, 1, 10);

  private final StayRepository stays = mock(StayRepository.class);
  private final AccommodationQueries queries = mock(AccommodationQueries.class);
  private final PermissionChecker permissions = mock(PermissionChecker.class);
  private final BusinessDateProvider dates = mock(BusinessDateProvider.class);
  private final ReservationService reservations = mock(ReservationService.class);
  private final StayService service =
      new StayService(
          stays,
          queries,
          permissions,
          dates,
          reservations,
          mock(org.springframework.context.ApplicationEventPublisher.class));

  @BeforeEach
  void setUp() {
    when(queries.card(1L))
        .thenReturn(
            Optional.of(new CardRow(1, "RESERVED", DAY, DAY.plusDays(5), 7, false, false, 0)));
    when(queries.bed(10L)).thenReturn(Optional.of(new BedRow(10, 20, 30)));
    when(queries.room(20L)).thenReturn(Optional.of(new RoomRow(20, 7, null, true, true, null)));
    when(queries.bedsOfRoom(20L)).thenReturn(List.of(new Bed(10, 20, BedType.REGULAR, true)));
    when(queries.staysInRoom(anyLong(), any())).thenReturn(List.of());
    when(queries.blocksInRoom(anyLong(), any())).thenReturn(List.of());
    when(queries.guestBirthDates(1L)).thenReturn(List.of());
    when(dates.currentForRoom(20L)).thenReturn(DAY);
    when(permissions.limit("STAY_CREATE", 30L)).thenReturn(365);
  }

  @Test
  void outsideReservationWithoutAdjustIsRejectedBeforeAnyWrite() {
    var cmd = new CreateCommand(10, DAY, DAY.plusDays(9), false, List.of());

    assertThatThrownBy(() -> service.create(1, cmd))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.getCode()).isEqualTo("OUTSIDE_RESERVATION");
              assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            });
    verify(stays, never()).saveAndFlush(any());
  }

  @Test
  void databaseExclusionViolationIsTranslatedToBedOccupied() {
    when(stays.saveAndFlush(any(Stay.class)))
        .thenThrow(
            new DataIntegrityViolationException(
                "exclusion", new SQLException("conflicting key value", "23P01")));
    var cmd = new CreateCommand(10, DAY, DAY.plusDays(2), false, List.of());

    assertThatThrownBy(() -> service.create(1, cmd))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.getCode()).isEqualTo("BED_OCCUPIED");
              assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            });
    verify(queries).lockRoom(20L);
  }

  @Test
  void otherIntegrityViolationsAreNotHidden() {
    when(stays.saveAndFlush(any(Stay.class)))
        .thenThrow(new DataIntegrityViolationException("x", new SQLException("fk", "23503")));
    var cmd = new CreateCommand(10, DAY, DAY.plusDays(2), false, List.of());

    assertThatThrownBy(() -> service.create(1, cmd))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void invalidIntervalIsBadRequest() {
    var cmd = new CreateCommand(10, DAY, DAY, false, List.of());

    assertThatThrownBy(() -> service.create(1, cmd))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void unknownBedIsBadRequestAndUnknownCardIsNotFound() {
    assertThatThrownBy(
            () -> service.create(1, new CreateCommand(99, DAY, DAY.plusDays(1), false, null)))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    assertThatThrownBy(
            () -> service.create(2, new CreateCommand(10, DAY, DAY.plusDays(1), false, null)))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
  }
}
