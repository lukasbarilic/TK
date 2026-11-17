-- Dávka presunov a výmena (TK-12) menia viac pobytov naraz; medzikroky môžu na lôžku dočasne
-- kolidovať. Obmedzenie ostáva INITIALLY IMMEDIATE (bežné zápisy sa kontrolujú hneď), ale dávka
-- si ho v transakcii odloží (SET CONSTRAINTS ... DEFERRED) a overí až konečný stav.
ALTER TABLE stay DROP CONSTRAINT stay_bed_no_overlap;
ALTER TABLE stay ADD CONSTRAINT stay_bed_no_overlap EXCLUDE USING gist (
    bed_id WITH =,
    daterange(date_from, date_to, '[)') WITH &&
) DEFERRABLE INITIALLY IMMEDIATE;
