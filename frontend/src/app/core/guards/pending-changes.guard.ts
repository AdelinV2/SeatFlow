import { CanDeactivateFn } from '@angular/router';

export interface PendingChangesAware {
  hasPendingChanges(): boolean;
  confirmDiscardChanges?(): boolean | Promise<boolean>;
}

export const pendingChangesGuard: CanDeactivateFn<PendingChangesAware> = (component) => {
  if (!component.hasPendingChanges()) {
    return true;
  }

  return (
    component.confirmDiscardChanges?.() ??
    window.confirm('You have unsaved changes. Leave this page and discard them?')
  );
};
