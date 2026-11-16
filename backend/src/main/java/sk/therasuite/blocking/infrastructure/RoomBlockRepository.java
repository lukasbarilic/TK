package sk.therasuite.blocking.infrastructure;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomBlockRepository extends JpaRepository<RoomBlock, Long> {

  /** Blokácie prekrývajúce interval [from, to); nezadané hranice a izba sa nefiltrujú. */
  @Query(
      """
      select b from RoomBlock b
      where (:roomId is null or b.roomId = :roomId)
        and (cast(:to as date) is null or b.dateFrom < :to)
        and (cast(:from as date) is null or b.dateTo > :from)
      order by b.dateFrom, b.id
      """)
  List<RoomBlock> findOverlapping(
      @Param("from") LocalDate from, @Param("to") LocalDate to, @Param("roomId") Long roomId);
}
