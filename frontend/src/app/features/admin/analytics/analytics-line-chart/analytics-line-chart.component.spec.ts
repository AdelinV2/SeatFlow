import { ComponentFixture, TestBed } from '@angular/core/testing';
import { AnalyticsLineChartComponent } from './analytics-line-chart.component';
import { AnalyticsSeries } from '../../../../models/admin-analytics.model';

describe('AnalyticsLineChartComponent', () => {
  let component: AnalyticsLineChartComponent;
  let fixture: ComponentFixture<AnalyticsLineChartComponent>;

  async function setup(series: AnalyticsSeries[]): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [AnalyticsLineChartComponent],
    }).compileComponents();
    fixture = TestBed.createComponent(AnalyticsLineChartComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('seriesList', series);
    fixture.detectChanges();
  }

  function countSeries(): AnalyticsSeries[] {
    const points = Array.from({ length: 30 }, (_, i) => ({
      date: `2026-08-${String(i + 1).padStart(2, '0')}`,
      value: i + 1,
    }));
    return [{ currency: null, testMode: null, points }];
  }

  it('should create and render a 30-day count series without NaN attributes', async () => {
    await setup(countSeries());
    const svg: SVGElement | null = fixture.nativeElement.querySelector('svg');
    expect(svg).toBeTruthy();
    expect(svg?.getAttribute('viewBox')).toContain('720');
    expect(svg?.querySelector('title')).toBeTruthy();
    expect(svg?.querySelector('desc')).toBeTruthy();
    const polyline = svg?.querySelector('polyline');
    expect(polyline?.getAttribute('points')).not.toContain('NaN');
    // accessible table equivalent lists all 30 dates
    const rows = fixture.nativeElement.querySelectorAll('tbody tr');
    expect(rows.length).toBe(30);
  });

  it('should render an all-zero series on a valid zero baseline without NaN', async () => {
    await setup([
      {
        currency: null,
        testMode: null,
        points: [
          { date: '2026-09-04', value: 0 },
          { date: '2026-09-05', value: 0 },
          { date: '2026-09-06', value: 0 },
        ],
      },
    ]);
    expect(component.maxValue()).toBe(0);
    expect(component.divisor()).toBe(1);
    fixture.detectChanges();
    const svg: SVGElement | null = fixture.nativeElement.querySelector('svg');
    expect(svg?.querySelector('polyline')?.getAttribute('points')).not.toContain('NaN');
    // all dots sit exactly on the zero baseline
    for (const d of component.renderSeries()[0].dots) {
      expect(d.y).toBe(component.baselineY());
    }
  });

  it('should render a single point centered without crashing', async () => {
    await setup([
      { currency: null, testMode: null, points: [{ date: '2026-09-06', value: 5 }] },
    ]);
    fixture.detectChanges();
    const svg: SVGElement | null = fixture.nativeElement.querySelector('svg');
    // single point renders as a dot, not a polyline
    expect(svg?.querySelector('polyline')).toBeFalsy();
    expect(svg?.querySelectorAll('circle').length).toBe(1);
    const rows = fixture.nativeElement.querySelectorAll('tbody tr');
    expect(rows.length).toBe(1);
  });

  it('should label multi-currency series distinctly and never merge them', async () => {
    await setup([
      {
        currency: 'RON',
        testMode: true,
        points: [{ date: '2026-09-06', value: 303000 }],
      },
      {
        currency: 'EUR',
        testMode: true,
        points: [{ date: '2026-09-06', value: 12000 }],
      },
    ]);
    fixture.detectChanges();
    const labels = component.renderSeries().map((s) => s.label);
    expect(labels).toEqual(['RON', 'EUR']);
    const legend: HTMLElement = fixture.nativeElement.querySelector('.analytics-chart__legend');
    expect(legend.textContent).toContain('RON');
    expect(legend.textContent).toContain('EUR');
    const headers = Array.from(
      fixture.nativeElement.querySelectorAll('thead th'),
    ).map((h) => (h as HTMLElement).textContent?.trim());
    expect(headers).toEqual(jasmine.arrayWithExactContents(['Date', 'RON', 'EUR']));
  });

  it('should show an empty state when there are no points', async () => {
    await setup([]);
    const status: HTMLElement | null = fixture.nativeElement.querySelector('[role="status"]');
    expect(status?.textContent).toContain('No trend data');
    expect(fixture.nativeElement.querySelector('svg')).toBeFalsy();
  });
});
