# 06 — REST API Contracts & Endpoint Catalog

This document defines the complete catalog of REST API endpoints exposed across SeatFlow microservices and routed through the API Gateway (`http://localhost:8080`).

---

## 1. REST API Conventions

- **Paths:** Kebab-case plural nouns (e.g. `/api/reservations`, `/api/events/{eventId}/pricing-tiers`).
- **HTTP Status Codes:**
  - `200 OK` — Successful query or idempotent update.
  - `201 Created` — Resource successfully created (`Location` header included where appropriate).
  - `204 No Content` — Successful operation with no return payload (e.g. cancellation/delete).
  - `400 Bad Request` — Validation failure, invalid UUID format, or constraint violation.
  - `401 Unauthorized` — Missing, expired, or invalid JWT Bearer token.
  - `403 Forbidden` — Authenticated principal lacks required role (`ROLE_ADMIN`).
  - `404 Not Found` — Resource with requested UUID does not exist.
  - `409 Conflict` — State conflict (seat already held/sold, optimistic lock error).
- **Error Response Envelope:** All non-2xx responses strictly follow `ApiErrorResponse`.

---

## 2. API Catalog by Service

### 2.1 User Service (`/api/users`)

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/api/users/me` | Customer / Admin | Get current authenticated user profile |
| `PUT` | `/api/users/me` | Customer / Admin | Update current user profile details |
| `GET` | `/api/admin/users` | Admin only | List registered users with pagination |

#### `PUT /api/users/me`
**Request Body:**
```json
{
  "phone": "+1-555-0199"
}
```
**Response Body (200 OK):**
```json
{
  "id": "123e4567-e89b-12d3-a456-426614174000",
  "email": "alex.smith@example.com",
  "phone": "+1-555-0199",
  "createdAt": "2026-08-23T10:00:00Z"
}
```

---

### 2.2 Seat Map Service (`/api/venues`)

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/api/venues` | Public | List all venues |
| `GET` | `/api/venues/{venueId}` | Public | Get venue details |
| `GET` | `/api/venues/{venueId}/layout` | Public | Get complete venue seat grid and sections |
| `POST` | `/api/admin/venues` | Admin only | Create new venue |
| `PUT` | `/api/admin/venues/{venueId}` | Admin only | Update venue details |
| `POST` | `/api/admin/venues/{venueId}/sections` | Admin only | Configure venue section and seats |
| `PATCH` | `/api/admin/venues/{venueId}/sections/{sectionId}/seats/{seatId}` | Admin only | Toggle seat active/inactive status |

#### `GET /api/venues/{venueId}/layout`
**Response Body (200 OK):**
```json
{
  "venueId": "923e4567-e89b-12d3-a456-426614174000",
  "name": "Grand Theatre",
  "capacity": 500,
  "sections": [
    {
      "sectionId": "823e4567-e89b-12d3-a456-426614174000",
      "name": "Orchestra",
      "rowCount": 10,
      "colCount": 20,
      "seats": [
        {
          "seatId": "723e4567-e89b-12d3-a456-426614174000",
          "rowLabel": "A",
          "seatNumber": 1,
          "gridX": 0,
          "gridY": 0,
          "isActive": true
        }
      ]
    }
  ]
}
```

---

