import { inject, Injectable, InjectionToken, signal } from '@angular/core';
import {
  createClient,
  Session,
  SupabaseClient,
  User,
} from '@supabase/supabase-js';
import { JwtClaims, UserProfile } from '../../models/user.model';
import { UserApiService } from '../../services/user-api.service';
import { UserContextService } from './user-context.service';

export interface SupabaseAuthConfiguration {
  supabaseUrl: string;
  supabaseAnonKey: string;
}

const defaultSupabaseUrl = 'https://txyyirobwnomhxygbacq.supabase.co';
const defaultSupabaseAnonKey =
  'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InR4eXlpcm9id25vbWh4eWdiYWNxIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODc4OTA5NjIsImV4cCI6MjEwMzQ2Njk2Mn0.e6hzLPiDkCJSA9ZKp9y_TcCzrymmptvAd3ly2bbouNc';

function readRuntimeValue(name: string, fallback: string): string {
  const globalObject = globalThis as Record<string, unknown>;
  const envWrapper = (globalObject['__env'] ?? globalObject['env']) as
    | Record<string, unknown>
    | undefined;
  const value = globalObject[name] ?? envWrapper?.[name];
  return typeof value === 'string' && value.trim() ? value.trim() : fallback;
}

export const SUPABASE_AUTH_CONFIG = new InjectionToken<SupabaseAuthConfiguration>(
  'SUPABASE_AUTH_CONFIG',
  {
    providedIn: 'root',
    factory: () => ({
      supabaseUrl: readRuntimeValue('SUPABASE_URL', defaultSupabaseUrl),
      supabaseAnonKey: readRuntimeValue('SUPABASE_ANON_KEY', defaultSupabaseAnonKey),
    }),
  },
);

