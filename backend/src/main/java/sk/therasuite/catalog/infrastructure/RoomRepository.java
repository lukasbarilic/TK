package sk.therasuite.catalog.infrastructure;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomRepository extends JpaRepository<Room, Long> {

  List<Room> findByBuildingIdOrderByNumber(Long buildingId);
}
