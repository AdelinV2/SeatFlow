import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { TaxComponent } from './tax.component';

describe('TaxComponent (TASK-P16-003)', () => {
  let fixture: ComponentFixture<TaxComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [TaxComponent],
      providers: [provideRouter([])],
    }).compileComponents();
    fixture = TestBed.createComponent(TaxComponent);
    fixture.detectChanges();
  });

  it('uses the P16-001 content shell and renders exactly one h1', () => {
    expect(fixture.nativeElement.querySelector('app-content-page')).not.toBeNull();
    expect(fixture.nativeElement.querySelectorAll('h1').length).toBe(1);
  });

  it('reflects the implemented tax-inclusive preview boundary', () => {
    const text: string = fixture.nativeElement.textContent ?? '';
    expect(text).toContain('tax-inclusive');
    expect(text).toContain('billing address');
    expect(text).toContain('street address');
    expect(text).toContain('breaks the total down instead of adding an extra charge');
  });

  it('states the transmitted-not-stored address boundary without schema exposure', () => {
    const text: string = fixture.nativeElement.textContent ?? '';
    expect(text).toContain('transmitted for that preview');
    expect(text).toContain('durable storage of the address beyond the payment tax and net record is not confirmed');
    expect(text).not.toContain('line1');
    expect(text).not.toContain('postalCode');
  });

  it('explains that previews are informational and the backend record is authoritative', () => {
    const text: string = fixture.nativeElement.textContent ?? '';
    expect(text).toContain('informational');
    expect(text).toContain('does not lock the final amount');
    expect(text).toContain('backend payment record and the provider confirmation decide');
  });

  it('describes full-amount refund tax treatment without a partial path', () => {
    const text: string = fixture.nativeElement.textContent ?? '';
    expect(text).toContain('full reservation refund');
    expect(text).toContain('including whatever tax portion was recorded');
    expect(text).toContain('no separate tax-only or partial refund');
  });

  it('discloses Stripe Test Mode demo status and denies invoicing suitability', () => {
    const text: string = fixture.nativeElement.textContent ?? '';
    expect(text).toContain('Stripe Test Mode');
    expect(text).toContain('portfolio/demo deployment');
    expect(text).toContain('not suitable for real invoicing or accounting');
  });

  it('gives no tax advice and promises no valid invoice', () => {
    const text: string = (fixture.nativeElement.textContent ?? '').toLowerCase();
    expect(text).toContain('not a tax adviser');
    expect(text).toContain('does not issue legally valid invoices');
    expect(text).not.toContain('you should claim');
    expect(text).not.toContain('tax advice for your');
  });

  it('states card details are never stored and publishes no secrets', () => {
    const text: string = fixture.nativeElement.textContent ?? '';
    expect(text).toContain('never stored by SeatFlow');
    for (const secret of ['eyJhbGci', 'gsk_', 'sk_test', 'whsec_', 'BEGIN PRIVATE', 'pi_']) {
      expect(text).not.toContain(secret);
    }
  });

  it('links to Refunds, Terms, Privacy, Support, and Events', () => {
    const raw: string = fixture.nativeElement.innerHTML as string;
    for (const path of ['/legal/refunds', '/legal/terms', '/legal/privacy', '/support/contact', '/events']) {
      expect(raw).withContext(`links to ${path}`).toContain(path);
    }
  });
});
