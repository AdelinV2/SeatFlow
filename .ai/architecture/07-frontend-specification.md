# 07 — Frontend Architecture & UI/UX Specification

The SeatFlow frontend is an **Angular 22** single-page application built with modern Signal-based reactivity, Standalone components, TailwindCSS v4, and Angular Material 22.

---

## 1. Application Architecture & Reactivity Model

- **Standalone Components:** 100% standalone architecture (`NgModule` is strictly prohibited).
- **OnPush Change Detection:** Mandatory on every component for optimal rendering performance.
- **Signals for State:** `signal()`, `computed()`, `input()`, `output()`, and `model()`. Never use `BehaviorSubject` for component state.
- **Dependency Injection:** Modern `inject()` function in field initializers.
- **Routing:** Lazy-loaded feature routes with View Transitions enabled (`withViewTransitions()`).

---

## 2. Feature Domains & Routing Structure

```text
/ (Home / Event Catalog)             --> EventListComponent (Featured carousel, category filter, date picker)
/events/:id                         --> EventDetailComponent (Description, venue info, pricing table, CTA)
/events/:id/seats                   --> SeatSelectionComponent (Interactive seat map, live WS updates, hold action)
/checkout/:reservationId            --> CheckoutComponent (Hold timer, order summary, Stripe Elements card form)
/order-confirmation/:paymentId      --> OrderConfirmationComponent (Success screen, download tickets, QR codes)
/profile/tickets                    --> MyTicketsComponent (Active and past digital tickets with QR modal)
/profile/settings                   --> UserSettingsComponent (Profile details, notification preferences)
/auth/callback                      --> AuthCallbackComponent (OIDC redirect handler)
/auth/logout                        --> Redirects to Entra logout endpoint
/admin/events                       --> AdminEventListComponent (Event CRUD, venue assignment, pricing manager)
/admin/venues                       --> AdminVenueListComponent (Venue layout designer, section builder)
/admin/scanner                      --> AdminScannerComponent (Camera-based QR code entry validator)
```

---

## 3. Core UI Components Specification

### 3.1 Interactive Seat Map Component (`SeatMapComponent`)
- **Layout Engine:** Responsive CSS Grid / SVG seat layout representing venue sections, rows, and numbered seats.
- **Seat Status Visual Encoding:**
  - `AVAILABLE` — Neutral slate background with subtle border, interactive hover effect, displays price on hover.
  - `SELECTING` (Local user) — Vibrant primary indigo with checkmark icon.
  - `HELD` (Other users / Pending hold) — Muted amber with lock icon (disabled).
  - `SOLD / RESERVED` — Slate-300 / dark grayed-out with cross (disabled).
  - `DISABLED / BLOCKED` — Transparent outline.
- **Selection Engine:**
  - Enforces client-side maximum selection of **10 seats**.
  - Recalculates total price instantly via `computed()`.
  - Connects to `WebSocketService` on initialization to receive real-time seat status updates.

### 3.2 Real-Time STOMP WebSocket Integration & Reconnection Reconciliation
- Subscribes to `/topic/events/{eventId}/seats`.
- When a `SeatStatusUpdated` message is received:
  1. Updates the `seats` Signal store.
  2. If a seat currently selected by the local user is held/booked by another user, deselects it and displays a warning notification via `MatSnackBar`.
- **Reconnection & State Reconciliation:** If the WebSocket client disconnects and reconnects (e.g. temporary network drop), the `onConnect` lifecycle callback automatically triggers `SeatService.loadSeats(eventId)` to fetch the authoritative seat availability matrix from `GET /api/events/{eventId}/availability` and reconcile the local Signal store.

### 3.3 Hold Countdown Timer Component (`HoldCountdownComponent`)
- Displays remaining hold time formatted as `MM:SS`.
- Visual alert state (pulsing red border/text) when remaining time falls below **120 seconds**.
- Emits `(expired)` event when counter reaches `00:00`, triggering redirect back to event details with an expiry dialog.

### 3.4 Stripe Test Mode Checkout (`CheckoutComponent`)
- Embeds Stripe Elements (`CardElement` or `PaymentElement`).
- Retrieves `clientSecret` from `POST /api/payments/intent`.
- Confirms payment client-side with Stripe SDK.
- Handles 3D Secure simulation and redirects to `/order-confirmation/:paymentId`.

---

## 4. Design System & Styling Rules

- **TailwindCSS v4 (CSS-first):** Primary styling engine for application shell, layout grids, spacing, responsive flex containers, and typography.
- **Angular Material 22:** Used strictly for complex interactive components:
  - `MatTable` & `MatPaginator` (Admin lists, order histories)
  - `MatDialog` (Ticket QR modal, confirmation dialogs)
  - `MatSnackBar` (Toasts for errors, conflicts, and seat hold expirations)
  - `MatDatepicker` (Accessible event date selection)
  - `MatFormField` & `MatSelect` (Form dropdowns)
- **Zero Style Leakage:** Never style internal Material classes directly with Tailwind utilities. Use container wrappers.
