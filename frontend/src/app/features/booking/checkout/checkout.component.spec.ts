import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import {
  Stripe,
  StripeAddressElement,
  StripeElements,
  StripePaymentElement,
} from '@stripe/stripe-js';
import { NEVER, of } from 'rxjs';
import { UserContextService } from '../../../core/auth/user-context.service';
import { ThemeService } from '../../../core/theme/theme.service';
import { PaymentApiService } from '../../../services/payment-api.service';
import { EventApiService } from '../../../services/event-api.service';
import {
  ReservationApiService,
  ReservationResponse,
} from '../../../services/reservation-api.service';
import { HoldCountdownComponent } from '../../../shared/components/hold-countdown/hold-countdown.component';
import { CheckoutComponent, STRIPE_LOADER, STRIPE_PUBLISHABLE_KEY } from './checkout.component';

describe('CheckoutComponent', () => {
  let fixture: ComponentFixture<CheckoutComponent>;
  let component: CheckoutComponent;
  let paymentApi: jasmine.SpyObj<PaymentApiService>;
  let reservationApi: jasmine.SpyObj<ReservationApiService>;
  let eventApi: jasmine.SpyObj<EventApiService>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;
  let dialog: jasmine.SpyObj<MatDialog>;
  let router: Router;
  let stripe: jasmine.SpyObj<Stripe>;
  let stripeLoader: jasmine.Spy;
  let elements: jasmine.SpyObj<StripeElements>;
  let paymentElement: jasmine.SpyObj<StripePaymentElement>;
  let addressElement: jasmine.SpyObj<StripeAddressElement>;

  const reservation: ReservationResponse = {
    id: 'reservation-007',
    eventId: 'event-009',
    customerEmail: 'guest@example.com',
    status: 'PENDING',
    expiresAt: '2099-08-30T10:15:00Z',
    totalAmount: 120,
    seatCount: 2,
    seats: [
      { id: 'hold-1', seatId: 'seat-1', rowNumber: 'A', seatNumber: 12, price: 60 },
      { id: 'hold-2', seatId: 'seat-2', rowNumber: 'A', seatNumber: 13, price: 60 },
    ],
  };

  beforeEach(async () => {
    reservationApi = jasmine.createSpyObj<ReservationApiService>('ReservationApiService', [
      'getReservation',
      'updateReservationPricing',
    ]);
    reservationApi.getReservation.and.returnValue(of(reservation));
    reservationApi.updateReservationPricing.and.returnValue(of(reservation));

    eventApi = jasmine.createSpyObj<EventApiService>('EventApiService', ['getEventSeatMap']);
    eventApi.getEventSeatMap.and.returnValue(
      of({
        eventId: reservation.eventId,
        venueId: 'venue-1',
        eventTitle: 'Concert',
        eventDate: '2099-08-30T20:00:00Z',
        venueName: 'Arena',
        venueCapacity: 2,
        totalConfiguredSeats: 2,
        sections: [
          {
            sectionId: 'section-a',
            name: 'Main floor',
            rowCount: 1,
            colCount: 2,
            seats: [
              { seatId: 'seat-1', rowLabel: 'A', seatNumber: 12, gridX: 0, gridY: 0, isActive: true },
              { seatId: 'seat-2', rowLabel: 'A', seatNumber: 13, gridX: 1, gridY: 0, isActive: true },
            ],
            pricingTiers: [
              { id: 'tier-standard', sectionId: 'section-a', categoryName: 'Standard', price: 60, currency: 'USD' },
              { id: 'tier-student', sectionId: 'section-a', categoryName: 'Student', price: 45, currency: 'USD' },
            ],
          },
        ],
      }),
    );

    paymentApi = jasmine.createSpyObj<PaymentApiService>('PaymentApiService', [
      'createPaymentIntent',
      'previewTax',
    ]);
    paymentApi.createPaymentIntent.and.returnValue(
      of({
        paymentId: 'payment-007',
        clientSecret: 'pi_test_secret',
        amount: 120,
        currency: 'USD',
        status: 'INITIATED',
      }),
    );
    paymentApi.previewTax.and.returnValue(of({ taxAmount: 0, effectiveRate: 0, currency: 'USD' }));

    paymentElement = jasmine.createSpyObj<StripePaymentElement>('StripePaymentElement', [
      'mount',
      'destroy',
      'update',
      'on',
    ]);
    addressElement = jasmine.createSpyObj<StripeAddressElement>('StripeAddressElement', [
      'mount',
      'destroy',
      'update',
      'on',
    ]);
    elements = jasmine.createSpyObj<StripeElements>('StripeElements', [
      'create',
      'update',
      'submit',
    ]);
    elements.create.and.callFake(((type: string) =>
      type === 'payment' ? paymentElement : addressElement) as never);
    elements.submit.and.resolveTo({});

    stripe = jasmine.createSpyObj<Stripe>('Stripe', [
      'elements',
      'confirmPayment',
      'confirmCardPayment',
    ]);
    stripe.elements.and.returnValue(elements);
    stripe.confirmPayment.and.resolveTo({
      paymentIntent: { status: 'succeeded' },
    } as never);
    stripe.confirmCardPayment.and.resolveTo({
      paymentIntent: { status: 'succeeded' },
    } as never);

    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);
    dialog.open.and.returnValue({ afterClosed: () => NEVER } as never);

    const userContext = {
      currentUser: signal(null),
      isAuthenticated: signal(false),
      userEmail: signal(''),
      userName: signal('User'),
    };
    const themeService = { isDark: signal(false) };
    stripeLoader = jasmine.createSpy('stripeLoader').and.resolveTo(stripe);

    await TestBed.configureTestingModule({
      imports: [CheckoutComponent],
      providers: [
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { paramMap: convertToParamMap({ reservationId: reservation.id }) },
          },
        },
        { provide: ReservationApiService, useValue: reservationApi },
        { provide: EventApiService, useValue: eventApi },
        { provide: PaymentApiService, useValue: paymentApi },
        { provide: UserContextService, useValue: userContext },
        { provide: ThemeService, useValue: themeService },
        { provide: MatSnackBar, useValue: snackBar },
        { provide: MatDialog, useValue: dialog },
        { provide: STRIPE_LOADER, useValue: stripeLoader },
        { provide: STRIPE_PUBLISHABLE_KEY, useValue: 'pk_test_component_spec' },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(CheckoutComponent);
    component = fixture.componentInstance;
    router = TestBed.inject(Router);
  });

  afterEach(() => fixture.destroy());

  async function initializeCheckout(): Promise<void> {
    fixture.detectChanges();
    await Promise.resolve();
    await new Promise<void>((resolve) => setTimeout(resolve));
    fixture.detectChanges();
    await fixture.whenStable();
  }

  async function startPayment(): Promise<void> {
    if (!component.guestForm.controls.customerName.value) {
      component.guestForm.controls.customerName.setValue('Test Buyer');
    }
    component.continueToPayment();
    await Promise.resolve();
    await new Promise<void>((resolve) => setTimeout(resolve));
    fixture.detectChanges();
    await fixture.whenStable();
  }

  it('loads authoritative seat rows and pricing tiers before creating the payment intent', async () => {
    await initializeCheckout();

    expect(reservationApi.getReservation).toHaveBeenCalledWith('reservation-007');
    expect(eventApi.getEventSeatMap).toHaveBeenCalledWith('event-009');
    expect(component.checkoutSeats().map((seat) => `${seat.rowLabel}-${seat.seatNumber}`)).toEqual([
      'A-12',
      'A-13',
    ]);
    expect(component.checkoutSeats()[0].pricingTiers.map((tier) => tier.categoryName)).toEqual([
      'Standard',
      'Student',
    ]);
    expect(fixture.nativeElement.textContent).toContain('Main floor');
    expect(fixture.nativeElement.textContent).toContain('Row A');
    expect(fixture.nativeElement.textContent).toContain('Seat 12');
    expect(paymentApi.createPaymentIntent).not.toHaveBeenCalled();

    component.guestForm.controls.customerName.setValue('Test Buyer');
    component.selectTicketType('seat-1', 'tier-student');
    await startPayment();

    expect(reservationApi.updateReservationPricing).toHaveBeenCalledWith(
      'reservation-007',
      {
        seats: [
          { seatId: 'seat-1', pricingTierId: 'tier-student' },
          { seatId: 'seat-2', pricingTierId: 'tier-standard' },
        ],
      },
      'guest@example.com',
    );
    expect(paymentApi.createPaymentIntent).toHaveBeenCalledWith({
      reservationId: 'reservation-007',
      idempotencyKey: 'pay-intent-reservation-007',
    });
    expect(component.grossTotal()).toBe(105);
    expect(component.currencyCode()).toBe('USD');
    expect(paymentElement.mount).toHaveBeenCalled();
    expect(addressElement.mount).toHaveBeenCalled();
    expect(stripe.elements).toHaveBeenCalledWith(jasmine.objectContaining({ locale: 'en' }));
  });

  it('disables Stripe’s sandbox testing assistant telemetry', async () => {
    await initializeCheckout();
    await startPayment();

    expect(stripeLoader).toHaveBeenCalledWith(
      'pk_test_component_spec',
      jasmine.objectContaining({
        developerTools: { assistant: { enabled: false } },
      }),
    );
  });

  it('requires a valid attendee name and email for guest checkout', async () => {
    await initializeCheckout();

    expect(component.guestForm.controls.customerEmail.value).toBe('guest@example.com');
    expect(component.guestForm.controls.customerName.value).toBe('');
    expect(component.paymentDisabled()).toBeTrue();

    void component.confirmPayment();

    expect(component.guestForm.controls.customerName.touched).toBeTrue();
    expect(snackBar.open).toHaveBeenCalledWith(
      'Add a valid attendee name and email for ticket delivery.',
      'Close',
      jasmine.objectContaining({ panelClass: 'snack-warning' }),
    );
  });

  it('selects the safe Stripe test method and fills non-sensitive testing details', async () => {
    await initializeCheckout();
    await startPayment();

    component.applyTestCard();

    expect(component.testCardSelected()).toBeTrue();
    expect(component.guestForm.getRawValue()).toEqual({
      customerName: 'SeatFlow Test Guest',
      customerEmail: 'testbuyer@seatflow.dev',
    });
    expect(paymentElement.update).toHaveBeenCalledWith(
      jasmine.objectContaining({ readOnly: true }),
    );
    expect(addressElement.update).toHaveBeenCalledWith({
      defaultValues: {
        name: 'SeatFlow Test Guest',
        address: {
          country: 'RO',
          postal_code: '010101',
          city: 'Bucharest',
          line1: '1 Test Avenue',
        },
      },
    });
    expect(component.paymentDisabled()).toBeFalse();
  });

  it('confirms the selected test card without collecting real card data', async () => {
    await initializeCheckout();
    await startPayment();
    component.applyTestCard();
    const navigate = spyOn(router, 'navigate').and.resolveTo(true);

    await component.confirmPayment();

    expect(stripe.confirmCardPayment).toHaveBeenCalledWith(
      'pi_test_secret',
      jasmine.objectContaining({
        payment_method: 'pm_card_visa',
        receipt_email: 'testbuyer@seatflow.dev',
      }),
    );
    expect(navigate).toHaveBeenCalledWith(['/order-confirmation', 'payment-007']);
  });

  it('freezes checkout and opens the non-dismissible dialog when the countdown expires', async () => {
    await initializeCheckout();
    await startPayment();
    const countdown = fixture.debugElement.query(
      (debugElement) => debugElement.componentInstance instanceof HoldCountdownComponent,
    ).componentInstance as HoldCountdownComponent;

    countdown.expired.emit();
    fixture.detectChanges();

    expect(component.isHoldExpired()).toBeTrue();
    expect(component.guestForm.disabled).toBeTrue();
    expect(dialog.open).toHaveBeenCalledWith(
      jasmine.any(Function),
      jasmine.objectContaining({ disableClose: true }),
    );
    expect(paymentElement.update).toHaveBeenCalledWith({ readOnly: true });
  });
});
