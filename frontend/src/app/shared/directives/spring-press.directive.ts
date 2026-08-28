import { Directive, input } from '@angular/core';

@Directive({
  selector: '[appSpringPress]',
  standalone: true,
  host: {
    class: 'btn-spring active:scale-[0.97] transition-all duration-150 ease-out cursor-pointer select-none',
    '[class.pointer-events-none]': 'disabled()',
    '[class.opacity-50]': 'disabled()',
  },
})
export class SpringPressDirective {
  readonly disabled = input(false, { alias: 'appSpringPress' });
}
