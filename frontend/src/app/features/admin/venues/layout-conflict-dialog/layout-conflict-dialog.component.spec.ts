import { Clipboard } from '@angular/cdk/clipboard';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import {
  LayoutConflictDialogComponent,
  LayoutConflictDialogData,
} from './layout-conflict-dialog.component';

describe('LayoutConflictDialogComponent', () => {
  let fixture: ComponentFixture<LayoutConflictDialogComponent>;
  let component: LayoutConflictDialogComponent;
  let clipboardSpy: jasmine.SpyObj<Clipboard>;
  let dialogRefSpy: jasmine.SpyObj<MatDialogRef<LayoutConflictDialogComponent>>;

  const dialogData: LayoutConflictDialogData = {
    localVersion: 7,
    correlationId: 'corr-123',
    snapshotJson: '{"layoutVersion":7}',
  };

  beforeEach(async () => {
    clipboardSpy = jasmine.createSpyObj('Clipboard', ['copy']);
    clipboardSpy.copy.and.returnValue(true);
    dialogRefSpy = jasmine.createSpyObj('MatDialogRef', ['close']);

    await TestBed.configureTestingModule({
      imports: [LayoutConflictDialogComponent],
      providers: [
        { provide: Clipboard, useValue: clipboardSpy },
        { provide: MatDialogRef, useValue: dialogRefSpy },
        { provide: MAT_DIALOG_DATA, useValue: dialogData },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(LayoutConflictDialogComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should display the local version and correlation id as text', () => {
    const root: HTMLElement = fixture.nativeElement;
    expect(root.textContent).toContain('7');
    expect(root.textContent).toContain('corr-123');
    expect(component.localVersion()).toBe(7);
  });

  it('should expose no force-save action', () => {
    const root: HTMLElement = fixture.nativeElement;
    const buttons = [...root.querySelectorAll('button')].map((b) =>
      (b.textContent ?? '').toLowerCase(),
    );
    expect(buttons.join(' ')).not.toContain('force');
    expect(buttons.join(' ')).not.toContain('overwrite');
  });

  it('should close with keep-editing', () => {
    component.keepEditing();
    expect(dialogRefSpy.close).toHaveBeenCalledWith('keep-editing');
  });

  it('should close with reload for server reload', () => {
    component.reloadServer();
    expect(dialogRefSpy.close).toHaveBeenCalledWith('reload');
  });

  it('should copy snapshot JSON to clipboard on success', () => {
    component.copyLocalJson();
    expect(clipboardSpy.copy).toHaveBeenCalledWith('{"layoutVersion":7}');
    expect(component.copied()).toBeTrue();
    expect(component.showFallback()).toBeFalse();
  });

  it('should show selectable fallback text when clipboard copy fails', () => {
    clipboardSpy.copy.and.returnValue(false);
    component.copyLocalJson();
    fixture.detectChanges();

    expect(component.showFallback()).toBeTrue();
    const fallback = fixture.nativeElement.querySelector(
      '[data-testid="conflict-fallback-json"]',
    ) as HTMLTextAreaElement | null;
    expect(fallback).not.toBeNull();
    expect(fallback?.textContent).toContain('{"layoutVersion":7}');
  });

  it('should render a hostile snapshot payload as escaped text, never as HTML', () => {
    const hostile = '{"x":"<img src=x onerror=alert(1)>"}';
    component.snapshotJson.set(hostile);
    component.showFallback.set(true);
    fixture.detectChanges();

    const fallback = fixture.nativeElement.querySelector(
      '[data-testid="conflict-fallback-json"]',
    ) as HTMLElement | null;
    expect(fallback?.querySelector('img')).toBeNull();
    expect(fallback?.textContent).toContain('<img src=x onerror=alert(1)>');
  });
});
