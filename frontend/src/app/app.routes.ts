import { Routes } from '@angular/router';
import { guestGuard } from './core/guards/guest.guard';

export const routes: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./features/events/event-list/event-list.component').then((m) => m.EventListComponent),
  },
  {
    path: 'events',
    loadComponent: () =>
      import('./features/events/event-list/event-list.component').then((m) => m.EventListComponent),
  },
  {
    path: 'events/:id/seats',
    loadComponent: () =>
      import('./features/booking/seat-selection/seat-selection.component').then(
        (m) => m.SeatSelectionComponent,
      ),
  },
  {
    path: 'events/:id',
    loadComponent: () =>
      import('./features/events/event-detail/event-detail.component').then(
        (m) => m.EventDetailComponent,
      ),
  },
  {
    path: 'auth/login',
    canActivate: [guestGuard],
    loadComponent: () =>
      import('./features/auth/login/login.component').then((m) => m.LoginComponent),
  },
  {
    path: 'auth/register',
    canActivate: [guestGuard],
    loadComponent: () =>
      import('./features/auth/register/register.component').then((m) => m.RegisterComponent),
  },
  {
    path: 'auth/forgot-password',
    canActivate: [guestGuard],
    loadComponent: () =>
      import('./features/auth/forgot-password/forgot-password.component').then(
        (m) => m.ForgotPasswordComponent,
      ),
  },
  {
    path: 'auth/reset-password',
    loadComponent: () =>
      import('./features/auth/reset-password/reset-password.component').then(
        (m) => m.ResetPasswordComponent,
      ),
  },
  {
    path: 'auth/callback',
    loadComponent: () =>
      import('./features/auth/callback/auth-callback.component').then(
        (m) => m.AuthCallbackComponent,
      ),
  },
  {
    path: '**',
    redirectTo: '',
  },
];