export const SUPABASE_CLIENT = new InjectionToken<SupabaseClient>('SUPABASE_CLIENT', {
  providedIn: 'root',
  factory: () => {
    const config = inject(SUPABASE_AUTH_CONFIG);
    return createClient(config.supabaseUrl, config.supabaseAnonKey, {
      auth: {
        persistSession: true,
        autoRefreshToken: true,
        detectSessionInUrl: true,
      },
    });
  },
});

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly userContext = inject(UserContextService);
  private readonly userApi = inject(UserApiService);
  private readonly supabase = inject(SUPABASE_CLIENT);
  private readonly accessToken = signal<string | null>(null);
  private readonly initialization: Promise<void>;
  private provisioningUserId: string | null = null;
  private provisionedUserId: string | null = null;

  readonly isReady = signal(false);
  readonly lastError = signal<string | null>(null);

  constructor() {
    this.initialization = this.initializeSession();
  }

  initialize(): Promise<void> {
    return this.initialization;
  }

  getToken(): string | null {
    const token = this.accessToken();
    if (!token) {
      return null;
    }

    const claims = this.decodeJwt(token);
    const nowInSeconds = Math.floor(Date.now() / 1000);
    if (!claims || (claims.exp !== undefined && claims.exp <= nowInSeconds + 30)) {
      this.accessToken.set(null);
      return null;
    }

    return token;
  }

  async login(email: string, password: string): Promise<void> {
    await this.initialization;
    this.lastError.set(null);

    const { data, error } = await this.supabase.auth.signInWithPassword({
      email,
      password,
    });

    if (error) {
      this.lastError.set(error.message);
      throw error;
    }

    if (data.session) {
      this.syncSession(data.session);
    }
  }

  async signUp(email: string, password: string, name?: string): Promise<void> {
    await this.initialization;
    this.lastError.set(null);

    const { data, error } = await this.supabase.auth.signUp({
      email,
      password,
      options: {
        data: name ? { name } : undefined,
      },
    });

    if (error) {
      this.lastError.set(error.message);
      throw error;
    }

    if (data.session) {
      this.syncSession(data.session);
    }
  }

  async signInWithOAuth(provider: 'google' | 'github'): Promise<void> {
    await this.initialization;
    this.lastError.set(null);

    const { error } = await this.supabase.auth.signInWithOAuth({
      provider,
      options: {
        redirectTo: `${window.location.origin}/auth/callback`,
        queryParams: {
          prompt: 'select_account',
        },
      },
    });

    if (error) {
      this.lastError.set(error.message);
      throw error;
    }
  }

  async logout(): Promise<void> {
    await this.initialization;
    this.clearSession();

    const { error } = await this.supabase.auth.signOut();
    if (error) {
      this.lastError.set(error.message);
      throw error;
    }
  }

  async resetPasswordForEmail(email: string, redirectTo?: string): Promise<void> {
    await this.initialization;
    this.lastError.set(null);
    const redirectUrl = redirectTo ?? `${window.location.origin}/auth/reset-password`;
    const { error } = await this.supabase.auth.resetPasswordForEmail(email, {
      redirectTo: redirectUrl,
    });
    if (error) {
      this.lastError.set(error.message);
      throw error;
    }
  }

  async updatePassword(newPassword: string): Promise<void> {
    await this.initialization;
    this.lastError.set(null);
    const { data, error } = await this.supabase.auth.updateUser({ password: newPassword });
    if (error) {
      this.lastError.set(error.message);
      throw error;
    }
    if (data.user) {
      const { data: sessionData } = await this.supabase.auth.getSession();
      if (sessionData.session) {
        this.syncSession(sessionData.session);
      }
    }
  }

  private async initializeSession(): Promise<void> {
    try {
      this.supabase.auth.onAuthStateChange((_event, session) => {
        if (session) {
          this.syncSession(session);
        } else {
          this.clearSession();
        }
      });

      const { data, error } = await this.supabase.auth.getSession();
      if (error) {
        this.clearSession();
        this.lastError.set(error.message);
      } else if (data.session) {
        this.syncSession(data.session);
      } else {
        this.clearSession();
      }
    } catch (error: unknown) {
      this.clearSession();
      this.lastError.set(error instanceof Error ? error.message : 'Session initialization failed');
    } finally {
      this.isReady.set(true);
    }
  }

  syncSession(session: Session): void {
    const token = session.access_token;
    this.accessToken.set(token);

    const claims = token ? this.decodeJwt(token) : null;
    const user = session.user;

    this.syncUserFromSession(claims, user);

    const userId = user?.id ?? claims?.sub;
    if (userId) {
      this.ensureSeatFlowUserProvisioned(userId);
    }
  }

  private ensureSeatFlowUserProvisioned(userId: string): void {
    if (this.provisionedUserId === userId || this.provisioningUserId === userId) {
      return;
    }

    this.provisioningUserId = userId;
    this.userApi.getProfile().subscribe({
      next: () => {
        this.provisionedUserId = userId;
        if (this.provisioningUserId === userId) {
          this.provisioningUserId = null;
        }
      },
      error: () => {
        if (this.provisioningUserId === userId) {
          this.provisioningUserId = null;
        }
      },
    });
  }

  private syncUserFromSession(claims: JwtClaims | null, user: User | null): void {
    if (!user && !claims?.sub) {
      this.clearSession();
      return;
    }

    const rawRoles: string[] = [];

    // 1. Check claims app_metadata
    if (claims?.app_metadata && Array.isArray(claims.app_metadata.roles)) {
      rawRoles.push(...claims.app_metadata.roles);
    } else if (claims?.roles && Array.isArray(claims.roles)) {
      rawRoles.push(...claims.roles);
    }

    // 2. Check user app_metadata / user_metadata from Supabase User object
    if (user?.app_metadata && Array.isArray(user.app_metadata['roles'])) {
      rawRoles.push(...(user.app_metadata['roles'] as string[]));
    }
    if (user?.user_metadata && Array.isArray(user.user_metadata['roles'])) {
      rawRoles.push(...(user.user_metadata['roles'] as string[]));
    }

    let roles = Array.from(new Set(rawRoles)).map((role) =>
      role.startsWith('ROLE_') ? role : `ROLE_${role}`,
    );

    if (roles.length === 0) {
      roles = ['ROLE_CUSTOMER'];
    }

    const userName =
      (user?.user_metadata?.['name'] as string | undefined) ??
      claims?.name ??
      user?.email ??
      claims?.email ??
      'User';

    const userProfile: UserProfile = {
      id: user?.id ?? claims?.sub ?? '',
      email: user?.email ?? claims?.email ?? '',
      name: userName,
      roles,
    };

    this.userContext.setUser(userProfile);
  }

  private clearSession(): void {
    this.accessToken.set(null);
    this.provisioningUserId = null;
    this.provisionedUserId = null;
    this.userContext.clearUser();
  }

  decodeJwt(token: string): JwtClaims | null {
    const payload = token.split('.')[1];
    if (!payload) {
      return null;
    }

    try {
      const normalized = payload.replace(/-/g, '+').replace(/_/g, '/');
      const padded = normalized.padEnd(Math.ceil(normalized.length / 4) * 4, '=');
      const bytes = Uint8Array.from(atob(padded), (character) => character.charCodeAt(0));
      return JSON.parse(new TextDecoder().decode(bytes)) as JwtClaims;
    } catch {
      return null;
    }
  }
}
