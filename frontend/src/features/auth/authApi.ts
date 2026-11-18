import { apiCall } from '../../shared/api/client';
import { ApiError } from '../../shared/api/errors';

export async function fetchMe() {
  return (await apiCall('get', '/api/me')).data;
}

export async function login(username: string, password: string) {
  return (await apiCall('post', '/api/auth/login', { body: { username, password } })).data;
}

export async function logout() {
  await apiCall('post', '/api/auth/logout');
}

export async function changePassword(currentPassword: string, newPassword: string) {
  await apiCall('post', '/api/me/password', { body: { currentPassword, newPassword } });
}

export const isUnauthorized = (e: unknown): boolean => e instanceof ApiError && e.status === 401;
