import { TestBed } from '@angular/core/testing';
import { AudioFeedbackService } from './audio-feedback.service';

describe('AudioFeedbackService', () => {
  let service: AudioFeedbackService;
  let vibrateSpy: jasmine.Spy;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [AudioFeedbackService],
    });
    service = TestBed.inject(AudioFeedbackService);

    vibrateSpy = jasmine.createSpy('vibrate');
    if (typeof navigator !== 'undefined') {
      try {
        Object.defineProperty(navigator, 'vibrate', {
          value: vibrateSpy,
          writable: true,
          configurable: true,
        });
      } catch {
        // Fallback if property is non-configurable
      }
    }
  });

  it('toggles and sets mute state correctly', () => {
    expect(service.isMuted()).toBeFalse();
    const newMuted = service.toggleMute();
    expect(newMuted).toBeTrue();
    expect(service.isMuted()).toBeTrue();

    service.setMuted(false);
    expect(service.isMuted()).toBeFalse();
  });

  it('triggers short vibration [100] for SUCCESS', () => {
    service.playFeedback('SUCCESS');
    expect(vibrateSpy).toHaveBeenCalledWith([100]);
  });

  it('triggers warning vibration [150, 50, 150] for ALREADY_USED', () => {
    service.playFeedback('ALREADY_USED');
    expect(vibrateSpy).toHaveBeenCalledWith([150, 50, 150]);
  });

  it('triggers long alert vibration [400] for INVALID or CANCELLED', () => {
    service.playFeedback('INVALID');
    expect(vibrateSpy).toHaveBeenCalledWith([400]);

    service.playFeedback('CANCELLED');
    expect(vibrateSpy).toHaveBeenCalledWith([400]);
  });

  it('handles playFeedback safely when muted', () => {
    service.setMuted(true);
    expect(() => service.playFeedback('SUCCESS')).not.toThrow();
  });
});
