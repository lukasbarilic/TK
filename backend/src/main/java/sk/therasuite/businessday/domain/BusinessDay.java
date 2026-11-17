package sk.therasuite.businessday.domain;

import java.time.LocalDate;

/** Obchodný deň recepcie. */
public record BusinessDay(long id, long receptionId, LocalDate businessDate, String status) {}
