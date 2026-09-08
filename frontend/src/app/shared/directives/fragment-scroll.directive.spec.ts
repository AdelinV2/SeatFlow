import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FragmentScrollDirective } from './fragment-scroll.directive';

@Component({
  standalone: true,
  imports: [FragmentScrollDirective],
  template: `
    <a id="plain-link" href="#target" appFragmentScroll>Plain</a>
    <a id="explicit-link" href="#target" [appFragmentScroll]="'custom'">Explicit</a>
    <a id="focus-link" href="#custom" appFragmentScroll appFragmentScrollFocus>Focus</a>
    <a id="missing-link" href="#no-such-section" appFragmentScroll>Missing</a>
    <div id="target">target section</div>
    <div id="custom" tabindex="-1">custom section</div>
  `,
})
class TestHostComponent {}

describe('FragmentScrollDirective', () => {
  let fixture: ComponentFixture<TestHostComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [TestHostComponent],
    }).compileComponents();

    fixture = TestBed.createComponent(TestHostComponent);
    fixture.detectChanges();
  });

  afterEach(() => {
    history.replaceState(null, '', window.location.pathname);
    (document.activeElement as HTMLElement | null)?.blur?.();
  });

  function link(id: string): HTMLAnchorElement {
    return fixture.nativeElement.querySelector(`#${id}`) as HTMLAnchorElement;
  }

  it('scrolls to the href target and sets the hash without router navigation', () => {
    const target = fixture.nativeElement.querySelector('#target') as HTMLElement;
    const scrollSpy = spyOn(target, 'scrollIntoView');
    const pathBefore = window.location.pathname;

    link('plain-link').click();

    expect(scrollSpy).toHaveBeenCalledWith({ behavior: 'smooth', block: 'start' });
    expect(window.location.hash).toBe('#target');
    // No route change: same path, no reload-style navigation.
    expect(window.location.pathname).toBe(pathBefore);
  });

  it('prefers the explicit target binding over the href hash', () => {
    const custom = fixture.nativeElement.querySelector('#custom') as HTMLElement;
    const customSpy = spyOn(custom, 'scrollIntoView');
    const target = fixture.nativeElement.querySelector('#target') as HTMLElement;
    const targetSpy = spyOn(target, 'scrollIntoView');

    link('explicit-link').click();

    expect(customSpy).toHaveBeenCalled();
    expect(targetSpy).not.toHaveBeenCalled();
    expect(window.location.hash).toBe('#custom');
  });

  it('moves focus to the target when focus input is set', () => {
    link('focus-link').click();
    fixture.detectChanges();

    expect(document.activeElement?.id).toBe('custom');
  });

  it('leaves modified clicks to the browser for open-in-new-tab', () => {
    const anchor = link('plain-link');
    const target = fixture.nativeElement.querySelector('#target') as HTMLElement;
    const scrollSpy = spyOn(target, 'scrollIntoView');
    const event = new MouseEvent('click', {
      bubbles: true,
      cancelable: true,
      ctrlKey: true,
    });
    const preventSpy = spyOn(event, 'preventDefault').and.callThrough();

    anchor.dispatchEvent(event);

    expect(preventSpy).not.toHaveBeenCalled();
    expect(scrollSpy).not.toHaveBeenCalled();
  });

  it('does nothing harmful when the fragment target does not exist', () => {
    expect(() => link('missing-link').click()).not.toThrow();
    expect(window.location.hash).toBe('');
  });
});
