-- Zoznam hostí s miestom ubytovania (TK-22, HLD kap. 8.5).
-- Ošetrujúci lekár karty; história priradení (doctor_assignment) je mimo rozsahu tohto tiketu.
ALTER TABLE reservation
    ADD COLUMN attending_doctor_id BIGINT NULL REFERENCES user_account (id);

CREATE INDEX reservation_attending_doctor_idx ON reservation (attending_doctor_id)
    WHERE attending_doctor_id IS NOT NULL;

-- Recepčná vidí zoznam všetkých hostí (nielen vlastných pacientov); vedúca recepcie ho už má z V4.
-- Export (CSV) ostáva vedúcej recepcie a lekárovi.
INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, 'GUEST_LOCATION_VIEW_ALL' FROM role r
WHERE r.code = 'RECEPTIONIST'
  AND NOT EXISTS (SELECT 1 FROM role_permission rp
                  WHERE rp.role_id = r.id AND rp.permission_code = 'GUEST_LOCATION_VIEW_ALL');
