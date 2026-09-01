import { NgClass } from '@angular/common';
import { ChangeDetectionStrategy, Component, input } from '@angular/core';

export type GlassCardElevation = 'flat' | 'raised' | 'elevated';

@Component({
  selector: 'app-glass-card',
  standalone: true,
  imports: [NgClass],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './glass-card.component.html',
  styleUrl: './glass-card.component.scss',
})
export class GlassCardComponent {
  readonly elevation = input<GlassCardElevation>('raised');
  readonly interactive = input(false);
  readonly padding = input<'none' | 'sm' | 'md' | 'lg'>('md');
}
