import { zodResolver } from '@hookform/resolvers/zod';
import { Alert, Badge, Button, Checkbox, Group, Loader, Modal, NativeSelect, Paper, Radio, Stack, Text, Textarea, TextInput, Title } from '@mantine/core';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { Controller, useFieldArray, useForm, useWatch } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { useLocation, useNavigate } from 'react-router-dom';
import { errorText } from '../../shared/api/errorText';
import { ApiError } from '../../shared/api/errors';
import type { components } from '../../shared/api/schema';
import { markEventHandled, useConnection, WriteAction } from '../../shared/offline';
import { readOrigin, type NavState } from '../../shared/navigation/origin';
import { addDays } from '../../shared/time';
import { ConfirmWarningsDialog } from '../../shared/ui/ConfirmWarningsDialog';
import { PageHeader } from '../../shared/ui/PageHeader';
import {
  createCard,
  createCardWithStay,
  loadAvailable,
  loadCategories,
  loadPayers,
  searchClients,
  type CreateCardBody,
} from './newReservationApi';
import { nightsBetween, reservationFormSchema, type ReservationForm } from './newReservationSchema';

type Me = components['schemas']['MeView'];

/** Lôžko vybrané v pláne (pravé tlačidlo na voľnom lôžku a dni → „Nová rezervácia sem…“). */
export interface NewReservationPrefill {
  receptionId?: number;
  roomId?: number;
  bedId: number;
  extra?: boolean;
  categoryId?: number;
  roomLabel: string;
  bedLabel: string;
  day: string;
}

/** `location.state` formulára: odkiaľ sa otvoril (`origin`) a predvyplnené lôžko. */
export interface NewReservationState extends NavState {
  prefill?: NewReservationPrefill;
}

interface BedChoice {
  bedId: number;
  extra: boolean;
  label: string;
}

const has = (me: Me | null | undefined, code: string) => (me?.permissions ?? []).some((p) => p.code === code);

/** Prevedie `guests[0].lastName` zo servera na `guests.0.lastName`; neznáme polia vráti `undefined`. */
const FIELDS = ['arrivalDate', 'departureDate', 'adults', 'children', 'note'] as const;
function formField(field: string): string | undefined {
  const g = /^guests\[(\d+)\]\.(\w+)$/.exec(field);
  if (g) return `guests.${g[1]}.${g[2]}`;
  if (field === 'bookedCategoryId') return 'categoryId';
  if (field === 'attendingDoctorId') return 'doctorId';
  return (FIELDS as readonly string[]).includes(field) ? field : field === 'payerId' ? 'payerId' : undefined;
}

/**
 * Nová rezervačná karta (UC1–UC4): 1. Hostia, 2. Pobyt, 3. Lôžko, 4. Poznámka + súhrn vpravo.
 * „Uložiť a prideliť lôžko“ → `POST /api/reservations/with-stay`, „Uložiť len kartu“ → `POST /api/reservations`.
 */
