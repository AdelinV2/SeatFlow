import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { UserContextService } from '../../../core/auth/user-context.service';
import { EventDetail, VenueDetail } from '../../../models/event.model';
import { PaymentStatusResponse } from '../../../models/payment.model';
import { EventApiService } from '../../../services/event-api.service';
import { PaymentApiService } from '../../../services/payment-api.service';
import {
  ReservationApiService,
  ReservationResponse,
} from '../../../services/reservation-api.service';
import { OrderConfirmationComponent } from './order-confirmation.component';

describe('OrderConfirmationComponent', () => {
  let component: OrderConfirmationComponent;
  let fixture: ComponentFixture<OrderConfirmationComponent>;
  let paymentApiSpy: jasmine.SpyObj<PaymentApiService>;
  let reservationApiSpy: jasmine.SpyObj<ReservationApiService>;
  let eventApiSpy: jasmine.SpyObj<EventApiService>;
  let userContextSpy: jasmine.SpyObj<UserContextService>;

  const mockPayment: PaymentStatusResponse = {
    id: 'pay-uuid-12345678',
    reservationId: 'res-uuid-87654321',
    customerEmail: 'buyer@seatflow.dev',
    amount: 150.0,
    taxAmount: 28.5,
    netAmount: 121.5,
    currency: 'USD',
    status: 'SUCCESS',
    createdAt: '2026-08-30T14:30:00Z',
  };

  const mockReservation: ReservationResponse = {
    id: 'res-uuid-87654321',
    eventId: 'event-uuid-001',
    customerEmail: 'buyer@seatflow.dev',
    customerName: 'Sam Taylor',
    status: 'CONFIRMED',
    expiresAt: '2026-08-30T14:45:00Z',
    totalAmount: 150.0,
    seats: [
      { seatId: 'seat-1', rowNumber: 'B', seatNumber: 12, price: 75.0 },
      { seatId: 'seat-2', rowNumber: 'B', seatNumber: 13, price: 75.0 },
    ],
  };

  const mockEvent: EventDetail = {
    id: 'event-uuid-001',
    venueId: 'venue-1',
    title: 'Rock Legends Arena Tour',
    description: 'Electrifying performance',
    category: 'CONCERT',
    bannerUrl: 'https://cdn.seatflow.com/rock.jpg',
    eventDate: '2026-11-20T20:00:00Z',
    status: 'PUBLISHED',
    venueName: 'Olympic Arena',
    pricingTiers: [],
    createdAt: '2026-08-01T10:00:00Z',
  };

  const mockVenue: VenueDetail = {
    id: 'venue-1',
    name: 'Olympic Arena',
    city: 'Los Angeles',
    address: '1000 Olympic Blvd',
    country: 'USA',
    capacity: 15000,
    latitude: 34.0522,
    longitude: -118.2437,
    sections: [],
  };

  beforeEach(async () => {
    paymentApiSpy = jasmine.createSpyObj('PaymentApiService', ['getPaymentStatus']);
    reservationApiSpy = jasmine.createSpyObj('ReservationApiService', ['getReservation']);
    eventApiSpy = jasmine.createSpyObj('EventApiService', ['getEventById', 'getVenueById']);
    userContextSpy = jasmine.createSpyObj('UserContextService', [
      'isAuthenticated',
      'userName',
      'userEmail',
    ]);

    paymentApiSpy.getPaymentStatus.and.returnValue(of(mockPayment));
    reservationApiSpy.getReservation.and.returnValue(of(mockReservation));
    eventApiSpy.getEventById.and.returnValue(of(mockEvent));
    eventApiSpy.getVenueById.and.returnValue(of(mockVenue));
    userContextSpy.isAuthenticated.and.returnValue(false);

    await TestBed.configureTestingModule({
      imports: [OrderConfirmationComponent],
      providers: [
        provideRouter([]),
        { provide: PaymentApiService, useValue: paymentApiSpy },
        { provide: ReservationApiService, useValue: reservationApiSpy },
        { provide: EventApiService, useValue: eventApiSpy },
        { provide: UserContextService, useValue: userContextSpy },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: {
                get: (key: string) => (key === 'paymentId' ? 'pay-uuid-12345678' : null),
              },
            },
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(OrderConfirmationComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('loads payment and reservation details from paymentId param', () => {
    expect(paymentApiSpy.getPaymentStatus).toHaveBeenCalledWith('pay-uuid-12345678');
    expect(reservationApiSpy.getReservation).toHaveBeenCalledWith(
      'res-uuid-87654321',
      'buyer@seatflow.dev',
    );
    expect(eventApiSpy.getEventById).toHaveBeenCalledWith('event-uuid-001');
    expect(component.payment()).toEqual(mockPayment);
    expect(component.orderReference()).toBe('PAY-UUID');
    expect(component.seatCount()).toBe(2);
    expect(component.totalAmount()).toBe(150.0);
    expect(component.taxAmount()).toBe(28.5);
  });

  it('identifies guest checkout state and shows account linking banner', () => {
    expect(component.isGuest()).toBeTrue();
    const banner = fixture.nativeElement.querySelector('.bg-gradient-to-r');
    expect(banner).toBeTruthy();
  });

  it('handles payment lookup error state', () => {
    paymentApiSpy.getPaymentStatus.and.returnValue(throwError(() => new Error('Not found')));
    component.ngOnInit();
    fixture.detectChanges();

    expect(component.errorMessage()).toBeTruthy();
    expect(component.isLoading()).toBeFalse();
  });

  it('triggers window.print when printReceipt() is called in browser', () => {
    spyOn(window, 'print');
    component.printReceipt();
    expect(window.print).toHaveBeenCalled();
  });
});
