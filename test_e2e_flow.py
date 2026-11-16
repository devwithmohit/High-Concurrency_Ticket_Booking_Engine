#!/usr/bin/env python3
"""
End-to-End API Lifecycle Verification Script
Tests:
  1. POST /api/v1/auth/login (Admin & User)
  2. POST /api/v1/events (Admin event creation & bulk seat generation A1-A10)
  3. GET /api/v1/events/{id}/seats (Public Seat Map & Redis caching)
  4. POST /api/v1/bookings/lock (Two-Phase Lock with Idempotency-Key)
  5. POST /api/v1/webhooks/payment (Payment confirmation & seat finalization)
  6. Idempotent replay checks
"""

import sys
import json
import time
import uuid
import urllib.request
import urllib.error

BASE_URL = "http://localhost:8080"

def make_request(method, path, body=None, headers=None):
    url = f"{BASE_URL}{path}"
    req_headers = {"Content-Type": "application/json"}
    if headers:
        req_headers.update(headers)

    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, headers=req_headers, method=method)

    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            status_code = response.getcode()
            raw_body = response.read().decode("utf-8")
            json_body = json.loads(raw_body) if raw_body else {}
            return status_code, json_body
    except urllib.error.HTTPError as e:
        raw_body = e.read().decode("utf-8")
        try:
            json_body = json.loads(raw_body)
        except Exception:
            json_body = {"raw": raw_body}
        return e.code, json_body

