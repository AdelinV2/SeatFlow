import { TestBed } from '@angular/core/testing';
import type {
  AccountInfo,
  AuthenticationResult,
  IPublicClientApplication,
} from '@azure/msal-browser';
import {
  AUTH_CLIENT_CONFIG,
  AuthClientConfiguration,
  AuthService,
  MSAL_CLIENT,
} from './auth.service';
import { UserContextService } from './user-context.service';

describe('AuthService', () => {
  let msalClient: jasmine.SpyObj<IPublicClientApplication>;
  let account: AccountInfo;
  const authConfig: AuthClientConfiguration = {
    clientId: 'client-id',
    tenantSubdomain: 'seatflow-test',
    apiScope: 'api://seatflow/access_as_user',
  };

  beforeEach(() => {
    account = {
      homeAccountId: 'home-account',
      environment: 'seatflow-test.ciamlogin.com',
      tenantId: 'tenant-id',
      username: 'alex@seatflow.test',
      localAccountId: 'user-123',
      name: 'Alex Morgan',
      idTokenClaims: {
        sub: 'user-123',
        email: 'alex@seatflow.test',
        name: 'Alex Morgan',
        roles: ['ROLE_CUSTOMER'],
      },
    } as AccountInfo;

    msalClient = jasmine.createSpyObj<IPublicClientApplication>('MSAL client', [
      'initialize',
      'handleRedirectPromise',
      'getActiveAccount',
      'getAllAccounts',
      'setActiveAccount',
      'acquireTokenSilent',
      'loginRedirect',
      'logoutRedirect',
    ]);
    msalClient.initialize.and.resolveTo();
    msalClient.getActiveAccount.and.returnValue(null);
    msalClient.getAllAccounts.and.returnValue([]);
    msalClient.loginRedirect.and.resolveTo();
    msalClient.logoutRedirect.and.resolveTo();
  });

  afterEach(() => TestBed.resetTestingModule());

  it('parses redirect JWT claims and normalizes roles into the user context', async () => {
    const token = createJwt({
      sub: 'user-123',
      email: 'alex@seatflow.test',
      name: 'Alex Morgan',
      roles: ['CUSTOMER', 'ROLE_STAFF'],
      exp: futureExpiration(),
    });
    msalClient.handleRedirectPromise.and.resolveTo(createResult(token));

    const service = createService();
    await service.initialize();
    const userContext = TestBed.inject(UserContextService);

    expect(service.getToken()).toBe(token);
    expect(service.isReady()).toBeTrue();
    expect(userContext.currentUser()?.roles).toEqual(['ROLE_CUSTOMER', 'ROLE_STAFF']);
    expect(userContext.isStaff()).toBeTrue();
    expect(msalClient.setActiveAccount).toHaveBeenCalledWith(account);
  });

  it('restores a cached account and silently acquires a fresh API token', async () => {
    const token = createJwt({
      sub: 'user-123',
      email: 'alex@seatflow.test',
      roles: ['ROLE_ADMIN'],
      exp: futureExpiration(),
    });
    msalClient.handleRedirectPromise.and.resolveTo(null);
    msalClient.getAllAccounts.and.returnValue([account]);
    msalClient.acquireTokenSilent.and.resolveTo(createResult(token));

    const service = createService();
    await service.initialize();

    expect(msalClient.acquireTokenSilent).toHaveBeenCalledWith({
      account,
      scopes: [authConfig.apiScope],
    });
    expect(service.getToken()).toBe(token);
    expect(TestBed.inject(UserContextService).isAdmin()).toBeTrue();
  });

  it('rejects an expired access token or one within the 30s clock skew buffer', async () => {
    const token = createJwt({
      sub: 'user-123',
      email: 'alex@seatflow.test',
      roles: ['ROLE_CUSTOMER'],
      exp: Math.floor(Date.now() / 1000) + 10,
    });
    msalClient.handleRedirectPromise.and.resolveTo(createResult(token));

    const service = createService();
    await service.initialize();

    expect(service.getToken()).toBeNull();
  });

  it('defaults empty roles to ROLE_CUSTOMER aligning with backend convention', async () => {
    const token = createJwt({
      sub: 'user-123',
      email: 'alex@seatflow.test',
      roles: [],
      exp: futureExpiration(),
    });
    msalClient.handleRedirectPromise.and.resolveTo(createResult(token));

    const service = createService();
    await service.initialize();

    expect(TestBed.inject(UserContextService).isCustomer()).toBeTrue();
    expect(TestBed.inject(UserContextService).roles()).toEqual(['ROLE_CUSTOMER']);
  });

  it('acquires an access token when redirectResult lacks an accessToken', async () => {
    const token = createJwt({
      sub: 'user-123',
      email: 'alex@seatflow.test',
      roles: ['ROLE_CUSTOMER'],
      exp: futureExpiration(),
    });
    const resultWithoutToken = { ...createResult(''), accessToken: '' };
    msalClient.handleRedirectPromise.and.resolveTo(resultWithoutToken);
    msalClient.acquireTokenSilent.and.resolveTo(createResult(token));

    const service = createService();
    await service.initialize();

    expect(msalClient.acquireTokenSilent).toHaveBeenCalled();
    expect(service.getToken()).toBe(token);
  });

  it('starts the Entra redirect flow with OIDC and API scopes', async () => {
    msalClient.handleRedirectPromise.and.resolveTo(null);
    const service = createService();
    await service.initialize();

    await service.login();

    expect(msalClient.loginRedirect).toHaveBeenCalledWith({
      scopes: ['openid', 'profile', 'email', authConfig.apiScope],
    });
  });

  it('clears the user context before redirecting to Entra sign-out', async () => {
    const token = createJwt({
      sub: 'user-123',
      email: 'alex@seatflow.test',
      roles: ['ROLE_CUSTOMER'],
      exp: futureExpiration(),
    });
    msalClient.handleRedirectPromise.and.resolveTo(createResult(token));
    msalClient.getActiveAccount.and.returnValue(account);
    const service = createService();
    await service.initialize();

    await service.logout();

    expect(TestBed.inject(UserContextService).currentUser()).toBeNull();
    expect(service.getToken()).toBeNull();
    expect(msalClient.logoutRedirect).toHaveBeenCalledWith({
      account,
      postLogoutRedirectUri: window.location.origin,
    });
  });

  function createService(): AuthService {
    TestBed.configureTestingModule({
      providers: [
        { provide: AUTH_CLIENT_CONFIG, useValue: authConfig },
        { provide: MSAL_CLIENT, useValue: Promise.resolve(msalClient) },
      ],
    });
    return TestBed.inject(AuthService);
  }

  function createResult(accessToken: string): AuthenticationResult {
    return {
      account,
      accessToken,
      idToken: 'id-token',
      idTokenClaims: account.idTokenClaims,
      scopes: [authConfig.apiScope],
      uniqueId: account.localAccountId,
      tenantId: account.tenantId,
      tokenType: 'Bearer',
      expiresOn: new Date(Date.now() + 60_000),
      correlationId: 'correlation-id',
      fromCache: false,
      authority: `https://${authConfig.tenantSubdomain}.ciamlogin.com/`,
    } as AuthenticationResult;
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
