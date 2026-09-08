import { Routes } from '@angular/router';
import { adminGuard } from './core/guards/admin.guard';
import { authGuard } from './core/guards/auth.guard';
import { guestGuard } from './core/guards/guest.guard';
import { pendingChangesGuard } from './core/guards/pending-changes.guard';
import { staffGuard } from './core/guards/staff.guard';

export const routes: Routes = [
  {
    path: '',
    title: 'SeatFlow — Discover Live Events',
    data: {
      description:
        'SeatFlow makes discovering events and reserving the right seats effortless.',
    },
    loadComponent: () =>
      import('./features/events/event-list/event-list.component').then((m) => m.EventListComponent),
  },
  {
    path: 'events',
    title: 'SeatFlow — Event Catalog',
    data: {
      description:
        'SeatFlow makes discovering events and reserving the right seats effortless.',
    },
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
    path: 'checkout/:reservationId',
    loadComponent: () =>
      import('./features/booking/checkout/checkout.component').then((m) => m.CheckoutComponent),
  },
  {
    path: 'order-confirmation/:paymentId',
    loadComponent: () =>
      import('./features/tickets/order-confirmation/order-confirmation.component').then(
        (m) => m.OrderConfirmationComponent,
      ),
  },
  {
    path: 'tickets/guest/:ticketCode',
    loadComponent: () =>
      import('./features/tickets/guest-ticket/guest-ticket.component').then(
        (m) => m.GuestTicketComponent,
      ),
  },
  {
    path: 'profile/tickets',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/profile/my-tickets/my-tickets.component').then(
        (m) => m.MyTicketsComponent,
      ),
  },
  {
    path: 'profile/settings',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/profile/user-settings/user-settings.component').then(
        (m) => m.UserSettingsComponent,
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
  // Staff Scanner Route (Protected by staffGuard per ADR-005)
  {
    path: 'scanner',
    canActivate: [staffGuard],
    loadComponent: () =>
      import('./features/scanner/staff-scanner/staff-scanner.component').then(
        (m) => m.StaffScannerComponent,
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
    canDeactivate: [pendingChangesGuard],
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
    path: 'admin/events/:id/sessions',
    canActivate: [adminGuard],
    loadComponent: () =>
      import('./features/admin/events/admin-session-manager/admin-session-manager.component').then(
        (m) => m.AdminSessionManagerComponent,
      ),
  },
  // P16-002: public legal pages (signed-out, lazy-loaded, no guards).
  // P16-005: distinct static titles + descriptions; no certification claims.
  {
    path: 'legal/terms',
    title: 'Terms & Conditions — SeatFlow',
    data: {
      description:
        'Portfolio demo terms for SeatFlow event booking, test payments, tickets, and refunds.',
    },
    loadComponent: () =>
      import('./features/public/legal/terms/terms.component').then(
        (m) => m.TermsComponent,
      ),
  },
  {
    path: 'legal/privacy',
    title: 'Privacy Notice — SeatFlow',
    data: {
      description:
        'How SeatFlow handles account, booking, payment, and browser-storage data in this demo deployment.',
    },
    loadComponent: () =>
      import('./features/public/legal/privacy/privacy.component').then(
        (m) => m.PrivacyComponent,
      ),
  },
  {
    path: 'legal/cookies',
    title: 'Cookies & Storage — SeatFlow',
    data: {
      description:
        'Cookies and similar browser storage used by SeatFlow, and why no consent banner is shown.',
    },
    loadComponent: () =>
      import('./features/public/legal/cookies/cookies.component').then(
        (m) => m.CookiesComponent,
      ),
  },
  {
    path: 'legal/security',
    title: 'Security Overview — SeatFlow',
    data: {
      description:
        'High-level overview of SeatFlow authentication, booking integrity, and payment boundaries.',
    },
    loadComponent: () =>
      import('./features/public/legal/security/security.component').then(
        (m) => m.SecurityComponent,
      ),
  },
  // P16-003: refund/tax disclosures (signed-out, lazy-loaded, no guards).
  {
    path: 'legal/refunds',
    title: 'Refund & Cancellation Policy — SeatFlow',
    data: {
      description:
        'When a SeatFlow reservation is eligible for a full-reservation refund: the 24-hour rule.',
    },
    loadComponent: () =>
      import('./features/public/legal/refunds/refunds.component').then(
        (m) => m.RefundsComponent,
      ),
  },
  {
    path: 'legal/tax',
    title: 'Tax & Test Payments — SeatFlow',
    data: {
      description:
        'How Stripe Tax previews and Test Mode payments work in the SeatFlow demo.',
    },
    loadComponent: () =>
      import('./features/public/legal/tax/tax.component').then((m) => m.TaxComponent),
  },
  // P16-004: public support/status/API-doc pages (signed-out, lazy-loaded, no guards).
  {
    path: 'support/faq',
    title: 'Help Center & FAQ — SeatFlow',
    data: {
      description:
        'Short answers about SeatFlow booking, tickets, test payments, refunds, and accounts.',
    },
    loadComponent: () =>
      import('./features/public/support/faq/faq.component').then((m) => m.FaqComponent),
  },
  {
    path: 'support/contact',
    title: 'Contact Support — SeatFlow',
    data: {
      description:
        'How to reach the SeatFlow demo maintainer and what this demo cannot support.',
    },
    loadComponent: () =>
      import('./features/public/support/contact/contact.component').then(
        (m) => m.ContactComponent,
      ),
  },
  {
    // Static title on purpose: never encodes transient live probe results.
    path: 'status',
    title: 'Platform Status — SeatFlow',
    data: {
      description:
        'Best-effort live view of SeatFlow demo availability. No uptime promise.',
    },
    loadComponent: () =>
      import('./features/public/status/status.component').then((m) => m.StatusComponent),
  },
  {
    path: 'api-docs',
    title: 'API Documentation — SeatFlow',
    data: {
      description:
        'Portfolio overview of the SeatFlow API domains, authentication, and protected surfaces.',
    },
    loadComponent: () =>
      import('./features/public/api-docs/api-docs.component').then((m) => m.ApiDocsComponent),
  },
  {
    path: '**',
    title: 'Page Not Found — SeatFlow',
    data: { noindex: true },
    loadComponent: () =>
      import('./features/public/not-found/not-found.component').then(
        (m) => m.NotFoundComponent,
      ),
  },
];
