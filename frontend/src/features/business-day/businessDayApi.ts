import { apiCall } from '../../shared/api/client';
import type { components } from '../../shared/api/schema';

export type ClosePreview = components['schemas']['Preview'];
export type Finding = components['schemas']['FindingView'];

/** Kontrola pred uzávierkou (nič nezapisuje, len vypočíta zistenia). */
export const previewClose = async (receptionId: number): Promise<ClosePreview> =>
  (await apiCall('post', '/api/receptions/{id}/business-day/close/preview', { path: { id: receptionId } })).data;

export const closeBusinessDay = async (receptionId: number) =>
  (await apiCall('post', '/api/receptions/{id}/business-day/close', { path: { id: receptionId } })).data;

export type DayReport = components['schemas']['BusinessDayReport'];
export type ArchiveItem = components['schemas']['ArchiveItem'];

/** Zoznam uzavretých dní s uloženým reportom (od najnovšieho podľa servera). */
export const listReports = async (receptionId: number): Promise<ArchiveItem[]> =>
  (await apiCall('get', '/api/receptions/{id}/business-day/reports', { path: { id: receptionId }, query: {} })).data;

export const getArchivedReport = async (receptionId: number, date: string) =>
  (await apiCall('get', '/api/receptions/{id}/business-day/reports/{date}', { path: { id: receptionId, date } })).data;
