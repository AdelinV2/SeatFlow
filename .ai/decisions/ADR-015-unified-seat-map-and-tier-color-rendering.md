# ADR-015: Unified Hall Seat Map with Global Geometry and Color-Coded Pricing Tiers

- **Date:** 2026-09-03
- **Author(s):** Architecture Team & AI Pair
- **Driven by Task:** Phase 11 — Advanced Venue & Seat Map Designer (`TASK-P11-006`, `TASK-P11-012`)
- **Supersedes:** N/A (Complements and refines ADR-010 for customer presentation)

## 1. Status
`ACCEPTED`

## 2. Context
In the Phase 09 MVP implementation, the customer seat selection component (`SeatMapComponent`) rendered venue sections inside isolated tabs (e.g. `Balcony`, `Orchestra`, `Loge`). Customers were required to manually toggle between tabs, rendering only one section at a time. This constraint existed because MVP venue models lacked continuous 2D venue-canvas coordinates; seats only possessed section-relative matrix indices (`grid_x`, `grid_y`), which would have overlapped if rendered simultaneously.

Real-world event and venue ticketing (e.g., classical theatres, opera houses, arenas, concert halls) requires displaying the **entire venue layout simultaneously** on a single unified canvas (stage, orchestra, loges, and balconies all visible together in their true physical spatial context). Furthermore, seats throughout the auditorium belong to distinct pricing categories / tiers (e.g., *Categoria A: 150 Lei*, *Categoria B: 120 Lei*, *Categoria C: 100 Lei*, *Categoria D: 80 Lei*, *Categoria E: 70 Lei*), visually color-coded according to a persistent top legend bar, with booked/held seats rendered in subdued neutral gray.

With ADR-010 establishing continuous 2D coordinates (`position_x`, `position_y`, `rotation_deg`, `width`, `height`), the venue model now fully supports global visual placement. However, the Phase 11 customer renderer specification (`TASK-P11-012`) retained legacy references to section tabs and section isolation.

## 3. Decision
1. **Unified Customer Viewport (No Mandatory Section Switching):**
   - Eliminate section tabs and mandatory section isolation from the customer seat selection experience (`SeatMapComponent`).
   - Render all active sections, layout elements (`STAGE`, `AISLE`, `LABEL`), and seats simultaneously within a unified SVG pan-zoom viewport.
   - All seats across all sections are immediately discoverable and clickable on the main canvas without requiring users to pre-select a section.

2. **Top Category & Pricing Legend:**
   - Replace the legacy section-selector tab bar with a prominent top **Pricing Category Legend bar**.
   - The legend dynamically lists active pricing tiers for the event (e.g., `Categoria A: 150 Lei`, `Categoria B: 120 Lei`, etc.), each with its designated color badge and available seat count/summary.
   - Interactive filtering (e.g., clicking a category in the legend) highlights matching seats across the entire venue or dims non-matching seats, rather than hiding other sections.

3. **Color-Coded Seat State Machine:**
   - Available seats are visually colored according to their respective pricing tier/category (using accessible, high-contrast color palettes).
   - Selected seats display the distinctive selection highlight and spring animation.
   - Held, sold, or disabled seats are uniformly muted (neutral gray), maintaining immediate clarity between availability and tier distinction.
   - Hover and tooltip states show complete contextual information: Section name, Row label, Seat number, Category name, and Price/Currency.

4. **Preserved Invariants & ARIA Accessibility:**
   - Stable domain identifiers (`seatId`, `sectionId`, `pricingTierId`) remain untouched across booking, reservation, outbox, and WebSocket reconciliation.
   - ARIA 2D grid/roving tabindex semantics are preserved, allowing keyboard users to navigate seats seamlessly across rows and sections using arrow keys.
   - Client-side selection limit (strictly maximum 10 seats) and server-side holds remain intact.

## 4. Alternatives Considered
1. **Retain Tab-Based Section Isolation (MVP Model):**
   - *Pros:* Simpler rendering when sections have disconnected coordinate grids.
   - *Cons:* Disjointed customer experience; prevents users from understanding the physical layout relative to the stage; forces repetitive tab clicking.
   - *Reason for Rejection:* Contradicts modern ticketing expectations and the 2D layout capabilities delivered by Phase 11.

2. **Modal / Drill-down Section Selector:**
   - *Pros:* Allows high zoom on smaller mobile devices.
   - *Cons:* Breaks visual continuity, requires extra modal interactions, and prevents selecting seats across adjacent sections in a single order.
   - *Reason for Rejection:* Replaced by smooth pan/pinch-to-zoom on the unified canvas with optional category-based quick-focus.

## 5. Consequences
### Positive:
- Matches standard real-world ticketing UX (theatre, opera, arenas) as requested by business stakeholders.
- Provides immediate visual comprehension of stage position, distance, and pricing tiers across the hall.
- Simplifies customer journey: zero tab switches to select seats across different sections.

### Negative / Trade-offs:
- Rendering 500–3,000 SVG elements simultaneously requires clean DOM management and hardware-accelerated CSS transforms.
- Mitigation: Section transforms utilize SVG `<g>` translation/rotation; seat nodes use lightweight `<circle>` elements with CSS variables for tier colors; touch-pan/zoom uses passive pointer events and `requestAnimationFrame`.

## 6. Implementation Notes
- **Impacted Tasks:**
  - `TASK-P11-006`: Ensure `LayoutCanvasComponent` supports simultaneous multi-section and element rendering in read-only customer mode.
  - `TASK-P11-012`: Refactor `SeatMapComponent` to render the unified hall canvas, remove section tabs, and introduce the top category color-coded legend bar.
- **Specification Updates:**
  - `.ai/tasks/phase-11-advanced-seat-map-designer/000-phase-overview.md`
  - `.ai/architecture/07-frontend-specification.md` §4.5 & §8
  - `.ai/SeatFlow-Architecture-and-Implementation-Spec.md` §8.3
