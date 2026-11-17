package sk.therasuite.reservation.infrastructure;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationGuestRepository extends JpaRepository<ReservationGuest, Long> {

  List<ReservationGuest> findByReservationIdOrderById(Long reservationId);
}
