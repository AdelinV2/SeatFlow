import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

export type SkeletonShape = 'text' | 'rectangle' | 'circle';

@Component({
  selector: 'app-skeleton-loader',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './skeleton-loader.component.html',
  styleUrl: './skeleton-loader.component.scss',
})
export class SkeletonLoaderComponent {
  readonly shape = input<SkeletonShape>('text');
  readonly lines = input(1);
  readonly width = input('100%');
  readonly height = input('1rem');
  readonly label = input('Loading content');

  readonly lineItems = computed(() => Array.from({ length: Math.max(1, this.lines()) }));
}
