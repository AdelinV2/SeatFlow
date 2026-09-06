import {
  getEventDescriptionExcerpt,
  renderEventDescriptionMarkdown,
  stripEventDescriptionMarkdown,
} from './markdown-format.pipe';

describe('renderEventDescriptionMarkdown', () => {
  it('renders headings, bold, and list items as HTML', () => {
    const html = renderEventDescriptionMarkdown(
      '### 🕒 Schedule & Door Times\n- **18:00** — Doors Open',
    );

    expect(html).toContain('<h3');
    expect(html).toContain('Schedule &amp; Door Times');
    expect(html).toContain('<ul');
    expect(html).toContain('<strong');
    expect(html).toContain('18:00');
  });

  it('escapes HTML input before formatting', () => {
    const html = renderEventDescriptionMarkdown('<script>alert(1)</script>');

    expect(html).not.toContain('<script>');
    expect(html).toContain('&lt;script&gt;');
  });

  it('returns the empty message for blank input', () => {
    expect(renderEventDescriptionMarkdown('   ')).toContain(
      'No description available for this event.',
    );
  });
});

describe('stripEventDescriptionMarkdown', () => {
  it('strips markdown markers but preserves content order', () => {
    const plain = stripEventDescriptionMarkdown(
      '### 🕒 Schedule & Door Times\n- **18:00** — Doors Open & Welcome\n- **19:15** — Opening Act',
    );

    expect(plain).toContain('🕒 Schedule & Door Times');
    expect(plain).toContain('18:00 — Doors Open & Welcome');
    expect(plain).toContain('19:15 — Opening Act');
    expect(plain).not.toContain('###');
    expect(plain).not.toContain('**');
    expect(plain).not.toContain('- ');
  });

  it('returns empty string for blank input', () => {
    expect(stripEventDescriptionMarkdown(null)).toBe('');
    expect(stripEventDescriptionMarkdown('   ')).toBe('');
  });
});

describe('getEventDescriptionExcerpt', () => {
  it('returns plain text unchanged when short', () => {
    expect(getEventDescriptionExcerpt('A great show')).toBe('A great show');
  });

  it('truncates long markdown to a word boundary with ellipsis', () => {
    const excerpt = getEventDescriptionExcerpt(
      '### Title\n- **Headliner:** Feature Orchestra & Guest Vocalist with a very long supporting lineup description that keeps going',
      60,
    );

    expect(excerpt.length).toBeLessThanOrEqual(61);
    expect(excerpt.endsWith('…')).toBeTrue();
    expect(excerpt).not.toContain('###');
    expect(excerpt).not.toContain('**');
  });

  it('returns empty string when there is no content', () => {
    expect(getEventDescriptionExcerpt('   ')).toBe('');
  });
});
