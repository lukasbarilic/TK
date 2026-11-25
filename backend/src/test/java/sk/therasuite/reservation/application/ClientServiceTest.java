package sk.therasuite.reservation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import sk.therasuite.accommodation.application.ReservationStayGateway;
import sk.therasuite.reservation.application.ClientService.ClientView;
import sk.therasuite.reservation.application.ClientService.UpdateCommand;
import sk.therasuite.reservation.infrastructure.Client;
import sk.therasuite.reservation.infrastructure.ClientRepository;
import sk.therasuite.reservation.infrastructure.ReservationGuest;
import sk.therasuite.reservation.infrastructure.ReservationGuestRepository;
import sk.therasuite.reservation.infrastructure.ReservationQueries;
import sk.therasuite.shared.application.ApiException;

/** Oprava údajov klienta. Fiktívne dáta. */
class ClientServiceTest {

  private ClientRepository clients;
  private ReservationGuestRepository guests;
  private ReservationStayGateway stays;
  private ClientService service;
  private Client client;

  @BeforeEach
  void setUp() {
    clients = mock(ClientRepository.class);
    guests = mock(ReservationGuestRepository.class);
    stays = mock(ReservationStayGateway.class);
    service = new ClientService(mock(ReservationQueries.class), clients, guests, stays);
    client =
        new Client(
            "Ukážka", "Preklepová", LocalDate.of(1980, 1, 1), "SK", "zly@example.invalid", "0900");
    ReflectionTestUtils.setField(client, "id", 7L);
    when(clients.findById(7L)).thenReturn(Optional.of(client));
    when(clients.saveAndFlush(any(Client.class))).thenAnswer(i -> i.getArgument(0));
  }

  private UpdateCommand cmd(long version, String first, String last, String nat, String email) {
    return new UpdateCommand(version, first, last, LocalDate.of(1981, 2, 3), nat, email, "  ");
  }

  @Test
  void updatesAllFieldsTrimsAndClearsBlankValues() {
    when(guests.findByClientId(7L))
        .thenReturn(
            List.of(
                new ReservationGuest(10L, 7L, true),
                new ReservationGuest(11L, 7L, false),
                new ReservationGuest(10L, 7L, false)));

    ClientView view =
        service.update(7L, cmd(0, " Ukážka ", " Opravená ", "cz", " dobry@example.invalid "));

    assertThat(view.firstName()).isEqualTo("Ukážka");
    assertThat(view.lastName()).isEqualTo("Opravená");
    assertThat(view.birthDate()).isEqualTo(LocalDate.of(1981, 2, 3));
    assertThat(view.nationality()).isEqualTo("CZ");
    assertThat(view.email()).isEqualTo("dobry@example.invalid");
    assertThat(view.phone()).isNull();
    verify(stays).publishPlanChangedForReservations(List.of(10L, 11L));
  }

  @Test
  void staleVersionIsConflict() {
    assertThatThrownBy(() -> service.update(7L, cmd(3, "A", "B", null, null)))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("iný používateľ");
    verify(clients, never()).saveAndFlush(any());
  }

  @Test
  void blankNameIsRejected() {
    assertThatThrownBy(() -> service.update(7L, cmd(0, "A", "   ", null, null)))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("povinné");
    assertThat(client.getLastName()).isEqualTo("Preklepová");
  }

  @Test
  void unknownClientIsNotFound() {
    when(clients.findById(8L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.update(8L, cmd(0, "A", "B", null, null)))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("neexistuje");
  }
}
