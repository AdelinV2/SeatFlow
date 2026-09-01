import { Component, inject } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideLocationMocks } from '@angular/common/testing';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import {
  ActivatedRoute,
  provideRouter,
  Router,
  Routes,
} from '@angular/router';

import { AuthService } from './core/auth/auth.service';
import { UserContextService } from './core/auth/user-context.service';
import { authGuard } from './core/guards/auth.guard';
import { adminGuard } from './core/guards/admin.guard';
import { staffGuard } from './core/guards/staff.guard';
import { guestGuard } from './core/guards/guest.guard';

@Component({
  standalone: true,
  template: '<div class="test-catalog">Catalog View</div>',
})
class TestCatalogComponent {}

@Component({
  standalone: true,
  template: '<div class="test-detail">Detail View for Event: {{ id }}</div>',
})
class TestDetailComponent {
  private readonly route = inject(ActivatedRoute);
  id = this.route.snapshot.paramMap.get('id');
}

@Component({
  standalone: true,
  template: '<div class="test-seat-map">Seat Map for Event: {{ id }}</div>',
})
class TestSeatMapComponent {
  private readonly route = inject(ActivatedRoute);
  id = this.route.snapshot.paramMap.get('id');
}

@Component({
  standalone: true,
  template: '<div class="test-checkout">Checkout for: {{ reservationId }}</div>',
})
class TestCheckoutComponent {
  private readonly route = inject(ActivatedRoute);
  reservationId = this.route.snapshot.paramMap.get('reservationId');
}

@Component({
  standalone: true,
  template: '<div class="test-confirmation">Confirmation: {{ paymentId }}</div>',
})
class TestConfirmationComponent {
  private readonly route = inject(ActivatedRoute);
  paymentId = this.route.snapshot.paramMap.get('paymentId');
}

@Component({
  standalone: true,
  template: '<div class="test-login">Login Page</div>',
})
class TestLoginComponent {}

@Component({
  standalone: true,
  template: '<div class="test-admin">Admin Portal</div>',
})
class TestAdminComponent {}

@Component({
  standalone: true,
  template: '<div class="test-scanner">Scanner Gate</div>',
})
class TestScannerComponent {}

@Component({
  standalone: true,
  template: '<div class="test-tickets">My Tickets</div>',
})
class TestTicketsComponent {}

describe('App Routing & Customer Journey Integration Flow', () => {
  let router: Router;
  let userContext: UserContextService;
  let authServiceSpy: jasmine.SpyObj<AuthService>;

  const seatFlowIntegrationRoutes: Routes = [
    { path: '', component: TestCatalogComponent },
    { path: 'events', component: TestCatalogComponent },
    { path: 'events/:id', component: TestDetailComponent },
    { path: 'events/:id/seats', component: TestSeatMapComponent },
    { path: 'checkout/:reservationId', component: TestCheckoutComponent },
    { path: 'order-confirmation/:paymentId', component: TestConfirmationComponent },
    { path: 'auth/login', canActivate: [guestGuard], component: TestLoginComponent },
    { path: 'profile/tickets', canActivate: [authGuard], component: TestTicketsComponent },
    { path: 'admin', canActivate: [adminGuard], component: TestAdminComponent },
    { path: 'scanner', canActivate: [staffGuard], component: TestScannerComponent },
    { path: '**', redirectTo: '' },
  ];

  beforeEach(() => {
    authServiceSpy = jasmine.createSpyObj<AuthService>('AuthService', ['initialize']);
    authServiceSpy.initialize.and.resolveTo();

    TestBed.configureTestingModule({
      providers: [
        provideRouter(seatFlowIntegrationRoutes),
        provideLocationMocks(),
        provideAnimationsAsync(),
        UserContextService,
        { provide: AuthService, useValue: authServiceSpy },
      ],
    });

    router = TestBed.inject(Router);
    userContext = TestBed.inject(UserContextService);
  });

  it('navigates through the complete booking lifecycle: / -> /events/ev-101 -> /events/ev-101/seats -> /checkout/res-202 -> /order-confirmation/pay-303', async () => {
    // 1. Root / Catalog
    await router.navigateByUrl('/');
    expect(router.url).toBe('/');

    // 2. Event Detail
    await router.navigateByUrl('/events/ev-101');
    expect(router.url).toBe('/events/ev-101');

    // 3. Seat Map Selection
    await router.navigateByUrl('/events/ev-101/seats');
    expect(router.url).toBe('/events/ev-101/seats');

    // 4. Checkout
    await router.navigateByUrl('/checkout/res-202');
    expect(router.url).toBe('/checkout/res-202');

    // 5. Order Confirmation
    await router.navigateByUrl('/order-confirmation/pay-303');
    expect(router.url).toBe('/order-confirmation/pay-303');
  });

  it('guards /profile/tickets against unauthenticated guests and redirects to /auth/login', async () => {
    userContext.clearUser();

    await router.navigateByUrl('/profile/tickets');
    expect(router.url).toBe('/auth/login?returnUrl=%2Fprofile%2Ftickets');
  });

  it('allows access to /profile/tickets when user is authenticated', async () => {
    userContext.setUser({
      id: 'u-1',
      email: 'alex@example.com',
      name: 'Alex Vance',
      roles: ['ROLE_CUSTOMER'],
    });

    await router.navigateByUrl('/profile/tickets');
    expect(router.url).toBe('/profile/tickets');
  });

  it('guards /admin for non-admin users and redirects to root /', async () => {
    userContext.setUser({
      id: 'u-1',
      email: 'customer@example.com',
      name: 'Customer',
      roles: ['ROLE_CUSTOMER'],
    });

    await router.navigateByUrl('/admin');
    expect(router.url).toBe('/');
  });

  it('allows access to /admin when user has ROLE_ADMIN', async () => {
    userContext.setUser({
      id: 'u-admin',
      email: 'admin@seatflow.com',
      name: 'Admin User',
      roles: ['ROLE_ADMIN'],
    });

    await router.navigateByUrl('/admin');
    expect(router.url).toBe('/admin');
  });

  it('guards /scanner for customer users and allows staff users', async () => {
    userContext.setUser({
      id: 'u-cust',
      email: 'customer@seatflow.com',
      name: 'Customer',
      roles: ['ROLE_CUSTOMER'],
    });

    await router.navigateByUrl('/scanner');
    expect(router.url).toBe('/');

    userContext.setUser({
      id: 'u-staff',
      email: 'staff@seatflow.com',
      name: 'Staff Gate',
      roles: ['ROLE_STAFF'],
    });

    await router.navigateByUrl('/scanner');
    expect(router.url).toBe('/scanner');
  });

  it('redirects unknown route to root /', async () => {
    await router.navigateByUrl('/random-invalid-route-path');
    expect(router.url).toBe('/');
  });
});
