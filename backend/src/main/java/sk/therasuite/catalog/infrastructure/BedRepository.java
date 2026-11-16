package sk.therasuite.catalog.infrastructure;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BedRepository extends JpaRepository<Bed, Long> {

  List<Bed> findByRoomIdInOrderByRoomIdAscBedNumberAsc(Collection<Long> roomIds);
}
