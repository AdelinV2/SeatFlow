import { resolveSupportContact } from './support-contact.config';

describe('support-contact.config (TASK-P16-004)', () => {
  it('treats missing values as unconfigured without a fake address', () => {
    for (const raw of [undefined, null, '', '   ']) {
      const contact = resolveSupportContact(raw);
      expect(contact.configured).toBeFalse();
      expect(contact.email).toBeNull();
      expect(contact.mailtoLink).toBeNull();
    }
  });

  it('treats placeholder and non-email values as unconfigured', () => {
    for (const raw of [
      'replace-with-your-address@example.com',
      'YOUR_SUPPORT_EMAIL',
      'support-not-configured@example.com',
      'not a mailbox',
      'missing-at-sign',
    ]) {
      expect(resolveSupportContact(raw).configured).toBeFalse();
    }
  });

  it('resolves a real maintainer address to a prefilled mailto link', () => {
    const contact = resolveSupportContact('maintainer@example.org');
    expect(contact.configured).toBeTrue();
    expect(contact.email).toBe('maintainer@example.org');
    expect(contact.mailtoLink ?? '').toContain('mailto:maintainer@example.org');
    expect(contact.mailtoLink ?? '').toContain('subject=');
  });
});
