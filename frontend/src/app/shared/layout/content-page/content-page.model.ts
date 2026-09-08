/**
 * Typed contracts for the reusable public content-page shell.
 *
 * Phase 16 content is version-controlled Angular content. There is no Markdown
 * renderer, CMS, or remote/dynamic HTML injection path by design.
 */
export interface ContentPageTocEntry {
  /** Anchor id slug. Deduplicated at render time so duplicate ids can never occur. */
  id: string;
  /** Human-readable link text shown in the table of contents. */
  label: string;
  /** Heading level the entry points to. Defaults to 2. */
  level?: 2 | 3;
}

export type ContentPageCalloutTone = 'info' | 'warning';

export interface ContentPageCallout {
  /** Optional heading. Falls back to a tone-derived label so meaning never relies on color alone. */
  title?: string;
  /** Plain-text message. Never rendered as HTML. */
  message: string;
  /** Visual tone. Defaults to 'info'. */
  tone?: ContentPageCalloutTone;
}
