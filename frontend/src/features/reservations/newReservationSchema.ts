import { z } from 'zod';
import { diffDays } from '../../shared/time';

const ISO_DAY = /^\d{4}-\d{2}-\d{2}$/;

/** Počet nocí; 0, ak dátumy nie sú platné alebo odchod nie je po príchode. */
export function nightsBetween(from: string, to: string): number {
  if (!ISO_DAY.test(from) || !ISO_DAY.test(to)) return 0;
  try {
    return Math.max(0, diffDays(from, to));
  } catch {
    return 0;
  }
}

const optional = z.string().trim().max(200);

export const guestSchema = z.object({
  clientId: z.number().optional(),
  firstName: z.string().trim().min(1, 'required').max(100),
  lastName: z.string().trim().min(1, 'required').max(100),
  birthYear: z.number().optional(),
  birthDate: z.string().refine((v) => v === '' || ISO_DAY.test(v), 'birthDate'),
  email: optional.refine((v) => v === '' || /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(v), 'email'),
  phone: z.string().trim().max(50),
});

/** Formulár novej karty (chybové hlásenia sú kľúče `newReservation.errors.*`). */
export const reservationFormSchema = z
  .object({
    guests: z.array(guestSchema).min(1, 'guestsRequired'),
    primaryIndex: z.number().int().min(0),
    arrivalDate: z.string().regex(ISO_DAY, 'required'),
    departureDate: z.string().regex(ISO_DAY, 'required'),
    categoryId: z.string().min(1, 'required'),
    adults: z.number({ error: 'required' }).int().min(1, 'adultsMin'),
    children: z.number({ error: 'required' }).int().min(0, 'childrenMin'),
    payerId: z.string().min(1, 'required'),
    doctorId: z.string().regex(/^\d*$/, 'doctor'),
    earlyCheckIn: z.boolean(),
    lateCheckOut: z.boolean(),
    exclusiveRoomUse: z.boolean(),
    doNotMove: z.boolean(),
    note: z.string().max(2000, 'noteLong'),
    noteInPlan: z.boolean(),
  })
  .superRefine((v, ctx) => {
    if (ISO_DAY.test(v.arrivalDate) && ISO_DAY.test(v.departureDate) && nightsBetween(v.arrivalDate, v.departureDate) < 1) {
      ctx.addIssue({ code: 'custom', path: ['departureDate'], message: 'departureAfterArrival' });
    }
  });

export type ReservationForm = z.infer<typeof reservationFormSchema>;
export type GuestForm = z.infer<typeof guestSchema>;
