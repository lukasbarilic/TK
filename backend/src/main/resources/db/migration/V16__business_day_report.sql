-- Archív reportu nočnej uzávierky (TK-44) a oprávnenie REPORT_VIEW pre vedúcu recepcie.
-- Report obsahuje len počty (osoby, obsadenosť), žiadne mená hostí.

CREATE TABLE business_day_report (
    reception_id  BIGINT      NOT NULL REFERENCES reception (id),
    business_date DATE        NOT NULL,
    report        JSONB       NOT NULL,
    closed_by     BIGINT      NOT NULL REFERENCES user_account (id),
    closed_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (reception_id, business_date)
);

INSERT INTO permission (code, module, description, has_limit) VALUES
 ('REPORT_VIEW', 'businessday', 'Zobraziť archív reportov nočnej uzávierky', FALSE);

INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, 'REPORT_VIEW' FROM role r WHERE r.code = 'RECEPTION_MANAGER';
