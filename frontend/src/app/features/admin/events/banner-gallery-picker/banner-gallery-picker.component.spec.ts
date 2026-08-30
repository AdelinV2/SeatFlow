import { ComponentFixture, TestBed } from '@angular/core/testing';
import { BannerGalleryPickerComponent } from './banner-gallery-picker.component';

describe('BannerGalleryPickerComponent', () => {
  let component: BannerGalleryPickerComponent;
  let fixture: ComponentFixture<BannerGalleryPickerComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [BannerGalleryPickerComponent],
    }).compileComponents();

    fixture = TestBed.createComponent(BannerGalleryPickerComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should emit selected preset banner url', () => {
    spyOn(component.bannerSelected, 'emit');
    const preset = component.presets[0];

    component.selectPreset(preset.url);

    expect(component.bannerSelected.emit).toHaveBeenCalledWith(preset.url);
    expect(component.customUrl()).toBe('');
  });

  it('should apply custom HTTPS url and emit bannerSelected', () => {
    spyOn(component.bannerSelected, 'emit');
    const custom = 'https://images.unsplash.com/photo-custom?auto=format';

    component.customUrl.set(custom);
    component.applyCustomUrl();

    expect(component.bannerSelected.emit).toHaveBeenCalledWith(custom);
  });

  it('should not emit if custom URL is empty or blank', () => {
    spyOn(component.bannerSelected, 'emit');

    component.customUrl.set('   ');
    component.applyCustomUrl();

    expect(component.bannerSelected.emit).not.toHaveBeenCalled();
  });

  it('should clear banner', () => {
    spyOn(component.bannerSelected, 'emit');
    component.customUrl.set('https://some-url.jpg');

    component.clearBanner();

    expect(component.bannerSelected.emit).toHaveBeenCalledWith('');
    expect(component.customUrl()).toBe('');
  });
});
