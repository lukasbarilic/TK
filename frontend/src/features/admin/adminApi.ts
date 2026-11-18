import { apiCall } from '../../shared/api/client';
import type { components } from '../../shared/api/schema';

type S = components['schemas'];
export type AdminUser = S['UserView'];
export type RoleAssignment = S['RoleAssignmentInput'];
export type RoleDef = S['RoleView'];
export type CreateUserBody = S['CreateUserRequest'];
export type UpdateUserBody = S['UpdateUserRequest'];

export const listUsers = async (): Promise<AdminUser[]> => (await apiCall('get', '/api/admin/users')).data;
export const listRoles = async (): Promise<RoleDef[]> => (await apiCall('get', '/api/admin/roles')).data;
export const listCentres = async () => (await apiCall('get', '/api/catalog/centres')).data;
export const listReceptions = async () => (await apiCall('get', '/api/catalog/receptions')).data;

export const createUser = async (body: CreateUserBody) => (await apiCall('post', '/api/admin/users', { body })).data;
export const updateUser = async (id: number, body: UpdateUserBody) =>
  (await apiCall('patch', '/api/admin/users/{id}', { path: { id }, body })).data;
export const assignRoles = async (id: number, roles: RoleAssignment[]) =>
  (await apiCall('put', '/api/admin/users/{id}/roles', { path: { id }, body: { roles } })).data;
export const resetPassword = async (id: number) =>
  (await apiCall('post', '/api/admin/users/{id}/reset-password', { path: { id } })).data;
export const unlockUser = async (id: number) =>
  (await apiCall('post', '/api/admin/users/{id}/unlock', { path: { id } })).data;
