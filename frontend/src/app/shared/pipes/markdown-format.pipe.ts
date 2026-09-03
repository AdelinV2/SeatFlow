import { Pipe, PipeTransform } from '@angular/core';

const DEFAULT_EMPTY_MESSAGE = 'No description available for this event.';

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}

function applyInlineFormatting(text: string): string {
  return text
    .replace(/\*\*(.*?)\*\*/g, '<strong class="font-bold text-[var(--color-text-primary)]">$1</strong>')
    .replace(/\*(.*?)\*/g, '<em class="italic">$1</em>')
    .replace(/`([^`]+)`/g, '<code class="px-1.5 py-0.5 rounded bg-[var(--color-canvas)] text-[11px] font-mono text-indigo-400">$1</code>');
}

/**
 * Renders the markdown subset supported by the admin event description editor.
 * Input is escaped before formatting so the result can safely be bound to innerHTML.
 */
export function renderEventDescriptionMarkdown(
  markdown: string | null | undefined,
  emptyMessage = DEFAULT_EMPTY_MESSAGE,
): string {
  const raw = markdown ?? '';
  if (!raw.trim()) {
    return `<p class="text-[var(--color-text-muted)] italic">${escapeHtml(emptyMessage)}</p>`;
  }

  const lines = raw.split(/\r?\n/);
  const output: string[] = [];
  let inList = false;

  const closeList = () => {
    if (inList) {
      output.push('</ul>');
      inList = false;
    }
  };

  for (const rawLine of lines) {
    const line = escapeHtml(rawLine.trim());

    if (line.startsWith('### ')) {
      closeList();
      output.push(
        `<h3 class="text-sm font-bold text-indigo-500 mt-3 mb-1">${applyInlineFormatting(line.substring(4))}</h3>`,
      );
    } else if (line.startsWith('## ')) {
      closeList();
      output.push(
        `<h2 class="text-base font-extrabold text-[var(--color-text-primary)] mt-4 mb-1.5">${applyInlineFormatting(line.substring(3))}</h2>`,
      );
    } else if (line.startsWith('- ') || line.startsWith('* ')) {
      if (!inList) {
        output.push('<ul class="list-disc list-inside space-y-1 my-1 text-xs text-[var(--color-text-secondary)]">');
        inList = true;
      }
      output.push(`<li>${applyInlineFormatting(line.substring(2))}</li>`);
    } else if (line.startsWith('&gt; ')) {
      closeList();
      output.push(
        `<blockquote class="border-l-2 border-indigo-500 pl-3 my-2 text-xs italic text-[var(--color-text-muted)]">${applyInlineFormatting(line.substring(5))}</blockquote>`,
      );
    } else if (line === '---') {
      closeList();
      output.push('<hr class="border-[var(--color-border)] my-3"/>');
    } else if (line === '') {
      closeList();
      output.push('<div class="h-2"></div>');
    } else {
      closeList();
      output.push(
        `<p class="text-xs leading-relaxed text-[var(--color-text-secondary)] mb-1">${applyInlineFormatting(line)}</p>`,
      );
    }
  }

  closeList();
  return output.join('');
}

@Pipe({
  name: 'sfMarkdown',
  standalone: true,
  pure: true,
})
export class MarkdownFormatPipe implements PipeTransform {
  transform(value: string | null | undefined, emptyMessage = DEFAULT_EMPTY_MESSAGE): string {
    return renderEventDescriptionMarkdown(value, emptyMessage);
  }
}
