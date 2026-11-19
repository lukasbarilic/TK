-- Stavy rezervačnej karty (TK-43): príchod, odchod, storno, neprišiel a opravy.

ALTER TABLE reservation DROP CONSTRAINT reservation_status_check;
ALTER TABLE reservation ADD CONSTRAINT reservation_status_check
    CHECK (status IN ('RESERVED', 'CHECKED_IN', 'CHECKED_OUT', 'CANCELLED', 'NO_SHOW'));

ALTER TABLE reservation
    ADD COLUMN checked_in_at  TIMESTAMPTZ  NULL,
    ADD COLUMN checked_out_at TIMESTAMPTZ  NULL,
    ADD COLUMN cancelled_at   TIMESTAMPTZ  NULL,
    ADD COLUMN cancel_reason  VARCHAR(500) NULL;

INSERT INTO permission (code, module, description, has_limit) VALUES
 ('RESERVATION_STATUS', 'reservation', 'Príchod, odchod, storno a neprišiel na rezervačnej karte', FALSE),
 ('RESERVATION_STATUS_FIX', 'reservation', 'Opravy stavu karty: zrušiť príchod, vrátiť neprišiel', FALSE);

INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, p.code FROM role r, permission p
WHERE r.code IN ('RECEPTIONIST', 'RECEPTION_MANAGER') AND p.code = 'RESERVATION_STATUS';

INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, p.code FROM role r, permission p
WHERE r.code = 'RECEPTION_MANAGER' AND p.code = 'RESERVATION_STATUS_FIX';
