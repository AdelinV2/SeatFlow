import { inject, Injectable, InjectionToken, signal } from '@angular/core';
import type {
  AccountInfo,
  AuthenticationResult,
  Configuration,
  IPublicClientApplication,
} from '@azure/msal-browser';
import { JwtClaims, UserProfile } from '../../models/user.model';
import { UserContextService } from './user-context.service';

export interface AuthClientConfiguration {
  clientId: string;
  tenantSubdomain: string;
  apiScope: string;
}

const placeholderClientId = '00000000-0000-0000-0000-000000000000';

function readRuntimeValue(name: string, fallback: string): string {
  const globalObject = globalThis as Record<string, unknown>;
  const envWrapper = (globalObject['__env'] ?? globalObject['env']) as
    | Record<string, unknown>
    | undefined;
  const value = globalObject[name] ?? envWrapper?.[name];
  return typeof value === 'string' && value.trim() ? value.trim() : fallback;
}

export const AUTH_CLIENT_CONFIG = new InjectionToken<AuthClientConfiguration>(
  'AUTH_CLIENT_CONFIG',
  {
    providedIn: 'root',
    factory: () => {
      const clientId = readRuntimeValue('NG_APP_ENTRA_CLIENT_ID', placeholderClientId);

      return {
        clientId,
        tenantSubdomain: readRuntimeValue('NG_APP_ENTRA_TENANT_SUBDOMAIN', 'seatflow'),
        apiScope: readRuntimeValue('NG_APP_ENTRA_API_SCOPE', `api://${clientId}/access_as_user`),
      };
    },
  },
);

export const MSAL_CLIENT = new InjectionToken<Promise<IPublicClientApplication>>('MSAL_CLIENT', {
  providedIn: 'root',
  factory: () => {
    const authConfig = inject(AUTH_CLIENT_CONFIG);
    const authorityHost = `${authConfig.tenantSubdomain}.ciamlogin.com`;
    const configuration: Configuration = {
      auth: {
        clientId: authConfig.clientId,
        authority: `https://${authorityHost}/`,
        knownAuthorities: [authorityHost],
        redirectUri: window.location.origin,
        postLogoutRedirectUri: window.location.origin,
      },
      cache: {
        cacheLocation: 'sessionStorage',
      },
    };

    return import('@azure/msal-browser').then(
      ({ PublicClientApplication }) => new PublicClientApplication(configuration),
    );
  },
});

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly userContext = inject(UserContextService);
  private readonly msalClientPromise = inject(MSAL_CLIENT);
  private readonly authConfig = inject(AUTH_CLIENT_CONFIG);
  private readonly accessToken = signal<string | null>(null);
  private readonly initialization = this.initializeSession();
  private msalClient: IPublicClientApplication | null = null;

  readonly isReady = signal(false);
  readonly lastError = signal<string | null>(null);

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

  async acquireAccessToken(): Promise<string | null> {
    await this.initialization;
    const account = this.getActiveAccount();
    if (!account) {
      return null;
    }

    return this.acquireAccessTokenForAccount(account);
  }

  private async acquireAccessTokenForAccount(account: AccountInfo): Promise<string | null> {
    try {
      const result = await this.client.acquireTokenSilent({
        account,
        scopes: [this.authConfig.apiScope],
      });
      this.syncAuthenticationResult(result);
      return this.getToken();
    } catch (error: unknown) {
      this.lastError.set(this.getErrorMessage(error));
      return null;
    }
  }

  async login(): Promise<void> {
    await this.initialization;
    this.lastError.set(null);

    try {
      await this.client.loginRedirect({
        scopes: ['openid', 'profile', 'email', this.authConfig.apiScope],
      });
    } catch (error: unknown) {
      this.lastError.set(this.getErrorMessage(error));
      throw error;
    }
  }

  async logout(): Promise<void> {
    await this.initialization;
    const account = this.getActiveAccount();
    this.clearSession();

    try {
      await this.client.logoutRedirect({
        account: account ?? undefined,
        postLogoutRedirectUri: window.location.origin,
      });
    } catch (error: unknown) {
      this.lastError.set(this.getErrorMessage(error));
      throw error;
    }
  }

  private async initializeSession(): Promise<void> {
    try {
      this.msalClient = await this.msalClientPromise;
      await this.client.initialize();
      const redirectResult = await this.client.handleRedirectPromise();
      const account = redirectResult?.account ?? this.getActiveAccount();

      if (!account) {
        this.clearSession();
        return;
      }

      this.client.setActiveAccount(account);

      if (redirectResult) {
        this.syncAuthenticationResult(redirectResult);
        if (!this.accessToken()) {
          await this.acquireAccessTokenForAccount(account);
        }
      } else {
        this.syncUserFromClaims(account.idTokenClaims as JwtClaims | undefined, account);
        await this.acquireAccessTokenForAccount(account);
      }
    } catch (error: unknown) {
      this.clearSession();
      this.lastError.set(this.getErrorMessage(error));
    } finally {
      this.isReady.set(true);
    }
  }

  private getActiveAccount(): AccountInfo | null {
    return this.client.getActiveAccount() ?? this.client.getAllAccounts()[0] ?? null;
  }

  private syncAuthenticationResult(result: AuthenticationResult): void {
    if (result.account) {
      this.client.setActiveAccount(result.account);
    }

    if (result.accessToken) {
      this.accessToken.set(result.accessToken);
    }

    const accessClaims = result.accessToken ? this.decodeJwt(result.accessToken) : null;
    const idClaims = result.idTokenClaims as JwtClaims | undefined;
    this.syncUserFromClaims(accessClaims ?? idClaims, result.account);
  }

  private syncUserFromClaims(claims: JwtClaims | undefined | null, account: AccountInfo): void {
    if (!claims?.sub && !account.localAccountId) {
      this.clearSession();
      return;
    }

    let roles = (claims?.roles ?? []).map((role) =>
      role.startsWith('ROLE_') ? role : `ROLE_${role}`,
    );

    if (roles.length === 0) {
      roles = ['ROLE_CUSTOMER'];
    }

    const user: UserProfile = {
      id: claims?.sub ?? account.localAccountId,
      email: claims?.email ?? account.username,
      name: claims?.name ?? account.name,
      roles,
    };

    this.userContext.setUser(user);
  }

  private clearSession(): void {
    this.accessToken.set(null);
    this.userContext.clearUser();
  }

  private get client(): IPublicClientApplication {
    if (!this.msalClient) {
      throw new Error('The authentication client has not finished initializing.');
    }

    return this.msalClient;
  }

  private decodeJwt(token: string): JwtClaims | null {
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

  private getErrorMessage(error: unknown): string {
    return error instanceof Error ? error.message : 'Authentication could not be completed.';
  }
}
