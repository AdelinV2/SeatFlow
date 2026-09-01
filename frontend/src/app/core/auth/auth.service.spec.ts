import { TestBed } from '@angular/core/testing';
import { Session, SupabaseClient, User } from '@supabase/supabase-js';
import {
  AuthService,
  SUPABASE_AUTH_CONFIG,
  SUPABASE_CLIENT,
  SupabaseAuthConfiguration,
} from './auth.service';
import { UserContextService } from './user-context.service';

describe('AuthService', () => {
  let supabaseMock: any;
  let authStateCallback: ((event: string, session: Session | null) => void) | null = null;

  const authConfig: SupabaseAuthConfiguration = {
    supabaseUrl: 'https://test-project.supabase.co',
    supabaseAnonKey: 'test-anon-key',
  };

  beforeEach(() => {
    authStateCallback = null;
    supabaseMock = {
      auth: {
        getSession: jasmine.createSpy('getSession').and.resolveTo({
          data: { session: null },
          error: null,
        }),
        onAuthStateChange: jasmine
          .createSpy('onAuthStateChange')
          .and.callFake((callback: (event: string, session: Session | null) => void) => {
            authStateCallback = callback;
            return {
              data: { subscription: { unsubscribe: jasmine.createSpy('unsubscribe') } },
            };
          }),
        signInWithPassword: jasmine.createSpy('signInWithPassword').and.resolveTo({
          data: { session: null, user: null },
          error: null,
        }),
        signUp: jasmine.createSpy('signUp').and.resolveTo({
          data: { session: null, user: null },
          error: null,
        }),
        signInWithOAuth: jasmine.createSpy('signInWithOAuth').and.resolveTo({
          data: { provider: 'google', url: null },
          error: null,
        }),
        resetPasswordForEmail: jasmine.createSpy('resetPasswordForEmail').and.resolveTo({
          data: {},
          error: null,
        }),
        updateUser: jasmine.createSpy('updateUser').and.resolveTo({
          data: { user: null },
          error: null,
        }),
        signOut: jasmine.createSpy('signOut').and.resolveTo({ error: null }),
      },
    };
  });

  afterEach(() => TestBed.resetTestingModule());

  it('parses Supabase JWT claims and normalizes roles into the user context', async () => {
    const token = createJwt({
      sub: 'user-123',
      email: 'alex@seatflow.test',
      name: 'Alex Morgan',
      app_metadata: {
        roles: ['CUSTOMER', 'ROLE_STAFF'],
      },
      exp: futureExpiration(),
    });

    const session: Session = {
      access_token: token,
      refresh_token: 'refresh-token',
      expires_in: 3600,
      token_type: 'bearer',
      user: {
        id: 'user-123',
        email: 'alex@seatflow.test',
        app_metadata: { roles: ['CUSTOMER', 'ROLE_STAFF'] },
        user_metadata: { name: 'Alex Morgan' },
        aud: 'authenticated',
        created_at: new Date().toISOString(),
      } as User,
    };

    supabaseMock.auth.getSession.and.resolveTo({
      data: { session },
      error: null,
    });

    const service = createService();
    await service.initialize();
    const userContext = TestBed.inject(UserContextService);

    expect(service.getToken()).toBe(token);
    expect(service.isReady()).toBeTrue();
    expect(userContext.currentUser()?.roles).toEqual(['ROLE_CUSTOMER', 'ROLE_STAFF']);
    expect(userContext.isStaff()).toBeTrue();
    expect(userContext.userName()).toBe('Alex Morgan');
  });

  it('defaults empty roles to ROLE_CUSTOMER aligning with backend convention', async () => {
    const token = createJwt({
      sub: 'user-123',
      email: 'alex@seatflow.test',
      app_metadata: { roles: [] },
      exp: futureExpiration(),
    });

    const session: Session = {
      access_token: token,
      refresh_token: 'refresh-token',
      expires_in: 3600,
      token_type: 'bearer',
      user: {
        id: 'user-123',
        email: 'alex@seatflow.test',
        app_metadata: { roles: [] },
        user_metadata: {},
        aud: 'authenticated',
        created_at: new Date().toISOString(),
      } as User,
    };

    supabaseMock.auth.getSession.and.resolveTo({
      data: { session },
      error: null,
    });

    const service = createService();
    await service.initialize();
    const userContext = TestBed.inject(UserContextService);

    expect(userContext.isCustomer()).toBeTrue();
    expect(userContext.roles()).toEqual(['ROLE_CUSTOMER']);
  });

  it('rejects an expired access token or one within the 30s clock skew buffer', async () => {
    const token = createJwt({
      sub: 'user-123',
      email: 'alex@seatflow.test',
      roles: ['ROLE_CUSTOMER'],
      exp: Math.floor(Date.now() / 1000) + 10,
    });

    const session: Session = {
      access_token: token,
      refresh_token: 'refresh-token',
      expires_in: 10,
      token_type: 'bearer',
      user: {
        id: 'user-123',
        email: 'alex@seatflow.test',
        app_metadata: {},
        user_metadata: {},
        aud: 'authenticated',
        created_at: new Date().toISOString(),
      } as User,
    };

    supabaseMock.auth.getSession.and.resolveTo({
      data: { session },
      error: null,
    });

    const service = createService();
    await service.initialize();

    expect(service.getToken()).toBeNull();
  });

  it('executes login with Supabase client and syncs session', async () => {
    const token = createJwt({
      sub: 'admin-123',
      email: 'admin@seatflow.com',
      app_metadata: { roles: ['ROLE_ADMIN'] },
      exp: futureExpiration(),
    });

    const session: Session = {
      access_token: token,
      refresh_token: 'refresh-token',
      expires_in: 3600,
      token_type: 'bearer',
      user: {
        id: 'admin-123',
        email: 'admin@seatflow.com',
        app_metadata: { roles: ['ROLE_ADMIN'] },
        user_metadata: { name: 'Admin User' },
        aud: 'authenticated',
        created_at: new Date().toISOString(),
      } as User,
    };

    supabaseMock.auth.signInWithPassword.and.resolveTo({
      data: { session, user: session.user },
      error: null,
    });

    const service = createService();
    await service.initialize();

    await service.login('admin@seatflow.com', 'AdminPass123!');

    expect(supabaseMock.auth.signInWithPassword).toHaveBeenCalledWith({
      email: 'admin@seatflow.com',
      password: 'AdminPass123!',
    });
    expect(TestBed.inject(UserContextService).isAdmin()).toBeTrue();
    expect(service.getToken()).toBe(token);
  });

  it('executes logout and clears user context', async () => {
    const token = createJwt({
      sub: 'user-123',
      email: 'alex@seatflow.test',
      roles: ['ROLE_CUSTOMER'],
      exp: futureExpiration(),
    });

    const session: Session = {
      access_token: token,
      refresh_token: 'refresh-token',
      expires_in: 3600,
      token_type: 'bearer',
      user: {
        id: 'user-123',
        email: 'alex@seatflow.test',
        app_metadata: {},
        user_metadata: {},
        aud: 'authenticated',
        created_at: new Date().toISOString(),
      } as User,
    };

    supabaseMock.auth.getSession.and.resolveTo({
      data: { session },
      error: null,
    });

    const service = createService();
    await service.initialize();
    expect(service.getToken()).toBe(token);

    await service.logout();

    expect(supabaseMock.auth.signOut).toHaveBeenCalled();
    expect(service.getToken()).toBeNull();
    expect(TestBed.inject(UserContextService).currentUser()).toBeNull();
  });

  it('reacts to authStateChange events from Supabase', async () => {
    const service = createService();
    await service.initialize();
    const userContext = TestBed.inject(UserContextService);

    expect(userContext.isAuthenticated()).toBeFalse();

    const token = createJwt({
      sub: 'user-456',
      email: 'live@seatflow.test',
      app_metadata: { roles: ['ROLE_STAFF'] },
      exp: futureExpiration(),
    });

    const newSession: Session = {
      access_token: token,
      refresh_token: 'refresh-456',
      expires_in: 3600,
      token_type: 'bearer',
      user: {
        id: 'user-456',
        email: 'live@seatflow.test',
        app_metadata: { roles: ['ROLE_STAFF'] },
        user_metadata: { name: 'Gate Staff' },
        aud: 'authenticated',
        created_at: new Date().toISOString(),
      } as User,
    };

    // Trigger auth state change event
    if (authStateCallback) {
      authStateCallback('SIGNED_IN', newSession);
    }

    expect(userContext.isAuthenticated()).toBeTrue();
    expect(userContext.isStaff()).toBeTrue();
    expect(service.getToken()).toBe(token);
  });

  it('triggers resetPasswordForEmail via Supabase client with redirect url', async () => {
    const service = createService();
    await service.initialize();

    await service.resetPasswordForEmail('user@seatflow.test', 'https://custom-redirect.test/reset');

    expect(supabaseMock.auth.resetPasswordForEmail).toHaveBeenCalledWith('user@seatflow.test', {
      redirectTo: 'https://custom-redirect.test/reset',
    });
  });

  it('triggers updateUser with new password via Supabase client', async () => {
    const service = createService();
    await service.initialize();

    await service.updatePassword('NewSecretPassword123!');

    expect(supabaseMock.auth.updateUser).toHaveBeenCalledWith({
      password: 'NewSecretPassword123!',
    });
  });

  it('triggers signInWithOAuth with prompt select_account option', async () => {
    const service = createService();
    await service.initialize();

    await service.signInWithOAuth('google');

    expect(supabaseMock.auth.signInWithOAuth).toHaveBeenCalledWith({
      provider: 'google',
      options: {
        redirectTo: `${window.location.origin}/auth/callback`,
        queryParams: {
          prompt: 'select_account',
        },
      },
    });
  });

  function createService(): AuthService {
    TestBed.configureTestingModule({
      providers: [
        { provide: SUPABASE_AUTH_CONFIG, useValue: authConfig },
        { provide: SUPABASE_CLIENT, useValue: supabaseMock },
      ],
    });
    return TestBed.inject(AuthService);
  }

  function createJwt(payload: Record<string, unknown>): string {
    const encode = (value: Record<string, unknown>) =>
      btoa(JSON.stringify(value)).replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_');
    return `${encode({ alg: 'none', typ: 'JWT' })}.${encode(payload)}.signature`;
  }

  function futureExpiration(): number {
    return Math.floor(Date.now() / 1000) + 3600;
  }
});