export function NewReservationPage({ me }: { me: Me | null | undefined }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { canWrite } = useConnection();
  const location = useLocation();
  const origin = readOrigin(location.state);
  const prefill = (location.state as NewReservationState | null)?.prefill;
  const canAssign = has(me, 'STAY_CREATE');
  const canDoctor = has(me, 'DOCTOR_ASSIGN');

  const form = useForm<ReservationForm>({
    resolver: zodResolver(reservationFormSchema),
    defaultValues: {
      guests: [],
      primaryIndex: 0,
      arrivalDate: prefill?.day ?? '',
      departureDate: prefill?.day ? addDays(prefill.day, 1) : '',
      categoryId: prefill?.categoryId ? String(prefill.categoryId) : '',
      adults: 1,
      children: 0,
      payerId: '',
      doctorId: '',
      earlyCheckIn: false,
      lateCheckOut: false,
      exclusiveRoomUse: false,
      doNotMove: false,
      note: '',
      noteInPlan: false,
    },
  });
  const { register, control, handleSubmit, setError, formState } = form;
  const { errors, isDirty } = formState;
  const guests = useFieldArray({ control, name: 'guests' });
  const v = useWatch({ control }) as ReservationForm; // všetky polia majú východiskové hodnoty
  const nights = nightsBetween(v.arrivalDate, v.departureDate);
  const err = (key?: string) => (key ? t(`newReservation.errors.${key}`, { defaultValue: key }) : undefined);

  // --- lôžko: z plánu / iné voľné / neprideľovať ---
  const planned: BedChoice | undefined = prefill && {
    bedId: prefill.bedId,
    extra: prefill.extra === true,
    label: `${prefill.roomLabel}, ${t(prefill.extra ? 'plan.extraBed' : 'plan.bed', { n: prefill.bedLabel })}`,
  };
  const [bedMode, setBedMode] = useState<'planned' | 'other' | 'none'>(planned ? 'planned' : 'none');
  const [other, setOther] = useState<BedChoice | undefined>();
  const bed = bedMode === 'planned' ? planned : bedMode === 'other' ? other : undefined;

  // --- číselníky a voľné lôžka ---
  const categories = useQuery({ queryKey: ['reservations', 'new', 'categories'], queryFn: loadCategories });
  const payers = useQuery({ queryKey: ['reservations', 'new', 'payers'], queryFn: loadPayers });
  const categoryId = Number(v.categoryId);
  const datesOk = nights > 0 && categoryId > 0;
  const available = useQuery({
    queryKey: ['reservations', 'new', 'available', v.arrivalDate, v.departureDate, categoryId],
    queryFn: () => loadAvailable(v.arrivalDate, v.departureDate, categoryId),
    enabled: datesOk,
  });
  const freeCount = (available.data ?? []).reduce((n, r) => n + (r.freeBeds?.length ?? 0), 0);
  const plannedFree =
    planned && available.data ? available.data.some((r) => r.freeBeds?.some((b) => b.id === planned.bedId)) : undefined;
  const categoryName = (categories.data ?? []).find((c) => c.id === categoryId)?.name;

  // --- uloženie ---
  const [warnings, setWarnings] = useState<string[]>([]);
  const [pending, setPending] = useState<{ values: ReservationForm; withBed: boolean } | null>(null);
  const [error, setError2] = useState<string>();

  const toBody = (x: ReservationForm): CreateCardBody => {
    const order = [x.primaryIndex, ...x.guests.map((_, i) => i).filter((i) => i !== x.primaryIndex)];
    return {
      arrivalDate: x.arrivalDate,
      departureDate: x.departureDate,
      bookedCategoryId: Number(x.categoryId),
      payerId: Number(x.payerId),
      adults: x.adults,
      children: x.children,
      exclusiveRoomUse: x.exclusiveRoomUse,
      guests: order.map((i) => {
        const g = x.guests[i];
        return {
          clientId: g.clientId,
          firstName: g.firstName.trim(),
          lastName: g.lastName.trim(),
          birthDate: g.birthDate || undefined,
          email: g.email || undefined,
          phone: g.phone || undefined,
        };
      }),
      attendingDoctorId: canDoctor && x.doctorId ? Number(x.doctorId) : undefined,
      earlyCheckIn: x.earlyCheckIn,
      lateCheckOut: x.lateCheckOut,
      doNotMove: x.doNotMove,
      note: x.note.trim() || undefined,
      noteInPlan: x.note.trim() ? x.noteInPlan : false,
    };
  };

  const allowLeave = useRef(false);
  const save = useMutation({
    mutationFn: async (a: { values: ReservationForm; withBed: boolean; confirm: string[] }) => {
      const body = toBody(a.values);
      if (a.withBed && bed) {
        return createCardWithStay({ ...body, bedId: bed.bedId, extraBed: bed.extra, ...(a.confirm.length ? { confirm: a.confirm } : {}) });
      }
      return createCard(body);
    },
    onSuccess: (res) => {
      setWarnings([]);
      if (res.auditEventId !== undefined) markEventHandled(res.auditEventId);
      for (const key of ['reservations', 'plan', 'movements']) void qc.invalidateQueries({ queryKey: [key] });
      allowLeave.current = true;
      // Späť z detailu vedie tam, odkiaľ sa formulár otvoril (formulár z histórie nahradíme)
      void navigate(`/reservations/${res.reservationId}`, { replace: true, state: origin ? ({ origin } satisfies NavState) : undefined });
    },
    onError: (e, a) => {
      if (e instanceof ApiError && e.status === 409 && e.confirmable) {
        setPending({ values: a.values, withBed: a.withBed });
        setWarnings(e.warnings);
        return;
      }
      setWarnings([]);
      setPending(null);
      let general = true;
      if (e instanceof ApiError) {
        for (const fv of e.fieldViolations) {
          const f = formField(fv.field);
          if (f) {
            setError(f as 'note', { message: fv.message });
            general = false;
          }
        }
      }
      setError2(general ? errorText(t, e) : t('newReservation.fixFields'));
      if (e instanceof ApiError && e.status === 409) {
        // obsadené lôžko: karta sa nezaložila; obnoviť voľné lôžka aj plán
        void qc.invalidateQueries({ queryKey: ['reservations', 'new', 'available'] });
        void qc.invalidateQueries({ queryKey: ['plan'] });
      }
    },
  });

  const submit = (withBed: boolean) =>
    handleSubmit((values) => {
      setError2(undefined);
      save.mutate({ values, withBed, confirm: [] });
    })();

  // --- odchod z rozpísaného formulára ---
  const [leave, setLeave] = useState<(() => void) | null>(null);
  const confirmLeave = (proceed: () => void) => (isDirty && !allowLeave.current ? setLeave(() => proceed) : proceed());
  useEffect(() => {
    if (!isDirty) return;
    const warn = (e: BeforeUnloadEvent) => e.preventDefault();
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [isDirty]);
  const cancel = () => confirmLeave(() => void navigate(origin?.to ?? '/reservations', { replace: true }));

  return (
    <Paper withBorder p="md" radius="lg" bg="white" data-testid="new-reservation">
      <PageHeader
        title={t('newReservation.title')}
        fallback={origin?.to ?? '/reservations'}
        confirmLeave={confirmLeave}
        crumbs={[origin ?? { label: t('menu.reservations'), to: '/reservations' }, { label: t('newReservation.title') }]}
      />
      {planned && prefill && (
        <Text size="sm" c="dimmed" mb="sm" data-testid="new-reservation-from-plan">
          {t('newReservation.fromPlan', { place: planned.label, day: prefill.day })}
        </Text>
      )}
      {!canWrite && <Alert color="orange" mb="md" data-testid="new-reservation-offline">{t('newReservation.offline')}</Alert>}
      <form noValidate onSubmit={(e) => e.preventDefault()}>
        <Group align="flex-start" wrap="nowrap" gap="lg">
          <Stack gap="lg" style={{ flex: 1, minWidth: 0 }}>
            <GuestsSection form={form} guests={guests} err={err} />

            <Stack gap="xs" data-testid="section-stay">
              <Title order={4}>{t('newReservation.stay')}</Title>
              <Group align="flex-start">
                <TextInput type="date" label={t('stays.from')} {...register('arrivalDate')} error={err(errors.arrivalDate?.message)} />
                <TextInput type="date" label={t('stays.to')} {...register('departureDate')} error={err(errors.departureDate?.message)} />
                <Stack gap={2}>
                  <Text size="sm" fw={500}>{t('newReservation.nightsLabel')}</Text>
                  <Badge variant="light" size="lg" data-testid="new-nights">{nights}</Badge>
                </Stack>
              </Group>
              <Group align="flex-start">
                <Controller
                  control={control}
                  name="categoryId"
                  render={({ field }) => (
                    <NativeSelect
                      label={t('newReservation.category')}
                      data={[{ value: '', label: '–' }, ...(categories.data ?? []).map((c) => ({ value: String(c.id), label: `${c.code ?? ''} – ${c.name ?? ''}` }))]}
                      value={field.value}
                      onChange={(e) => field.onChange(e.currentTarget.value)}
                      onBlur={field.onBlur}
                      error={err(errors.categoryId?.message)}
                    />
                  )}
                />
                <TextInput type="number" min={1} label={t('newReservation.adults')} w={110} {...register('adults', { valueAsNumber: true })} error={err(errors.adults?.message)} />
                <TextInput type="number" min={0} label={t('newReservation.children')} w={110} {...register('children', { valueAsNumber: true })} error={err(errors.children?.message)} />
                <Controller
                  control={control}
                  name="payerId"
                  render={({ field }) => (
                    <NativeSelect
                      label={t('newReservation.payer')}
                      data={[{ value: '', label: '–' }, ...(payers.data ?? []).filter((p) => p.active !== false).map((p) => ({ value: String(p.id), label: p.name ?? String(p.id) }))]}
                      value={field.value}
                      onChange={(e) => field.onChange(e.currentTarget.value)}
                      onBlur={field.onBlur}
                      error={err(errors.payerId?.message)}
                    />
                  )}
                />
                {canDoctor && (
                  <TextInput label={t('newReservation.doctor')} w={140} inputMode="numeric" {...register('doctorId')} error={err(errors.doctorId?.message)} />
                )}
              </Group>
              <Group>
                <Checkbox label={t('newReservation.earlyCheckIn')} {...register('earlyCheckIn')} />
                <Checkbox label={t('newReservation.lateCheckOut')} {...register('lateCheckOut')} />
                <Checkbox label={t('newReservation.exclusiveRoomUse')} {...register('exclusiveRoomUse')} />
                <Checkbox label={t('newReservation.doNotMove')} {...register('doNotMove')} />
              </Group>
            </Stack>

            {canAssign && (
              <Stack gap="xs" data-testid="section-bed">
                <Title order={4}>{t('newReservation.bed')}</Title>
                <Radio.Group value={bedMode} onChange={(m) => setBedMode(m as typeof bedMode)} aria-label={t('newReservation.bed')}>
                  <Stack gap="xs">
                    {planned && (
                      <Radio
                        value="planned"
                        label={
                          <span data-testid="bed-planned">
                            {planned.label}
                            {plannedFree === true && ` · ${t('newReservation.bedFree')}`}
                          </span>
                        }
                      />
                    )}
                    <Radio value="other" label={t('newReservation.bedOther')} />
                    <Radio value="none" label={t('newReservation.bedNone')} />
                  </Stack>
                </Radio.Group>
                {bedMode === 'planned' && plannedFree === false && (
                  <Alert color="orange" data-testid="bed-planned-busy">{t('newReservation.plannedBusy')}</Alert>
                )}
                {bedMode === 'other' && (
                  <Stack gap="xs" pl="md">
                    {!datesOk && <Text size="sm" c="dimmed">{t('newReservation.bedNeedsStay')}</Text>}
                    {available.isFetching && <Loader size="sm" />}
                    {datesOk && available.data?.length === 0 && <Text size="sm" c="dimmed">{t('stays.noRooms')}</Text>}
                    <Radio.Group
                      value={other ? String(other.bedId) : ''}
                      onChange={(id) => {
                        for (const r of available.data ?? []) {
                          const b = r.freeBeds?.find((x) => String(x.id) === id);
                          if (b?.id !== undefined) {
                            const extra = b.type === 'EXTRA';
                            setOther({ bedId: b.id, extra, label: `${r.buildingName} ${r.roomNumber}, ${t(extra ? 'plan.extraBed' : 'plan.bed', { n: b.bedNumber })}` });
                          }
                        }
                      }}
                      aria-label={t('stays.pickBed')}
                    >
                      <Stack gap="xs">
                        {(available.data ?? []).map((r) => (
                          <Stack gap={2} key={r.roomId} data-testid="new-room">
                            <Text size="sm" fw={600}>{r.buildingName} {r.roomNumber} · {r.categoryName}</Text>
                            <Group gap="md" pl="sm">
                              {(r.freeBeds ?? []).map((b) => (
                                <Radio key={b.id} value={String(b.id)} label={t(b.type === 'EXTRA' ? 'plan.extraBed' : 'plan.bed', { n: b.bedNumber })} />
                              ))}
                            </Group>
                          </Stack>
                        ))}
                      </Stack>
                    </Radio.Group>
                  </Stack>
                )}
              </Stack>
            )}

            <Stack gap="xs">
              <Title order={4}>{t('newReservation.noteTitle')}</Title>
              <Textarea rows={3} label={t('newReservation.note')} {...register('note')} error={err(errors.note?.message)} />
              <Checkbox label={t('newReservation.noteInPlan')} {...register('noteInPlan')} />
            </Stack>
          </Stack>

          <Paper withBorder p="md" radius="md" w={280} data-testid="new-summary" style={{ flex: 'none' }}>
            <Stack gap="xs">
              <Title order={4}>{t('newReservation.summary')}</Title>
              <Text size="sm" data-testid="summary-guests">
                {v.guests.length === 0 ? '–' : v.guests.map((g) => `${g.firstName} ${g.lastName}`.trim() || '…').join(', ')}
              </Text>
              <Text size="sm" data-testid="summary-term">
                {v.arrivalDate || '…'} – {v.departureDate || '…'} ({t('stays.nights', { count: nights })})
              </Text>
              {categoryName && <Text size="sm">{t('newReservation.category')}: {categoryName}</Text>}
              <Text size="sm" data-testid="summary-bed">{t('newReservation.bed')}: {bed?.label ?? t('newReservation.bedNone')}</Text>
              {datesOk && (
                <Text size="sm" c={available.data && freeCount === 0 ? 'red' : undefined} data-testid="summary-free">
                  {available.isFetching ? '…' : available.isError ? t('errors.generic') : t('newReservation.freeBeds', { count: freeCount })}
                </Text>
              )}
              {error && <Alert color="red" data-testid="new-reservation-error">{error}</Alert>}
              {canAssign && (
                <WriteAction>
                  <Button
                    onClick={() => void submit(true)}
                    loading={save.isPending && warnings.length === 0}
                    disabled={!bed}
                    data-testid="save-with-stay"
                  >
                    {t('newReservation.saveWithBed')}
                  </Button>
                </WriteAction>
              )}
              <WriteAction>
                <Button variant="light" onClick={() => void submit(false)} loading={save.isPending && warnings.length === 0} data-testid="save-card">
                  {t('newReservation.saveCard')}
                </Button>
              </WriteAction>
              <Button variant="subtle" onClick={cancel} data-testid="new-cancel">{t('newReservation.cancel')}</Button>
            </Stack>
          </Paper>
        </Group>
      </form>

      <ConfirmWarningsDialog
        warnings={warnings}
        loading={save.isPending}
        onConfirm={() => pending && save.mutate({ ...pending, confirm: warnings })}
        onCancel={() => { setWarnings([]); setPending(null); }}
      />
      <Modal opened={leave !== null} onClose={() => setLeave(null)} title={t('newReservation.leaveTitle')} centered>
        <Text size="sm" mb="md">{t('newReservation.leaveText')}</Text>
        <Group justify="flex-end">
          <Button variant="default" onClick={() => setLeave(null)} data-testid="leave-stay">{t('newReservation.leaveStay')}</Button>
          <Button color="red" onClick={() => { const go = leave; setLeave(null); go?.(); }} data-testid="leave-discard">{t('newReservation.leaveDiscard')}</Button>
        </Group>
      </Modal>
    </Paper>
  );
}

type FormApi = ReturnType<typeof useForm<ReservationForm>>;

/** 1. Hostia: vyhľadanie existujúceho klienta (`GET /api/clients`), nový klient, hlavný hosť. */
function GuestsSection({
  form,
  guests,
  err,
}: {
  form: FormApi;
  guests: ReturnType<typeof useFieldArray<ReservationForm, 'guests'>>;
  err: (k?: string) => string | undefined;
}) {
  const { t } = useTranslation();
  const [draft, setDraft] = useState('');
  const [term, setTerm] = useState('');
  const { register, watch, setValue, formState } = form;
  const primary = watch('primaryIndex');
  const rows = watch('guests');
  const found = useQuery({ queryKey: ['reservations', 'new', 'clients', term], queryFn: () => searchClients(term), enabled: term.trim().length >= 2 });
  const guestsError = formState.errors.guests?.message ?? formState.errors.guests?.root?.message;

  const add = (g: Partial<ReservationForm['guests'][number]>) =>
    guests.append({ firstName: '', lastName: '', birthDate: '', email: '', phone: '', ...g });
  const remove = (i: number) => {
    guests.remove(i);
    setValue('primaryIndex', i === primary ? 0 : i < primary ? primary - 1 : primary, { shouldDirty: true });
  };

  return (
    <Stack gap="xs" data-testid="section-guests">
      <Title order={4}>{t('newReservation.guests')}</Title>
      <Group align="flex-end">
        <TextInput
          label={t('newReservation.searchClient')}
          placeholder={t('newReservation.searchClientPlaceholder')}
          value={draft}
          onChange={(e) => setDraft(e.currentTarget.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault();
              setTerm(draft);
            }
          }}
          style={{ flex: 1 }}
        />
        <Button variant="default" onClick={() => setTerm(draft)}>{t('reservations.search')}</Button>
      </Group>
      {found.isFetching && <Loader size="sm" />}
      {found.isError && <Alert color="red">{errorText(t, found.error)}</Alert>}
      {found.data?.length === 0 && <Text size="sm" c="dimmed">{t('newReservation.noClients')}</Text>}
      {found.data?.map((c) => {
        const added = rows.some((g) => g.clientId === c.id);
        return (
          <Group key={c.id} justify="space-between" data-testid="client-hit">
            <Text size="sm">
              {c.firstName} {c.lastName}
              {c.birthYear ? ` ${c.birthYear}` : ''}
              {c.lastStayDate ? ` · ${t('newReservation.lastStay', { date: c.lastStayDate })}` : ''}
            </Text>
            <Button
              size="compact-sm"
              variant="light"
              disabled={added}
              onClick={() => add({ clientId: c.id, firstName: c.firstName ?? '', lastName: c.lastName ?? '', birthYear: c.birthYear })}
            >
              {t('newReservation.addClient')}
            </Button>
          </Group>
        );
      })}

      <Radio.Group value={String(primary)} onChange={(x) => setValue('primaryIndex', Number(x), { shouldDirty: true })} aria-label={t('newReservation.primary')}>
        <Stack gap="xs">
          {guests.fields.map((f, i) => {
            const row = rows[i];
            const ge = formState.errors.guests?.[i];
            return (
              <Group key={f.id} align="flex-start" data-testid="guest-form-row">
                <Radio value={String(i)} label={t('newReservation.primary')} mt={26} />
                {row?.clientId !== undefined ? (
                  <Text size="sm" mt={26} data-testid="guest-existing">
                    {row.firstName} {row.lastName}{row.birthYear ? ` ${row.birthYear}` : ''}
                  </Text>
                ) : (
                  <>
                    <TextInput label={t('newReservation.firstName')} {...register(`guests.${i}.firstName`)} error={err(ge?.firstName?.message)} />
                    <TextInput label={t('newReservation.lastName')} {...register(`guests.${i}.lastName`)} error={err(ge?.lastName?.message)} />
                    <TextInput type="date" label={t('newReservation.birthDate')} {...register(`guests.${i}.birthDate`)} error={err(ge?.birthDate?.message)} />
                    <TextInput label={t('newReservation.email')} {...register(`guests.${i}.email`)} error={err(ge?.email?.message)} />
                    <TextInput label={t('newReservation.phone')} {...register(`guests.${i}.phone`)} error={err(ge?.phone?.message)} />
                  </>
                )}
                <Button variant="subtle" color="red" size="compact-sm" mt={26} onClick={() => remove(i)}>{t('newReservation.removeGuest')}</Button>
              </Group>
            );
          })}
        </Stack>
      </Radio.Group>
      {guestsError && <Text size="sm" c="red" data-testid="guests-error">{err(guestsError)}</Text>}
      <div>
        <Button variant="default" size="compact-md" onClick={() => add({})}>{t('newReservation.newClient')}</Button>
      </div>
    </Stack>
  );
}
