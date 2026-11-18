-- Počítadlo zmien recepcie (TK-18): základ pre ETag a podmienené čítanie (304) pri offline synchronizácii.
-- Hodnota sa zvyšuje triggermi v tej istej transakcii ako samotný zápis, takže zrušená transakcia
-- počítadlo nezmení.

CREATE TABLE reception_change_seq (
    reception_id BIGINT      PRIMARY KEY REFERENCES reception (id),
    seq          BIGINT      NOT NULL DEFAULT 0,
    changed_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO reception_change_seq (reception_id) SELECT id FROM reception;

CREATE FUNCTION bump_reception_seq(p_reception_id BIGINT) RETURNS VOID
    LANGUAGE sql AS $$
    INSERT INTO reception_change_seq (reception_id, seq, changed_at)
    VALUES (p_reception_id, 1, now())
    ON CONFLICT (reception_id)
        DO UPDATE SET seq = reception_change_seq.seq + 1, changed_at = now()
$$;

CREATE FUNCTION bump_reception_seq_of_room(p_room_id BIGINT) RETURNS VOID
    LANGUAGE sql AS $$
    SELECT bump_reception_seq(b.reception_id)
    FROM room r JOIN building b ON b.id = r.building_id
    WHERE r.id = p_room_id
$$;

CREATE FUNCTION bump_reception_seq_of_bed(p_bed_id BIGINT) RETURNS VOID
    LANGUAGE sql AS $$
    SELECT bump_reception_seq(b.reception_id)
    FROM bed d JOIN room r ON r.id = d.room_id JOIN building b ON b.id = r.building_id
    WHERE d.id = p_bed_id
$$;

-- Rezervačná karta patrí recepciám, v ktorých budovách má pobyty.
CREATE FUNCTION bump_reception_seq_of_reservation(p_reservation_id BIGINT) RETURNS VOID
    LANGUAGE sql AS $$
    SELECT bump_reception_seq(x.reception_id)
    FROM (SELECT DISTINCT b.reception_id
          FROM stay s
                   JOIN bed d ON d.id = s.bed_id
                   JOIN room r ON r.id = d.room_id
                   JOIN building b ON b.id = r.building_id
          WHERE s.reservation_id = p_reservation_id) x
$$;

CREATE FUNCTION trg_stay_bump_seq() RETURNS TRIGGER
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        PERFORM bump_reception_seq_of_bed(NEW.bed_id);
    END IF;
    IF TG_OP = 'DELETE' OR (TG_OP = 'UPDATE' AND OLD.bed_id <> NEW.bed_id) THEN
        PERFORM bump_reception_seq_of_bed(OLD.bed_id);
    END IF;
    RETURN NULL;
END $$;

CREATE FUNCTION trg_room_block_bump_seq() RETURNS TRIGGER
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        PERFORM bump_reception_seq_of_room(NEW.room_id);
    END IF;
    IF TG_OP = 'DELETE' OR (TG_OP = 'UPDATE' AND OLD.room_id <> NEW.room_id) THEN
        PERFORM bump_reception_seq_of_room(OLD.room_id);
    END IF;
    RETURN NULL;
END $$;

CREATE FUNCTION trg_reservation_bump_seq() RETURNS TRIGGER
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM bump_reception_seq_of_reservation(OLD.id);
    ELSE
        PERFORM bump_reception_seq_of_reservation(NEW.id);
    END IF;
    RETURN NULL;
END $$;

CREATE FUNCTION trg_reservation_guest_bump_seq() RETURNS TRIGGER
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM bump_reception_seq_of_reservation(OLD.reservation_id);
    ELSE
        PERFORM bump_reception_seq_of_reservation(NEW.reservation_id);
    END IF;
    RETURN NULL;
END $$;

-- Uzávierka (otvorenie a zatvorenie obchodného dňa) mení obchodný dátum recepcie.
CREATE FUNCTION trg_business_day_bump_seq() RETURNS TRIGGER
    LANGUAGE plpgsql AS $$
BEGIN
    PERFORM bump_reception_seq(NEW.reception_id);
    RETURN NULL;
END $$;

CREATE TRIGGER stay_bump_seq AFTER INSERT OR UPDATE OR DELETE ON stay
    FOR EACH ROW EXECUTE FUNCTION trg_stay_bump_seq();
CREATE TRIGGER room_block_bump_seq AFTER INSERT OR UPDATE OR DELETE ON room_block
    FOR EACH ROW EXECUTE FUNCTION trg_room_block_bump_seq();
CREATE TRIGGER reservation_bump_seq AFTER INSERT OR UPDATE OR DELETE ON reservation
    FOR EACH ROW EXECUTE FUNCTION trg_reservation_bump_seq();
CREATE TRIGGER reservation_guest_bump_seq AFTER INSERT OR UPDATE OR DELETE ON reservation_guest
    FOR EACH ROW EXECUTE FUNCTION trg_reservation_guest_bump_seq();
CREATE TRIGGER business_day_bump_seq AFTER INSERT OR UPDATE ON business_day
    FOR EACH ROW EXECUTE FUNCTION trg_business_day_bump_seq();

-- Zmena údajov klienta (meno na pásiku plánu) sa týka recepcií jeho kariet s pobytmi.
CREATE FUNCTION trg_client_bump_seq() RETURNS TRIGGER
    LANGUAGE plpgsql AS $$
DECLARE
    v_reservation_id BIGINT;
BEGIN
    FOR v_reservation_id IN SELECT reservation_id FROM reservation_guest WHERE client_id = NEW.id LOOP
        PERFORM bump_reception_seq_of_reservation(v_reservation_id);
    END LOOP;
    RETURN NULL;
END $$;

CREATE TRIGGER client_bump_seq AFTER UPDATE ON client
    FOR EACH ROW EXECUTE FUNCTION trg_client_bump_seq();
