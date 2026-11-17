package sk.therasuite.businessday.domain;

/** Zistenie pravidla uzávierky; bez osobných údajov (karta sa identifikuje číslom). */
public record Finding(String ruleCode, Long reservationId, String message) {}