### 2.3 Event Service (`/api/events`)

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/api/events` | Public | Search & list upcoming published events (`eventDate > now()`) with filters |
| `GET` | `/api/events/{eventId}` | Public | Get single event details (404 if not published or `eventDate <= now()`, ADR-003) |
| `GET` | `/api/events/{eventId}/seat-map` | Public | Get event seat map merged with section pricing (404 if `eventDate <= now()`, ADR-003) |
| `POST` | `/api/admin/events` | Admin only | Create new draft event |
| `PUT` | `/api/admin/events/{eventId}` | Admin only | Update event details & state transitions |
| `POST` | `/api/admin/events/{eventId}/pricing` | Admin only | Configure section pricing tiers |

#### `GET /api/events` (Query Parameters: `category`, `search`, `page`, `size`, `sort`)
**Response Body (200 OK):**
```json
{
  "content": [
    {
      "id": "223e4567-e89b-12d3-a456-426614174000",
      "title": "Hamlet — Royal Shakespeare Co.",
      "description": "Acclaimed production of Shakespeare's masterpiece.",
      "category": "THEATRE",
      "bannerUrl": "https://cdn.seatflow.com/events/hamlet.jpg",
      "eventDate": "2026-09-15T19:30:00Z",
      "venueName": "Grand Theatre",
      "status": "PUBLISHED",
      "minPrice": 35.00,
      "maxPrice": 150.00
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "isFirst": true,
  "isLast": true
}
```

---

### 2.4 Reservation Service (`/api/reservations`)

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/reservations` | Public / Customer | Create 15-min seat hold (Max 10 seats, Guest or Authenticated) |
| `GET` | `/api/reservations/{reservationId}` | Public / Customer / Admin | Get reservation details & hold countdown |
| `POST` | `/api/reservations/{reservationId}/cancel` | Public / Customer | Cancel active hold |
| `GET` | `/api/reservations/events/{eventId}/availability` | Public | Get current seat availability status list |

#### `POST /api/reservations`
**Request Body:**
```json
{
  "eventId": "223e4567-e89b-12d3-a456-426614174000",
  "customerEmail": "customer@seatflow.com",
  "customerName": "Alex Smith",
  "seatIds": [
    "723e4567-e89b-12d3-a456-426614174000",
    "823e4567-e89b-12d3-a456-426614174000"
  ],
  "idempotencyKey": "hold-req-user123-uuid-001"
}
```
*Note: `customerEmail` is optional if an authenticated JWT Bearer token is provided, in which case it is extracted from token claims. For unauthenticated guests, `customerEmail` is required.*

**Response Body (201 Created):**
```json
{
  "id": "123e4567-e89b-12d3-a456-426614174000",
  "eventId": "223e4567-e89b-12d3-a456-426614174000",
  "customerEmail": "customer@seatflow.com",
  "customerName": "Alex Smith",
  "status": "PENDING",
  "expiresAt": "2026-08-23T14:45:00Z",
  "totalAmount": 120.00,
  "seats": [
    {
      "seatId": "723e4567-e89b-12d3-a456-426614174000",
      "rowNumber": "A",
      "seatNumber": 1,
      "price": 60.00
    },
    {
      "seatId": "823e4567-e89b-12d3-a456-426614174000",
      "rowNumber": "A",
      "seatNumber": 2,
      "price": 60.00
    }
  ]
}
```

---

### 2.5 Payment Service (`/api/payments`)

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/payments/intent` | Public / Customer | Create Stripe PaymentIntent for a reservation |
| `GET` | `/api/payments/{paymentId}` | Public / Customer | Get payment status |
| `POST` | `/api/payments/webhook` | Public (Stripe Sig) | Handle Stripe async webhook events |

#### `POST /api/payments/intent`
**Request Body:**
```json
{
  "reservationId": "123e4567-e89b-12d3-a456-426614174000",
  "idempotencyKey": "pay-req-user123-uuid-001"
}
```
**Response Body (201 Created):**
```json
{
  "paymentId": "523e4567-e89b-12d3-a456-426614174000",
  "clientSecret": "pi_3Nsk2e2eZvKYlo2C1gQ_secret_xxx",
  "amount": 120.00,
  "currency": "USD",
  "status": "INITIATED"
}
```

---

### 2.6 Ticket Service (`/api/tickets`)

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/api/tickets/my-tickets` | Customer | List purchased tickets for authenticated user |
| `GET` | `/api/tickets/guest/{ticketCode}` | Public | Get ticket detail by secure ticket code (guest delivery) |
| `GET` | `/api/tickets/{ticketId}` | Customer | Get ticket detail with QR code data (authenticated user) |
| `GET` | `/api/tickets/{ticketId}/pdf` | Public / Customer | Download rendered PDF ticket |
| `POST` | `/api/admin/tickets/validate` | Admin / Scanner | Validate ticket QR code at venue entrance |

#### `POST /api/admin/tickets/validate`
**Request Body:**
```json
{
  "ticketCode": "SF-TKT-9876-ABCD"
}
```
**Response Body (200 OK):**
```json
{
  "valid": true,
  "ticketId": "823e4567-e89b-12d3-a456-426614174000",
  "eventTitle": "Hamlet — Royal Shakespeare Co.",
  "eventDate": "2026-09-15T19:30:00Z",
  "attendeeName": "Alex Smith",
  "section": "Orchestra",
  "rowNumber": "A",
  "seatNumber": 1,
  "scannedAt": "2026-09-15T18:45:10Z"
}
```

---

### 2.7 Realtime WebSocket (`/ws`)
- **Protocol:** STOMP over SockJS fallback.
- **Connection URL:** `ws://localhost:8080/ws` (via Gateway) or `http://localhost:8080/ws` (SockJS).
- **Authentication:** `Authorization: Bearer <token>` in STOMP connect headers.
- **Subscribe Topic:** `/topic/events/{eventId}/seats` (receives `SeatStatusUpdated` JSON payloads).
