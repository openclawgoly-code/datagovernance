import { http } from './request'
import type {
  LoginRequest,
  LoginResult,
  SwitchWorkspaceRequest,
  SwitchWorkspaceResult,
  MeResult,
} from '@/types/auth'

export function login(payload: LoginRequest): Promise<LoginResult> {
  return http.post<LoginResult>('/auth/login', payload)
}

export function switchWorkspace(payload: SwitchWorkspaceRequest): Promise<SwitchWorkspaceResult> {
  return http.post<SwitchWorkspaceResult>('/auth/switch-workspace', payload)
}

export function fetchMe(): Promise<MeResult> {
  return http.get<MeResult>('/auth/me')
}

export function logout(): Promise<void> {
  return http.post<void>('/auth/logout')
}
