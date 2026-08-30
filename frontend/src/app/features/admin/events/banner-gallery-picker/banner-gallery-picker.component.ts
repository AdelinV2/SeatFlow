import {
  ChangeDetectionStrategy,
  Component,
  input,
  output,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { BannerPreset } from '../../../../models/admin-event.model';

@Component({
  selector: 'app-banner-gallery-picker',
  standalone: true,
  imports: [CommonModule, FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './banner-gallery-picker.component.html',
  styleUrl: './banner-gallery-picker.component.scss',
})
export class BannerGalleryPickerComponent {
  readonly currentBannerUrl = input<string>('');
  readonly bannerSelected = output<string>();

  readonly customUrl = signal<string>('');

  readonly presets: BannerPreset[] = [
    {
      id: 'concert-1',
      title: 'Neon Symphony Live',
      category: 'CONCERT',
      url: 'https://images.unsplash.com/photo-1514525253161-7a46d19cd819?auto=format&fit=crop&w=1600&q=80',
    },
    {
      id: 'theatre-1',
      title: 'Royal Opera House Stage',
      category: 'THEATRE',
      url: 'https://images.unsplash.com/photo-1507676184212-d03ab07a01bf?auto=format&fit=crop&w=1600&q=80',
    },
    {
      id: 'electronic-1',
      title: 'Festival Laser Arena',
      category: 'FESTIVAL',
      url: 'https://images.unsplash.com/photo-1470225620780-dba8ba36b745?auto=format&fit=crop&w=1600&q=80',
    },
    {
      id: 'sports-1',
      title: 'Basketball Championship Arena',
      category: 'SPORTS',
      url: 'https://images.unsplash.com/photo-1546519638-68e109498ffc?auto=format&fit=crop&w=1600&q=80',
    },
    {
      id: 'comedy-1',
      title: 'Vintage Spotlight Microphone',
      category: 'COMEDY',
      url: 'https://images.unsplash.com/photo-1585699324551-f6c309eedeca?auto=format&fit=crop&w=1600&q=80',
    },
    {
      id: 'symphony-1',
      title: 'Classical Philharmonic Orchestra',
      category: 'SYMPHONY',
      url: 'https://images.unsplash.com/photo-1465847899084-d164df4dedc6?auto=format&fit=crop&w=1600&q=80',
    },
  ];

  selectPreset(url: string): void {
    this.customUrl.set('');
    this.bannerSelected.emit(url);
  }

  applyCustomUrl(): void {
    const trimmed = this.customUrl().trim();
    if (trimmed) {
      this.bannerSelected.emit(trimmed);
    }
  }

  clearBanner(): void {
    this.customUrl.set('');
    this.bannerSelected.emit('');
  }
}
