import { ComponentFixture, TestBed } from '@angular/core/testing';
import { EventSession } from '../../../models/event.model';
import { SessionSelectorComponent } from './session-selector.component';

describe('SessionSelectorComponent', () => {
  let component: SessionSelectorComponent;
  let fixture: ComponentFixture<SessionSelectorComponent>;

  const mockSessions: EventSession[] = [
    {
      id: 'session-2',
      eventId: 'event-1',
      startsAt: '2027-05-02T19:00:00Z',
      endsAt: '2027-05-02T21:00:00Z',
      status: 'SCHEDULED',
      timezone: 'America/New_York',
    },
    {
      id: 'session-1',
      eventId: 'event-1',
      startsAt: '2027-05-01T19:00:00Z',
      endsAt: '2027-05-01T21:00:00Z',
      status: 'SCHEDULED',
      timezone: 'America/New_York',
    },
    {
      id: 'session-cancelled',
      eventId: 'event-1',
      startsAt: '2027-05-03T19:00:00Z',
      endsAt: '2027-05-03T21:00:00Z',
      status: 'CANCELLED',
      timezone: 'America/New_York',
    },
  ];

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SessionSelectorComponent],
    }).compileComponents();

    fixture = TestBed.createComponent(SessionSelectorComponent);
    component = fixture.componentInstance;
  });

  it('should render empty message when sessions list is empty', () => {
    fixture.componentRef.setInput('sessions', []);
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent;
    expect(text).toContain('No upcoming showtimes available');
  });

  it('should sort sessions chronologically by startsAt', () => {
    fixture.componentRef.setInput('sessions', mockSessions);
    fixture.detectChanges();

    const enriched = component.enrichedSessions();
    expect(enriched[0].session.id).toBe('session-1');
    expect(enriched[1].session.id).toBe('session-2');
    expect(enriched[2].session.id).toBe('session-cancelled');
  });

  it('should mark cancelled session as non-selectable', () => {
    fixture.componentRef.setInput('sessions', mockSessions);
    fixture.detectChanges();

    const cancelled = component.enrichedSessions().find((s) => s.session.id === 'session-cancelled');
    expect(cancelled?.isSelectable).toBeFalse();
    expect(cancelled?.state).toBe('CANCELLED');
  });

  it('should emit sessionSelected when an available session is clicked', () => {
    fixture.componentRef.setInput('sessions', mockSessions);
    fixture.detectChanges();

    let emittedSession: EventSession | null = null;
    component.sessionSelected.subscribe((s) => (emittedSession = s));

    const buttons = fixture.nativeElement.querySelectorAll('button[role="radio"]');
    buttons[0].click(); // session-1

    expect(emittedSession).toBeTruthy();
    expect(emittedSession!.id).toBe('session-1');
  });

  it('should not emit sessionSelected when clicking a non-selectable session', () => {
    fixture.componentRef.setInput('sessions', mockSessions);
    fixture.detectChanges();

    let emittedSession: EventSession | null = null;
    component.sessionSelected.subscribe((s) => (emittedSession = s));

    const buttons = fixture.nativeElement.querySelectorAll('button[role="radio"]');
    buttons[2].click(); // session-cancelled

    expect(emittedSession).toBeNull();
  });

  it('should not emit sessionSelected when component is disabled', () => {
    fixture.componentRef.setInput('sessions', mockSessions);
    fixture.componentRef.setInput('disabled', true);
    fixture.detectChanges();

    let emittedSession: EventSession | null = null;
    component.sessionSelected.subscribe((s) => (emittedSession = s));

    const buttons = fixture.nativeElement.querySelectorAll('button[role="radio"]');
    buttons[0].click();

    expect(emittedSession).toBeNull();
  });

  it('should disable an already-started but not-yet-ended session without emitting (REV-004 FIX-2)', () => {
    const startedSession: EventSession = {
      id: 'session-started',
      eventId: 'event-1',
      startsAt: new Date(Date.now() - 30 * 60 * 1000).toISOString(),
      endsAt: new Date(Date.now() + 90 * 60 * 1000).toISOString(),
      status: 'SCHEDULED',
      timezone: 'America/New_York',
    };
    fixture.componentRef.setInput('sessions', [startedSession]);
    fixture.detectChanges();

    const enriched = component.enrichedSessions();
    expect(enriched[0].isSelectable).toBeFalse();

    let emittedSession: EventSession | null = null;
    component.sessionSelected.subscribe((s) => (emittedSession = s));
    component.selectSession(enriched[0]);

    expect(emittedSession).toBeNull();
    const button = fixture.nativeElement.querySelector('button[role="radio"]');
    expect(button.disabled).toBeTrue();
  });
});
