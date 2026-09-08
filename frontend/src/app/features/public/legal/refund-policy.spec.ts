import { refundBoundaryStatement, refundCutoffSummary, REFUND_CUTOFF_HOURS } from './refund-policy';

describe('refund-policy display helper (TASK-P16-003)', () => {
  it('pins the cutoff to exactly 24 hours', () => {
    expect(REFUND_CUTOFF_HOURS).toBe(24);
  });

  it('states the full-reservation scope with the exact 24 hours rule', () => {
    const summary = refundCutoffSummary();
    expect(summary).toContain('24 hours');
    expect(summary).toContain('full reservation');
    expect(summary).toContain('at least 24 hours remain');
    expect(summary).not.toContain('partial');
    expect(summary).not.toContain('per-ticket');
  });

  it('states the exact 24:00:00 boundary without ambiguity', () => {
    const boundary = refundBoundaryStatement();
    expect(boundary).toContain('Exactly 24 hours (24:00:00)');
    expect(boundary).toContain('below 24 hours is not eligible');
    expect(boundary).not.toContain('one day before');
  });
});
