import { Routes } from '@angular/router';
import { adminGuard } from './core/guards/admin.guard';
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
  // Admin Routes (Protected by adminGuard)
  {
    path: 'admin',
    canActivate: [adminGuard],
    loadComponent: () =>
      import('./features/admin/admin-portal/admin-portal.component').then(
        (m) => m.AdminPortalComponent,
      ),
  },
  {
    path: 'admin/venues',
    canActivate: [adminGuard],
    loadComponent: () =>
      import('./features/admin/venues/admin-venue-list/admin-venue-list.component').then(
        (m) => m.AdminVenueListComponent,
      ),
  },
  {
    path: 'admin/venues/new',
    canActivate: [adminGuard],
    loadComponent: () =>
      import('./features/admin/venues/admin-venue-editor/admin-venue-editor.component').then(
        (m) => m.AdminVenueEditorComponent,
      ),
  },
  {
    path: 'admin/venues/:id/edit',
    canActivate: [adminGuard],
    loadComponent: () =>
      import('./features/admin/venues/admin-venue-editor/admin-venue-editor.component').then(
        (m) => m.AdminVenueEditorComponent,
      ),
  },
  {
    path: 'admin/venues/:id/designer',
    canActivate: [adminGuard],
    loadComponent: () =>
      import('./features/admin/venues/venue-grid-designer/venue-grid-designer.component').then(
        (m) => m.VenueGridDesignerComponent,
      ),
  },
  {
    path: 'admin/users',
    canActivate: [adminGuard],
    loadComponent: () =>
      import('./features/admin/users/admin-user-list/admin-user-list.component').then(
        (m) => m.AdminUserListComponent,
      ),
  },
  // Admin Event Routes
  {
    path: 'admin/events',
    canActivate: [adminGuard],
    loadComponent: () =>
      import('./features/admin/events/admin-event-list/admin-event-list.component').then(
        (m) => m.AdminEventListComponent,
      ),
  },
  {
    path: 'admin/events/new',
    canActivate: [adminGuard],
    loadComponent: () =>
      import('./features/admin/events/admin-event-editor/admin-event-editor.component').then(
        (m) => m.AdminEventEditorComponent,
      ),
  },
  {
    path: 'admin/events/:id/edit',
    canActivate: [adminGuard],
    loadComponent: () =>
      import('./features/admin/events/admin-event-editor/admin-event-editor.component').then(
        (m) => m.AdminEventEditorComponent,
      ),
  },
  {
    path: 'admin/events/:id/pricing',
    canActivate: [adminGuard],
    loadComponent: () =>
      import('./features/admin/events/admin-pricing-manager/admin-pricing-manager.component').then(
        (m) => m.AdminPricingManagerComponent,
      ),
  },
  {
    path: '**',
    redirectTo: '',
  },
];
