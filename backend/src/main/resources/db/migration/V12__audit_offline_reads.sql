-- Audit zobrazení z lokálnej kópie (TK-21): zdroj záznamu, čas a zariadenie z klienta.
-- Existujúce záznamy vznikli online na serveri, preto predvolená hodnota ONLINE.
ALTER TABLE audit_event
    ADD COLUMN source             VARCHAR(10)  NOT NULL DEFAULT 'ONLINE'
        CHECK (source IN ('ONLINE', 'OFFLINE', 'CACHED')),
    ADD COLUMN client_occurred_at TIMESTAMPTZ  NULL,
    ADD COLUMN device_id          VARCHAR(100) NULL;

-- Idempotencia odosielania: audit_event je partitioned podľa occurred_at, takže unikátny index nad
-- samotným event_uid by nebol možný (kľúč partície by musel byť jeho súčasťou). Preto samostatná,
-- nepartitionovaná tabuľka prijatých uid s unikátnym kľúčom; je len na pridávanie.
CREATE TABLE audit_offline_uid (
    event_uid   UUID        PRIMARY KEY,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TRIGGER audit_offline_uid_append_only
    BEFORE UPDATE OR DELETE ON audit_offline_uid
    FOR EACH ROW EXECUTE FUNCTION audit_reject_modification();