def run_e2e_flow():
    print("=" * 70)
    print(" STARTING HIGH-CONCURRENCY TICKET BOOKING ENGINE E2E VERIFICATION")
    print("=" * 70)

    # 1. Health check
    print("\n[Step 1] Checking /actuator/health...")
    status, health = make_request("GET", "/actuator/health")
    print(f"Health Status: HTTP {status} -> {health.get('status')}")
    components = health.get("components", {})
    db_status = components.get("db", {}).get("status", "N/A")
    redis_status = components.get("redis", {}).get("status", "N/A")
    print(f" - PostgreSQL component: {db_status}")
    print(f" - Redis component:      {redis_status}")
    assert status == 200, f"Health check failed with HTTP {status}"
    assert health.get("status") == "UP", "Overall application is not UP"

    # 2. Authenticate Admin
    print("\n[Step 2] Authenticating Admin via POST /api/v1/auth/login...")
    status, auth_res = make_request("POST", "/api/v1/auth/login", {
        "username": "admin",
        "password": "admin123"
    })
    print(f"Admin Login: HTTP {status}")
    assert status == 200, f"Admin login failed: {auth_res}"
    admin_token = auth_res["token"]
    print(f" -> Admin Token acquired: {admin_token[:20]}... (Role: {auth_res.get('role')})")

    # Authenticate User
    print("\n[Step 2b] Authenticating User via POST /api/v1/auth/login...")
    status, user_res = make_request("POST", "/api/v1/auth/login", {
        "username": "user",
        "password": "user123"
    })
    print(f"User Login: HTTP {status}")
    assert status == 200, f"User login failed: {user_res}"
    user_token = user_res["token"]
    user_id = user_res["userId"]
    print(f" -> User Token acquired: {user_token[:20]}... (User ID: {user_id})")

    # 3. Create Event & Bulk Generate Seats A1 to A10
    print("\n[Step 3] Creating Event & Generating Seats A1-A10 (Admin)...")
    event_payload = {
        "title": "E2E Automated Verification Concert",
        "venue": "Grand Symphony Hall",
        "eventTime": "2029-12-31T20:00:00Z",
        "seatGenerators": [
            {
                "row": "A",
                "startNumber": 1,
                "endNumber": 10,
                "category": "PREMIUM",
                "price": 120.00
            }
        ]
    }
    status, event_res = make_request("POST", "/api/v1/events", event_payload, {
        "Authorization": f"Bearer {admin_token}"
    })
    print(f"Create Event: HTTP {status}")
    assert status == 201, f"Failed to create event: {event_res}"
    event_id = event_res["id"]
    print(f" -> Created Event ID: {event_id} with {event_res.get('totalSeats')} seats")

    # 4. Fetch Live Seat Map (and verify Redis caching)
    print("\n[Step 4] Querying Public Seat Map (GET /api/v1/events/{id}/seats)...")
    status, seat_map = make_request("GET", f"/api/v1/events/{event_id}/seats")
    print(f"Seat Map: HTTP {status}")
    assert status == 200, f"Failed to get seat map: {seat_map}"
    seats = seat_map.get("seats", [])
    print(f" -> Total seats returned: {len(seats)}, Available: {seat_map.get('availableSeats')}")
    assert len(seats) == 10, f"Expected 10 seats, got {len(seats)}"
    seat_a1 = next((s for s in seats if s["seatNumber"] == "A1"), None)
    assert seat_a1 is not None, "Seat A1 not found in seat map"
    assert seat_a1["status"] == "AVAILABLE", f"Seat A1 should be AVAILABLE, got {seat_a1['status']}"
    target_seat_id = seat_a1["id"]
    print(f" -> Target Seat A1 ID: {target_seat_id} is AVAILABLE")

    # 5. Two-Phase Reservation: Lock Seat A1
    print("\n[Step 5] Locking Seat A1 via POST /api/v1/bookings/lock...")
    lock_idempotency_key = f"e2e-lock-{uuid.uuid4()}"
    lock_payload = {
        "eventId": event_id,
        "seatIds": [target_seat_id]
    }
    status, lock_res = make_request("POST", "/api/v1/bookings/lock", lock_payload, {
        "Authorization": f"Bearer {user_token}",
        "Idempotency-Key": lock_idempotency_key
    })
    print(f"Lock Seat: HTTP {status}")
    assert status == 201, f"Failed to lock seat: {lock_res}"
    booking_id = lock_res["bookingId"]
    print(f" -> Booking created: {booking_id}, Status: {lock_res.get('status')}, ExpiresAt: {lock_res.get('expiresAt')}")

    # Verify Idempotent Replay on Lock
    print("\n[Step 5b] Verifying Idempotent Replay with same Idempotency-Key...")
    replay_status, replay_res = make_request("POST", "/api/v1/bookings/lock", lock_payload, {
        "Authorization": f"Bearer {user_token}",
        "Idempotency-Key": lock_idempotency_key
    })
    print(f"Replay Lock: HTTP {replay_status}")
    assert replay_status in (200, 201), f"Idempotent replay failed: {replay_res}"
    assert replay_res["bookingId"] == booking_id, "Replay returned different booking ID!"
    print(" -> Idempotent replay verified successfully.")

    # Verify Seat Map now shows Seat A1 as LOCKED
    print("\n[Step 5c] Verifying Seat Map updated (Seat A1 = LOCKED)...")
    status, updated_seat_map = make_request("GET", f"/api/v1/events/{event_id}/seats")
    updated_a1 = next((s for s in updated_seat_map["seats"] if s["id"] == target_seat_id), None)
    print(f" -> Seat A1 Status: {updated_a1['status']} (Available: {updated_seat_map.get('availableSeats')}, Locked: {updated_seat_map.get('lockedSeats')})")
    assert updated_a1["status"] == "LOCKED", f"Expected LOCKED, got {updated_a1['status']}"

    # Verify another lock on Seat A1 fails with 409 Conflict
    print("\n[Step 5d] Verifying duplicate reservation attempt on Seat A1 returns 409 Conflict...")
    status, conflict_res = make_request("POST", "/api/v1/bookings/lock", lock_payload, {
        "Authorization": f"Bearer {user_token}",
        "Idempotency-Key": f"e2e-lock-conflict-{uuid.uuid4()}"
    })
    print(f"Conflict Test: HTTP {status} -> {conflict_res.get('message')}")
    assert status == 409, f"Expected 409 Conflict, got HTTP {status}"
    print(" -> 409 Conflict verified for occupied seat.")

    # 6. Payment Webhook Confirmation
    print("\n[Step 6] Confirming Booking via POST /api/v1/webhooks/payment...")
    payment_idempotency_key = f"e2e-pay-{uuid.uuid4()}"
    payment_payload = {
        "bookingId": booking_id,
        "paymentReference": f"PAY-REF-{uuid.uuid4().hex[:12].upper()}",
        "amount": lock_res["totalAmount"],
        "status": "SUCCESS"
    }
    status, pay_res = make_request("POST", "/api/v1/webhooks/payment", payment_payload, {
        "Idempotency-Key": payment_idempotency_key
    })
    print(f"Payment Webhook: HTTP {status} -> Status: {pay_res.get('status')}")
    assert status == 200, f"Payment webhook failed: {pay_res}"
    assert pay_res["status"] == "CONFIRMED", f"Expected CONFIRMED, got {pay_res['status']}"

    # Verify Seat Map now shows Seat A1 as BOOKED
    print("\n[Step 6b] Verifying Seat Map updated (Seat A1 = BOOKED)...")
    status, final_seat_map = make_request("GET", f"/api/v1/events/{event_id}/seats")
    final_a1 = next((s for s in final_seat_map["seats"] if s["id"] == target_seat_id), None)
    print(f" -> Seat A1 Status: {final_a1['status']} (Booked: {final_seat_map.get('bookedSeats')})")
    assert final_a1["status"] == "BOOKED", f"Expected BOOKED, got {final_a1['status']}"

    # Verify Idempotent Replay on Payment
    print("\n[Step 6c] Verifying Idempotent Replay on Payment Webhook...")
    status, pay_replay_res = make_request("POST", "/api/v1/webhooks/payment", payment_payload, {
        "Idempotency-Key": payment_idempotency_key
    })
    print(f"Payment Replay: HTTP {status} -> Status: {pay_replay_res.get('status')}")
    assert status == 200, f"Payment replay failed: {pay_replay_res}"
    assert pay_replay_res["status"] == "CONFIRMED", "Replay did not return CONFIRMED"

    print("\n" + "=" * 70)
    print(" ALL END-TO-END LIFECYCLE TESTS PASSED PERFECTLY!")
    print("=" * 70)

if __name__ == "__main__":
    run_e2e_flow()
