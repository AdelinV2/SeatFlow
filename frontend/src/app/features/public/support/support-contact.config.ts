/**
 * Public demo/support contact configuration (TASK-P16-004).
 *
 * The project owner must supply the intended maintainer contact channel before
 * any production use. Until then the contact page shows an explicit
 * configuration gate instead of a real address.
 *
 * Resolution order:
 * 1. runtime `window.__env.SUPPORT_EMAIL` / `NG_APP_SUPPORT_EMAIL`
 *    (see `frontend/public/env.example.js`);
 * 2. otherwise unconfigured (`null`).
 *
 * Never hardcode a personal email inferred from source history here. Email
 * addresses in this file are intentionally absent: the maintainer address is a
 * deployment fact, not a code constant.
 */

export interface PublicSupportContact {
  /** Maintainer email when configured, otherwise `null`. */
  readonly email: string | null;
  /** `mailto:` link when configured, otherwise `null`. */
  readonly mailtoLink: string | null;
  /** True only when a real maintainer channel is configured. */
  readonly configured: boolean;
}

function readRuntimeSupportEmail(): string | null {
  try {
    const globalObject = globalThis as Record<string, unknown>;
    const envWrapper = (globalObject['__env'] ?? globalObject['env']) as
      | Record<string, unknown>
      | undefined;
    const raw =
      envWrapper?.['SUPPORT_EMAIL'] ??
      envWrapper?.['NG_APP_SUPPORT_EMAIL'] ??
      globalObject['SUPPORT_EMAIL'] ??
      globalObject['NG_APP_SUPPORT_EMAIL'];
    if (typeof raw !== 'string') {
      return null;
    }
    const value = raw.trim();
    if (!value) {
      return null;
    }
    const lowered = value.toLowerCase();
    if (
      lowered.includes('replace') ||
      lowered.includes('not-configured') ||
      lowered.includes('not_configured') ||
      lowered.includes('your-') ||
      lowered.includes('your_') ||
      lowered.includes('placeholder') ||
      !value.includes('@')
    ) {
      return null;
    }
    return value;
  } catch {
    return null;
  }
}

function toSupportContact(email: string | null): PublicSupportContact {
  if (!email) {
    return { email: null, mailtoLink: null, configured: false };
  }
  return {
    email,
    mailtoLink: `mailto:${email}?subject=${encodeURIComponent('SeatFlow demo feedback')}`,
    configured: true,
  };
}

/** Resolved once at module load; tests may call `resolveSupportContact` directly. */
export const PUBLIC_SUPPORT_CONTACT: PublicSupportContact = toSupportContact(
  readRuntimeSupportEmail(),
);

/** Testable resolver: maps a raw runtime value to a contact record. */
export function resolveSupportContact(raw: unknown): PublicSupportContact {
  if (typeof raw !== 'string') {
    return toSupportContact(null);
  }
  const value = raw.trim();
  if (!value) {
    return toSupportContact(null);
  }
  const lowered = value.toLowerCase();
  if (
    lowered.includes('replace') ||
    lowered.includes('not-configured') ||
    lowered.includes('not_configured') ||
    lowered.includes('your-') ||
    lowered.includes('your_') ||
    lowered.includes('placeholder') ||
    !value.includes('@')
  ) {
    return toSupportContact(null);
  }
  return toSupportContact(value);
}
