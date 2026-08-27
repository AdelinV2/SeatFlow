# 07 — Frontend Architecture & UI/UX Specification

The SeatFlow frontend is an **Angular 22** single-page application built with modern Signal-based reactivity, Standalone components, TailwindCSS v4, and Angular Material 22.

---

## 1. Application Architecture & Reactivity Model

- **Standalone Components:** 100% standalone architecture (`NgModule` is strictly prohibited).
- **OnPush Change Detection:** Mandatory on every component (`ChangeDetectionStrategy.OnPush`) for optimal rendering performance.
- **Signals for State:** `signal()`, `computed()`, `input()`, `output()`, and `model()`. Never use `BehaviorSubject` for component state.
- **Dependency Injection:** Modern `inject()` function in field initializers.
- **Routing:** Lazy-loaded feature routes with View Transitions enabled (`withViewTransitions()`).
- **100% Mobile & Desktop Responsive:** Fluid layout breakpoints, safe-area mobile paddings, and touch target minimums ($44\times 44\text{px}$).

---

## 2. Design System: Sensory UI, Nuanced Color Palettes & Micro-Interactions

The design system is crafted to provide a luxurious, modern, and tactile user experience, completely avoiding harsh/flat pure `#000000` or `#FFFFFF` contrasts:

### 2.1 Theme & Color Palette Specification

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│ 🌙 DARK THEME — "Obsidian & Midnight Slate" (Zero pure #000000)             │
│ • Background Canvas:   #0B0F19 (Deep rich slate with subtle sapphire hue)   │
│ • Card Surface:        #111827 (Layered matte surface)                      │
│ • Elevated / Modals:   #1E293B (Elevated dialogs & popovers)                │
│ • Subtle Borders:      rgba(255, 255, 255, 0.08)                            │
│ • Ambient Glows:       rgba(99, 102, 241, 0.15) (Soft Indigo/Violet blooms) │
│ • Primary Accents:     Electric Indigo (#6366F1), Vivid Violet (#8B5CF6)    │
│ • Semantic Accents:    Emerald (#10B981), Amber (#F59E0B), Rose (#F43F5E)   │
│ • Text Hierarchy:      #F8FAFC (Headings), #94A3B8 (Body), #64748B (Muted)  │
└─────────────────────────────────────────────────────────────────────────────┘
                               ▲
                 [ Smooth Theme Switcher Engine ]
                               ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ ☀️ LIGHT THEME — "Warm Alabaster & Pearl Slate" (Zero pure flat #FFFFFF)    │
│ • Background Canvas:   #F8FAFC to #F1F5F9 (Warm pearl slate background)     │
│ • Card Surface:        #FFFFFF (Clean floating cards with soft borders)     │
│ • Subtle Borders:      #E2E8F0 (Crisp, soft division lines)                 │
│ • Layered Shadows:     0 10px 25px -5px rgba(15, 23, 42, 0.05) (Soft depth) │
│ • Primary Accents:     Royal Indigo (#4F46E5), Deep Violet (#7C3AED)        │
│ • Semantic Accents:    Forest Emerald (#059669), Warm Amber (#D97706)       │
│ • Text Hierarchy:      #0F172A (Headings), #334155 (Body), #64748B (Muted)  │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 Tactile Buttons, Scroll Reveals & Sensory Micro-Interactions
- **Tactile Button Press Physics:** Primary and secondary buttons implement physical spring damping (`active:scale-[0.97] transition-all duration-150 ease-out`).
- **Sheen Sweep & Glow Effect:** Primary CTA buttons feature a continuous subtle sheen sweep gradient on hover and an ambient radial glow.
- **Seat Spring Animation:** Selecting/deselecting seats on the interactive map triggers an elastic spring keyframe bounce (`scale-125` -> `scale-100`) and glowing focus halo.
- **15-Min Hold Pulse Ring:** Hold countdown timer uses dynamic circular progress indicators that pulse vigorously in amber and rose when remaining time drops below 120 seconds.
- **Scroll-Driven Reveal Animations:** Event cards, calendar views, and sections use smooth scroll-reveal fade-ins (`animate-fade-in-up`).
- **Skeleton Shimmer Loaders:** Fluid animated gradient shimmer waves for all loading states.

---

## 3. Feature Domains & 14 Complete Application Pages

```text
/                                    --> EventListComponent (Featured hero carousel, calendar, category pills, date picker, search)
/events/:id                         --> EventDetailComponent (Hero banner, details, pricing tiers, Leaflet interactive map, CTA)
/events/:id/seats                   --> SeatSelectionComponent (Interactive SVG seat map, live WS updates, hold dock)
/checkout/:reservationId            --> CheckoutComponent (15-min countdown, guest email/name form, Stripe Elements, tax breakdown)
/order-confirmation/:paymentId      --> OrderConfirmationComponent (Celebration confetti, order recap, digital pass cards, PDF download)
/tickets/guest/:ticketCode          --> GuestTicketComponent (Multi-ticket switcher "Bilet 1 din N", QR viewer, PDF download, link account)
/profile/tickets                    --> MyTicketsComponent (Active & past digital tickets with Apple Wallet-style QR pass modal)
/profile/settings                   --> UserSettingsComponent (JWT name/email, phone update, theme selector, notifications display)
/auth/callback                      --> AuthCallbackComponent (OIDC redirect handler)
/auth/login                         --> LoginComponent (Entra ID redirect / Sign-in landing)
/auth/logout                        --> LogoutComponent (Session clearance and redirect)
/scanner                            --> StaffScannerComponent (Live camera QR reader with 3-color status feedback: Green/Yellow/Red, ADR-005)
/admin/events                       --> AdminEventListComponent (Event CRUD table, publication lifecycle, filters, quick actions)
/admin/events/new | :id/edit        --> AdminEventEditorComponent (Event form, 16:9 live banner preview & preset gallery, pricing tier manager)
/admin/venues | :id/designer        --> AdminVenueDesignerComponent (2D seat grid generator, section builder, seat toggle, Nominatim address map)
/admin/users                        --> AdminUserListComponent (User audit table, registered users, role assignments)
```

---

## 4. Detailed Specification of Core Frontend Features

### 4.1 Free Interactive Map Integration (Leaflet.js + OpenStreetMap & CartoDB)
- **Library:** Leaflet.js (`leaflet`, `@types/leaflet`) without expensive Google Maps API keys.
- **Theme-Adaptive Tiles:**
  - *Dark Mode:* `CartoDB Dark Matter` (`https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png`).
  - *Light Mode:* `CartoDB Positron` (`https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png`).
- **Event Details Page (`/events/:id`):** Renders venue pin with animated pulsing ripple, popup with venue name, address, and direct external navigation links (*Open in Google Maps / Apple Maps / Waze*).
- **Admin Venue Editor (`/admin/venues/new`):** Uses **Nominatim OpenStreetMap Geocoding** for real-time address autocomplete and draggable pin placement.

### 4.2 Banner Management with Live Preview & Preset High-Res Gallery
- **Live Preview Window:** Aspect ratio 16:9 / 21:9 preview with subtle rounded glass borders and ambient blurred backdrop.
- **Preset Banner Gallery:** 1-click selection from curated high-resolution categories (Concert, Symphony/Theatre, Electronic/Club, Sports Arena, Festival, Comedy/Stand-up).
- **Custom Image URL:** Direct URL input supporting any HTTPS image source (Unsplash, Cloudinary, AWS S3, GCP Cloud Storage, CDN).

### 4.3 Multi-Ticket Support for Guest Checkout (`/tickets/guest/:ticketCode`)
- When a guest completes a purchase of multiple seats in a single reservation:
  - **Multi-Ticket Switcher Bar:** Displays tabbed pagination (*"Biletul 1: Rând A, Loc 12"*, *"Biletul 2: Rând A, Loc 13"*).
  - **Individual QR & Details:** Shows high-density QR code for the currently selected ticket with attendee name and seat location.
  - **Download Actions:** Buttons for *"Download Ticket PDF"* and *"Download All Tickets (PDF Bundle)"*.
  - **Account Linking Prompt:** Prominent aesthetic banner inviting the guest to sign up/sign in with the same email to automatically organize all their tickets in their personal wallet.

### 4.4 Staff Gate Scanner with 3-Color Feedback System (ADR-005)
- **Camera Integration:** Continuous camera stream using HTML5-QRCode / ZXing with viewfinder overlay.
- **3-Color Visual & Acoustic Feedback Matrix:**
  1. 🟢 **SUCCESS (Emerald `#10B981`):** *Entry Granted*. Pleasant confirmation chime + short haptic vibration. Displays attendee name, section, row, seat.
  2. 🟡 **ALREADY_USED (Amber `#F59E0B`):** *Ticket Already Scanned*. Double warning beep + pulsing amber border. Displays timestamp of initial scan and gate device ID.
  3. 🔴 **INVALID / CANCELLED (Rose `#F43F5E`):** *Invalid / Revoked Ticket*. Low error buzz + alert vibration. Displays rejection reason.
- **Manual Input Fallback:** Alphanumeric ticket code entry field for damaged or unreadable screens.

### 4.5 Interactive SVG Seat Map Component (`SeatMapComponent`)
- **Layout Engine:** Scalable SVG / CSS Grid seat matrix supporting pan, pinch-to-zoom, and section isolation on both desktop and mobile touch.
- **Seat Status Visual Encoding:**
  - `AVAILABLE` — Subtle slate pill with price tag on hover.
  - `SELECTING` (Local user) — Electric Indigo with checkmark, spring bounce keyframe animation.
  - `HELD` (Other users) — Muted Amber with lock badge.
  - `SOLD / RESERVED` — Slate-300 / dark grayed-out with cross (disabled).
- **Selection Engine:**
  - Strictly enforces client-side maximum limit of **10 seats**.
  - Recalculates total price instantly via `computed()`.
  - Connects to `WebSocketService` on initialization for live updates on `/topic/events/{eventId}/seats`.

### 4.6 Real-Time STOMP WebSocket Integration & Reconnection Reconciliation
- Subscribes to `/topic/events/{eventId}/seats`.
- When a `SeatStatusUpdated` message is received:
  1. Updates the reactive `seats` Signal store.
  2. If a seat currently selected by the local user is held/booked by another user, deselects it and displays a warning notification via `MatSnackBar`.
- **Reconnection & State Reconciliation:** On reconnect (`onConnect`), automatically re-fetches authoritative seat availability from `GET /api/reservations/events/{eventId}/availability` to guarantee zero state drift.

### 4.7 15-Minute Hold Countdown Timer (`HoldCountdownComponent`)
- Displays remaining hold time formatted as `MM:SS` with a dynamic circular progress ring.
- Visual alert state (pulsing rose text and border) when remaining time falls below **120 seconds**.
- Emits `(expired)` event at `00:00`, triggering a modal and redirecting back to the event catalog.

### 4.8 Stripe Test Mode Hybrid Checkout (`CheckoutComponent`)
- Embeds Stripe Elements (`PaymentElement` + `AddressElement`).
- **Tax-Inclusive Display (ADR-004):** Displays total gross price alongside real-time Stripe Tax breakdown.
- **Hybrid Checkout (ADR-001):** Auto-fills for authenticated users, renders email/name fields for guest checkout.
- Manages 3D Secure simulation and redirects to `/order-confirmation/:paymentId`.

### 4.9 Interactive Monthly Event Calendar (`EventCalendarComponent`)
- Rendered on `/` and `/events` directly below the featured hero carousel.
- **Dual Responsive Layout:**
  - *Desktop ($\ge 768\text{px}$):* Full 7-column month grid (LUN, MAR, MIE, J, VIN, S, D) displaying scheduled event chips per day.
  - *Mobile ($< 768\text{px}$):* Compact monthly view with blue event indicator dots under dates that have events.
- **Month Browsing & Day Selection:** `<` and `>` controls for navigating months; clicking a day applies a reactive date filter to the event catalog.

### 4.10 Rich Multi-Column Footer (`FooterComponent`) & Scroll Reveal Animations
- **4 Structured Columns:**
  1. *Brand & Mission:* Logo, tagline, live status badge (`🟢 All Systems Operational`).
  2. *Explore Events:* Quick category links (Concerts, Theatre, Sports, Festivals).
  3. *Support & Tools:* My Tickets, Guest Ticket Lookup, Staff Scanner, FAQ.
  4. *Legal & Compliance:* Terms & Conditions, Privacy Policy (GDPR), Stripe Tax/VAT Policy, Refund Rules.
- **Scroll-Driven Animation:** Smooth entrance animations (`animate-fade-in-up`) for cards, calendar, and sections as the user scrolls.

---

## 5. Directory Layout

```text
frontend/
├── package.json
├── tsconfig.json
├── angular.json
├── .env.example                       # API Gateway URL, Entra client ID
├── .env                               # Local overrides (.gitignored)
└── src/
    ├── app/
    │   ├── core/                      # Singletons, auth interceptor, error handling, theme engine
    │   │   ├── auth/                  # AuthService, OIDC/Entra ID integration, token storage
    │   │   ├── interceptors/          # auth.interceptor.ts, error.interceptor.ts, logging.interceptor.ts
    │   │   ├── guards/                # auth.guard.ts, admin.guard.ts, staff.guard.ts
    │   │   └── theme/                 # theme.service.ts (Dark/Light/System Signal engine)
    │   ├── shared/                    # Reusable sensory UI widgets, pipes, directives
    │   │   ├── components/            # tactile-button, glass-card, countdown-timer, qr-modal, map-view
    │   │   ├── pipes/                 # currency-format.pipe.ts, date-format.pipe.ts
    │   │   └── directives/            # ripple.directive.ts, spring-press.directive.ts
    │   ├── features/                  # Lazy-loaded domain routes
    │   │   ├── auth/                  # Login, callback, logout
    │   │   ├── events/                # Catalog, hero carousel, event calendar, event details with Leaflet map
    │   │   ├── booking/               # SVG seat map, hold dock, countdown timer, Stripe checkout
    │   │   ├── tickets/               # Multi-ticket guest viewer, my tickets, digital wallet pass
    │   │   ├── scanner/               # Gate camera QR scanner with 3-color verification
    │   │   ├── profile/               # User profile, phone update, theme & preferences
    │   │   └── admin/                 # Events manager, venue 2D designer, user audit
    │   ├── models/                    # TypeScript interfaces matching backend DTOs
    │   ├── services/                  # Cross-cutting API & WebSocket services
    │   ├── app.config.ts              # App providers (Router, HttpClient, Animations, STOMP)
    │   ├── app.routes.ts              # Lazy routing table with View Transitions
    │   └── app.component.ts           # Root shell component
    ├── assets/                        # Audio chimes (ding/beep/buzz), static images, logo
    └── styles.scss                    # Tailwind v4 import, custom spring utilities, theme tokens
```
