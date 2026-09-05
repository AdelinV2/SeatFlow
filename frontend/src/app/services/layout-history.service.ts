import { computed, Injectable, signal } from '@angular/core';
import { DeepReadonly, VenueLayout } from '../models/venue.model';

/** Maximum number of retained undo entries (newest 100 kept). */
export const MAX_LAYOUT_HISTORY = 100;

export type LayoutHistorySnapshot = VenueLayout;

function deepClone<T>(value: T): T {
  if (value === null || value === undefined) {
    return value;
  }
  if (typeof structuredClone === 'function') {
    return structuredClone(value);
  }
  return JSON.parse(JSON.stringify(value));
}

function deepFreeze<T>(value: T): T {
  if (value === null || typeof value !== 'object') {
    return value;
  }
  if (Object.isFrozen(value)) {
    return value;
  }
  if (Array.isArray(value)) {
    for (const item of value) {
      deepFreeze(item);
    }
  } else {
    for (const key of Object.keys(value)) {
      deepFreeze((value as Record<string, unknown>)[key]);
    }
  }
  return Object.freeze(value);
}

/**
 * Bounded local undo/redo store for complete venue layout snapshots.
 *
 * Stores only persistent layout documents (`VenueLayout`); transient UI state
 * such as selection, pan, or zoom is never accepted and therefore excluded.
 * Snapshots are deep-cloned on push and on restore so callers can never share
 * references with the retained stacks. IDs (including null draft IDs) and
 * `layoutVersion` are preserved exactly as cloned.
 *
 * Pointer gestures coalesce: `beginCoalesced` pushes the pre-gesture baseline
 * once and suppresses further pushes until `endCoalesced`, so 50 pointer-move
 * events produce a single undo entry committed on pointer end.
 *
 * No HTTP is performed here; undo/redo only transform unsaved draft state and
 * the caller decides when to clear on load/reset/save-success.
 */
@Injectable({ providedIn: 'root' })
export class LayoutHistoryService {
  private readonly _undoStack = signal<LayoutHistorySnapshot[]>([]);
  private readonly _redoStack = signal<LayoutHistorySnapshot[]>([]);
  private readonly _coalescing = signal<boolean>(false);

  readonly canUndo = computed(() => this._undoStack().length > 0);
  readonly canRedo = computed(() => this._redoStack().length > 0);
  readonly undoDepth = computed(() => this._undoStack().length);
  readonly redoDepth = computed(() => this._redoStack().length);
  readonly isCoalescing = this._coalescing.asReadonly();

  /** Pushes a discrete command baseline; clears redo and caps at 100. */
  push(snapshot: VenueLayout | DeepReadonly<VenueLayout>): void {
    const frozen = deepFreeze(deepClone(snapshot as VenueLayout));
    this._undoStack.update((stack) => {
      const next = [...stack, frozen];
      if (next.length > MAX_LAYOUT_HISTORY) {
        next.splice(0, next.length - MAX_LAYOUT_HISTORY);
      }
      return next;
    });
    this._redoStack.set([]);
    this._coalescing.set(false);
  }

  /**
   * Begins (or continues) a coalesced pointer gesture. The first call pushes
   * the pre-gesture baseline as a single entry; subsequent calls while the
   * gesture is active are suppressed until `endCoalesced`.
   */
  beginCoalesced(snapshot: VenueLayout | DeepReadonly<VenueLayout>): void {
    if (this._coalescing()) {
      return;
    }
    const frozen = deepFreeze(deepClone(snapshot as VenueLayout));
    this._undoStack.update((stack) => {
      const next = [...stack, frozen];
      if (next.length > MAX_LAYOUT_HISTORY) {
        next.splice(0, next.length - MAX_LAYOUT_HISTORY);
      }
      return next;
    });
    this._redoStack.set([]);
    this._coalescing.set(true);
  }

  /** Ends the active coalesced gesture without adding another entry. */
  endCoalesced(): void {
    this._coalescing.set(false);
  }

  /**
   * Cancels the active coalesced gesture, restoring its baseline without
   * leaving a redo entry. Returns the restored baseline clone, or null when
   * no gesture is active.
   */
  cancelCoalesced(
    current: VenueLayout | DeepReadonly<VenueLayout> | null,
  ): LayoutHistorySnapshot | null {
    if (!this._coalescing()) {
      return null;
    }
    this._coalescing.set(false);
    const undo = this._undoStack();
    if (undo.length === 0) {
      return current ? deepClone(current as VenueLayout) : null;
    }
    const baseline = undo[undo.length - 1];
    this._undoStack.update((stack) => stack.slice(0, -1));
    // Discard the in-progress gesture entirely: no redo entry is retained.
    this._redoStack.set([]);
    return deepClone(baseline);
  }

  /**
   * Restores the previous snapshot. No-op (returns null) at the boundary.
   * Pushes the supplied current layout onto the redo stack.
   */
  undo(current: VenueLayout | DeepReadonly<VenueLayout>): LayoutHistorySnapshot | null {
    const undo = this._undoStack();
    if (undo.length === 0) {
      return null;
    }
    const currentClone = deepClone(current as VenueLayout);
    const previous = undo[undo.length - 1];
    this._undoStack.update((stack) => stack.slice(0, -1));
    this._redoStack.update((stack) => [...stack, deepFreeze(currentClone)]);
    this._coalescing.set(false);
    return deepClone(previous);
  }

  /**
   * Restores the next snapshot. No-op (returns null) at the boundary.
   * Pushes the supplied current layout onto the undo stack (capped).
   */
  redo(current: VenueLayout | DeepReadonly<VenueLayout>): LayoutHistorySnapshot | null {
    const redo = this._redoStack();
    if (redo.length === 0) {
      return null;
    }
    const currentClone = deepFreeze(deepClone(current as VenueLayout));
    const next = redo[redo.length - 1];
    this._redoStack.update((stack) => stack.slice(0, -1));
    this._undoStack.update((stack) => {
      const updated = [...stack, currentClone];
      if (updated.length > MAX_LAYOUT_HISTORY) {
        updated.splice(0, updated.length - MAX_LAYOUT_HISTORY);
      }
      return updated;
    });
    this._coalescing.set(false);
    return deepClone(next);
  }

  /** Clears the redo stack without touching undo (used after gesture cancel). */
  clearRedo(): void {
    this._redoStack.set([]);
  }

  /** Clears both stacks and any active coalesced gesture. */
  clear(): void {
    this._undoStack.set([]);
    this._redoStack.set([]);
    this._coalescing.set(false);
  }
}
