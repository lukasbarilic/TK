package sk.therasuite.reservation.infrastructure;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationGuestRepository extends JpaRepository<ReservationGuest, Long> {

  List<ReservationGuest> findByReservationIdOrderById(Long reservationId);

  List<ReservationGuest> findByReservationIdInOrderById(Collection<Long> reservationIds);

  List<ReservationGuest> findByClientId(Long clientId);
}
