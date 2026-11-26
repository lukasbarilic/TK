package sk.therasuite.pricing.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PriceListRepository extends JpaRepository<PriceList, Long> {}
