import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { UserContextService } from '../../../core/auth/user-context.service';
import { AssistantStore } from '../../../services/assistant-store.service';
import { AssistantDrawerComponent } from './assistant-drawer.component';

@Component({
  selector: 'app-assistant-drawer-stub',
  standalone: true,
  template: '<p>stub</p>',
})
class StubComponent {}

describe('AssistantDrawerComponent', () => {
  let fixture: ComponentFixture<AssistantDrawerComponent>;
  let store: AssistantStore;
  let httpMock: HttpTestingController;
  let userContext: UserContextService;
  let router: Router;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AssistantDrawerComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([
          { path: 'checkout/:reservationId', component: StubComponent },
          { path: 'events', component: StubComponent },
        ]),
      ],
    }).compileComponents();

    store = TestBed.inject(AssistantStore);
    httpMock = TestBed.inject(HttpTestingController);
    userContext = TestBed.inject(UserContextService);
    router = TestBed.inject(Router);
    userContext.setUser({
      id: 'user-1',
      email: 'user@seatflow.test',
      name: 'Test User',
      roles: ['ROLE_CUSTOMER'],
    });
    store.isOpen.set(true);
    fixture = TestBed.createComponent(AssistantDrawerComponent);
    fixture.detectChanges();
  });

  afterEach(() => {
    httpMock.verify();
    userContext.clearUser();
    TestBed.flushEffects();
  });

  function drawerText(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('renders header, body, and composer sections with a live region', () => {
    expect(drawerText()).toContain('SeatFlow Assistant');
    expect(drawerText()).toContain('Reset conversation');
    expect(drawerText()).toContain('Close');
    expect(drawerText()).toContain('Send');
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[aria-live="polite"]'),
    ).not.toBeNull();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[role="dialog"]'),
    ).not.toBeNull();
  });

  it('sends a starter prompt as a standard chat request', () => {
    const starter = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.assistant-drawer__starter'),
    ).find((button) => button.textContent?.includes('Find events this weekend.')) as HTMLButtonElement;
    expect(starter).toBeTruthy();

    starter.click();

    const req = httpMock.expectOne('/api/ai/chat');
    expect(req.request.body).toEqual({ message: 'Find events this weekend.' });
    req.flush({
      conversationId: 'conv-1',
      assistantMessage: 'Here are events.',
      state: 'DISCOVERING',
      cards: [],
      suggestedActions: [],
      error: null,
    });
    fixture.detectChanges();
    expect(drawerText()).toContain('Here are events.');
  });

  it('sends on Enter but not on Shift+Enter', () => {
    fixture.componentInstance.draft.set('hello');
    fixture.detectChanges();

    const input = (fixture.nativeElement as HTMLElement).querySelector(
      '#assistant-composer',
    ) as HTMLTextAreaElement;
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    httpMock.expectOne('/api/ai/chat');

    fixture.componentInstance.draft.set('line two');
    input.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Enter', shiftKey: true, bubbles: true }),
    );
    httpMock.expectNone('/api/ai/chat');
    expect(fixture.componentInstance.draft()).toBe('line two');
  });

  it('blocks over-limit composer input without HTTP', () => {
    fixture.componentInstance.draft.set('x'.repeat(2001));
    fixture.detectChanges();

    const send = (fixture.nativeElement as HTMLElement).querySelector(
      '.assistant-drawer__send',
    ) as HTMLButtonElement;
    expect(send.disabled).toBeTrue();
    fixture.componentInstance.send();
    httpMock.expectNone('/api/ai/chat');
  });

  it('closes on Escape and returns focus to the launcher', () => {
    const launcher = document.createElement('button');
    launcher.id = 'assistant-launcher';
    document.body.appendChild(launcher);

    try {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      expect(store.isOpen()).toBeFalse();
      expect(document.activeElement).toBe(launcher);
    } finally {
      launcher.remove();
    }
  });

  it('closes when checkout navigation starts', async () => {
    expect(store.isOpen()).toBeTrue();
    await router.navigateByUrl('/checkout/res-9');
    expect(store.isOpen()).toBeFalse();
  });

  it('closes the drawer when the user signs out', () => {
    expect(store.isOpen()).toBeTrue();
    userContext.clearUser();
    TestBed.flushEffects();
    fixture.detectChanges();
    expect(store.isOpen()).toBeFalse();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[role="dialog"]'),
    ).toBeNull();
  });

  it('renders an unavailable state without breaking the shell', () => {
    store.featureStatus.set({ enabled: false, state: 'DISABLED', model: null });
    store.statusLoaded.set(true);
    fixture.detectChanges();
    expect(drawerText()).toContain('currently unavailable');
  });

  it('REV-004: unavailable banner offers a bounded retry that refetches status', () => {
    store.ensureStatusLoaded();
    httpMock
      .expectOne('/api/ai/status')
      .flush({ message: 'down' }, { status: 503, statusText: 'Service Unavailable' });
    fixture.detectChanges();

    expect(drawerText()).toContain('currently unavailable');
    const retry = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll(
        '.assistant-drawer__unavailable button',
      ),
    ).find((button) => button.textContent?.includes('Try again')) as HTMLButtonElement;
    expect(retry).toBeTruthy();

    retry.click();
    httpMock
      .expectOne('/api/ai/status')
      .flush({ enabled: true, state: 'READY', model: 'test-model' });
    fixture.detectChanges();

    expect(store.isAvailable()).toBeTrue();
    expect(drawerText()).not.toContain('currently unavailable');
  });
});
