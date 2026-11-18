package sk.therasuite.shared.infrastructure;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Zapína plánované úlohy (@Scheduled), napr. dopĺňanie partícií auditu. */
@Configuration
@EnableScheduling
class SchedulingConfig {}
