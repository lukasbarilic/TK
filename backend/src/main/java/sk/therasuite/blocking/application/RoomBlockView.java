package sk.therasuite.blocking.application;

import java.time.LocalDate;

public record RoomBlockView(
    Long id, Long roomId, Long reasonId, LocalDate dateFrom, LocalDate dateTo, String note) {}
