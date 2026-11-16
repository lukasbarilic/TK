-- Seed: oprávnenia (HLD kap. 8.3), predvolené roly a technický používateľ system.

INSERT INTO permission (code, module, description, has_limit) VALUES
 ('PLAN_VIEW', 'accommodation', 'Zobraziť plán ubytovania a karty', FALSE),
 ('STAY_CREATE', 'accommodation', 'Ubytovať klienta (limit = max. nocí)', TRUE),
 ('STAY_OTHER_CATEGORY', 'accommodation', 'Ubytovať na inú kategóriu, než má karta', FALSE),
 ('STAY_MOVE', 'accommodation', 'Sťahovať, meniť dĺžku, vymieňať', FALSE),
 ('STAY_MOVE_LOCKED', 'accommodation', 'Presunúť zamknutý pobyt', FALSE),
 ('STAY_BACKDATE', 'accommodation', 'Meniť ubytovanie spätne', FALSE),
 ('PRICE_CATEGORY_EDIT', 'accommodation', 'Meniť kategóriu pre cenu a typ ceny', FALSE),
 ('ROOM_BLOCK_EDIT', 'blocking', 'Vytvárať a mazať blokácie', FALSE),
 ('ROOM_FILTER_EDIT', 'accommodation', 'Spravovať filtre izieb pre celú organizáciu', FALSE),
 ('CONTINGENT_VIEW', 'accommodation', 'Zobraziť kontingenty a turnusy', FALSE),
 ('CONTINGENT_EDIT', 'accommodation', 'Spravovať kontingenty a turnusy', FALSE),
 ('GUEST_LOCATION_VIEW', 'reservation', 'Zoznam hostí s miestom ubytovania', FALSE),
 ('GUEST_LOCATION_VIEW_ALL', 'reservation', 'Zoznam všetkých hostí, nielen vlastných pacientov', FALSE),
 ('GUEST_LOCATION_EXPORT', 'reservation', 'Export zoznamu hostí do CSV/PDF', FALSE),
 ('PATIENT_SELF_ASSIGN', 'reservation', 'Lekár si prevezme pacienta iného lekára', FALSE),
 ('DOCTOR_ASSIGN', 'reservation', 'Priradiť ľubovoľného lekára ku karte', FALSE),
 ('RESERVATION_VIEW', 'reservation', 'Zobraziť rezervačné karty', FALSE),
 ('RESERVATION_EDIT', 'reservation', 'Zakladať a meniť rezervačné karty', FALSE),
 ('CLIENT_VIEW', 'reservation', 'Detail klienta s kontaktnými údajmi', FALSE),
 ('BUSINESS_DAY_CLOSE', 'businessday', 'Nočná uzávierka', FALSE),
 ('CATALOG_EDIT', 'catalog', 'Číselníky izieb, lôžok, kategórií', FALSE),
 ('USER_ADMIN', 'iam', 'Správa používateľov a rolí', FALSE),
 ('SETTINGS_EDIT', 'settings', 'Systémové nastavenia', FALSE),
 ('AUDIT_VIEW', 'audit', 'Čítanie auditu', FALSE);

INSERT INTO role (code, name, description, system_role) VALUES
 ('RECEPTIONIST', 'Recepčná', 'Ubytovanie, sťahovanie, blokácie, plán ubytovania', TRUE),
 ('RECEPTION_MANAGER', 'Vedúca recepcie', 'Všetko ako recepčná + kontingenty, spätné zmeny, zamknuté pobyty', TRUE),
 ('DOCTOR', 'Lekár', 'Zoznam hostí s miestom ubytovania', TRUE),
 ('ADMIN', 'Administrátor', 'Správa používateľov, číselníkov a nastavení', TRUE),
 ('AUDITOR', 'Audítor', 'Len čítanie audit záznamov', TRUE),
 ('SYSTEM', 'Systém', 'Plánované úlohy (technický používateľ)', TRUE);

-- Recepčná
INSERT INTO role_permission (role_id, permission_code, limit_value)
SELECT r.id, p.code, CASE WHEN p.code = 'STAY_CREATE' THEN 365 END
FROM role r, permission p
WHERE r.code = 'RECEPTIONIST'
  AND p.code IN ('PLAN_VIEW', 'STAY_CREATE', 'STAY_OTHER_CATEGORY', 'STAY_MOVE', 'PRICE_CATEGORY_EDIT',
                 'ROOM_BLOCK_EDIT', 'CONTINGENT_VIEW', 'GUEST_LOCATION_VIEW', 'DOCTOR_ASSIGN',
                 'RESERVATION_VIEW', 'RESERVATION_EDIT', 'CLIENT_VIEW', 'BUSINESS_DAY_CLOSE');

-- Vedúca recepcie = recepčná + ďalšie
INSERT INTO role_permission (role_id, permission_code, limit_value)
SELECT r.id, p.code, CASE WHEN p.code = 'STAY_CREATE' THEN 365 END
FROM role r, permission p
WHERE r.code = 'RECEPTION_MANAGER'
  AND p.code IN ('PLAN_VIEW', 'STAY_CREATE', 'STAY_OTHER_CATEGORY', 'STAY_MOVE', 'PRICE_CATEGORY_EDIT',
                 'ROOM_BLOCK_EDIT', 'CONTINGENT_VIEW', 'GUEST_LOCATION_VIEW', 'DOCTOR_ASSIGN',
                 'RESERVATION_VIEW', 'RESERVATION_EDIT', 'CLIENT_VIEW', 'BUSINESS_DAY_CLOSE',
                 'STAY_MOVE_LOCKED', 'STAY_BACKDATE', 'ROOM_FILTER_EDIT', 'CONTINGENT_EDIT',
                 'GUEST_LOCATION_VIEW_ALL', 'GUEST_LOCATION_EXPORT');

INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, p.code FROM role r, permission p
WHERE r.code = 'DOCTOR' AND p.code IN ('GUEST_LOCATION_VIEW', 'GUEST_LOCATION_EXPORT', 'PATIENT_SELF_ASSIGN');

INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, p.code FROM role r, permission p
WHERE r.code = 'ADMIN' AND p.code IN ('CATALOG_EDIT', 'USER_ADMIN', 'SETTINGS_EDIT', 'AUDIT_VIEW');

INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, p.code FROM role r, permission p
WHERE r.code = 'AUDITOR' AND p.code = 'AUDIT_VIEW';

-- Technický používateľ: bez hesla, takže sa nedá prihlásiť.
INSERT INTO user_account (username, display_name, status, auth_provider, password_hash, must_change_password)
VALUES ('system', 'Systém', 'ACTIVE', 'LOCAL', NULL, FALSE);

INSERT INTO user_role (user_id, role_id)
SELECT u.id, r.id FROM user_account u, role r WHERE u.username = 'system' AND r.code = 'SYSTEM';
