-- Fiktívny demo cenník (len ukážkové ceny, nie skutočné).
INSERT INTO price_list (id, name, valid_from, valid_to, status)
VALUES (1, 'Demo cenník 2026/2027', DATE '2026-01-01', DATE '2027-12-31', 'ACTIVE');

INSERT INTO price_season (price_list_id, code, name, date_from, date_to, priority) VALUES
 (1, 'LOW', 'Mimosezóna', DATE '2026-01-01', DATE '2027-12-31', 0),
 (1, 'XMAS', 'Vianoce', DATE '2026-12-24', DATE '2027-01-03', 10);

INSERT INTO package (code, name, board_type, procedures_per_day, min_nights, max_nights) VALUES
 ('DEMO_RELAX', 'Demo relaxačný týždeň', 'HB', 2, 7, NULL);

INSERT INTO package_item (package_id, service_code, name, part, allocated_amount, vat_rate, unit, frequency, quantity)
SELECT id, 'HB', 'Polpenzia', 'FOOD', 18.00, 19.00, 'PER_PERSON', 'PER_NIGHT', 1 FROM package WHERE code = 'DEMO_RELAX'
UNION ALL
SELECT id, 'PROC', 'Procedúry', 'SERVICES', 20.00, 19.00, 'PER_PERSON', 'PER_NIGHT', 2 FROM package WHERE code = 'DEMO_RELAX';

-- Sadzba za lôžko a noc podľa kategórie (základ 40 € + 10 € za každú ďalšiu úroveň kategórie).
INSERT INTO price_rate (price_season_id, room_category_id, package_id, price_type, occupancy, amount, extra_bed_amount, unit)
SELECT s.id, c.id, NULL, 'PER_BED', NULL,
       40.00 + 10.00 * (c.base_category_id - 1) + CASE WHEN s.code = 'XMAS' THEN 20.00 ELSE 0 END,
       20.00, 'PER_NIGHT'
FROM price_season s CROSS JOIN room_category c WHERE s.price_list_id = 1;

INSERT INTO price_rate (price_season_id, room_category_id, package_id, price_type, occupancy, amount, unit, nights, short_stay_rule)
SELECT s.id, c.id, p.id, 'PER_BED', NULL, 690.00, 'PER_STAY', 7, 'PRORATE'
FROM price_season s CROSS JOIN room_category c CROSS JOIN package p
WHERE s.price_list_id = 1 AND s.code = 'LOW' AND c.code = 'K4' AND p.code = 'DEMO_RELAX';

INSERT INTO occupancy_modifier (price_list_id, age_from, age_to, bed_type, kind, value, priority) VALUES
 (1, 0, 2, NULL, 'FIXED_PRICE', 0.00, 10),
 (1, 3, 11, 'EXTRA', 'FIXED_PRICE', 20.00, 10);

INSERT INTO price_surcharge (price_list_id, code, type, amount, amount_kind, vat_rate) VALUES
 (1, 'EXTRA_BED', 'EXTRA_BED', 15.00, 'PER_NIGHT', 19.00),
 (1, 'SINGLE_USE', 'SINGLE_USE', 12.00, 'PER_NIGHT', 19.00),
 (1, 'LATE_CHECK_OUT', 'LATE_CHECK_OUT', 15.00, 'PER_STAY', 19.00);

INSERT INTO price_discount (price_list_id, code, type, percent, min_nights, stackable, priority) VALUES
 (1, 'LENGTH7', 'LENGTH', 5.00, 7, FALSE, 0);

INSERT INTO payer_coverage (payer_id, room_category_id, valid_from, amount_per_night, covers)
SELECT p.id, c.id, DATE '2026-01-01', 48.00, 'ACCOMMODATION'
FROM payer p CROSS JOIN room_category c WHERE p.type = 'INSURER' AND c.code = 'K2';

SELECT setval(pg_get_serial_sequence('price_list', 'id'), 1);
