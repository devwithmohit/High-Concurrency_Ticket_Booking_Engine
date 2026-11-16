#!/usr/bin/env python3
"""
Edge-Case & Active Sweeper Verification Script:
Tests:
  1. Lock a seat (LOCKED for 10 minutes).
  2. Test Lazy Expiration: Fast-forward lock_expires_at to the past, verify public seat map reports AVAILABLE immediately.
  3. Active 30-Second Sweeper: Wait for background @Scheduled job, verify DB row transitions to AVAILABLE and booking to EXPIRED.
  4. 10:01 Boundary Race Condition:
     - User 2 acquires lock on the released seat.
     - User 1's late payment webhook arrives.
     - Verify late payment does NOT overwrite User 2's lock and is marked REFUND_INITIATED.
     - User 2 completes payment successfully.
"""

import subprocess
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

def run_sql(sql):
    cmd = [
        "docker", "exec", "ticket-engine-postgres",
        "psql", "-U", "postgres", "-d", "ticket_engine", "-t", "-c", sql
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    return result.stdout.strip()

def run_edge_case_tests():
    print("=" * 70)
    print(" STARTING SWEEPER & 10:01 BOUNDARY RACE CONDITION TESTS")
    print("=" * 70)

    # 1. Login Admin
    print("\n[Step 1] Authenticating Admin & Users...")
    _, admin_res = make_request("POST", "/api/v1/auth/login", {"username": "admin", "password": "admin123"})
    admin_token = admin_res["token"]

    # Register/Login User 1
    u1_username = f"user1_{int(time.time())}"
    make_request("POST", "/api/v1/auth/register", {"username": u1_username, "password": "Password123!", "role": "ROLE_USER"})
    _, u1_res = make_request("POST", "/api/v1/auth/login", {"username": u1_username, "password": "Password123!"})
    u1_token = u1_res["token"]
    u1_id = u1_res["userId"]

    # Register/Login User 2
    u2_username = f"user2_{int(time.time())}"
    make_request("POST", "/api/v1/auth/register", {"username": u2_username, "password": "Password123!", "role": "ROLE_USER"})
    _, u2_res = make_request("POST", "/api/v1/auth/login", {"username": u2_username, "password": "Password123!"})
    u2_token = u2_res["token"]
    u2_id = u2_res["userId"]

    print(f" -> User 1: {u1_username} (ID: {u1_id})")
    print(f" -> User 2: {u2_username} (ID: {u2_id})")

    # 2. Create Event & get Seat
    print("\n[Step 2] Creating test event...")
    _, event_res = make_request("POST", "/api/v1/events", {
        "title": "Sweeper & Boundary Test Event",
        "venue": "Test Center",
        "eventTime": "2029-06-01T18:00:00Z",
        "seatGenerators": [{"row": "B", "startNumber": 1, "endNumber": 5, "category": "REGULAR", "price": 80.00}]
    }, {"Authorization": f"Bearer {admin_token}"})
    event_id = event_res["id"]

    _, seat_map = make_request("GET", f"/api/v1/events/{event_id}/seats")
    target_seat = seat_map["seats"][0]
    target_seat_id = target_seat["id"]
    print(f" -> Created Event {event_id}, Target Seat: {target_seat['seatNumber']} ({target_seat_id})")

    # 3. User 1 locks Seat B1
    print("\n[Step 3] User 1 locks Seat B1...")
    u1_booking_key = f"key-u1-{uuid.uuid4()}"
    status, u1_lock_res = make_request("POST", "/api/v1/bookings/lock", {
        "eventId": event_id,
        "seatIds": [target_seat_id]
    }, {"Authorization": f"Bearer {u1_token}", "Idempotency-Key": u1_booking_key})
    assert status == 201, f"User 1 lock failed: {u1_lock_res}"
    u1_booking_id = u1_lock_res["bookingId"]
    print(f" -> User 1 Booking ID: {u1_booking_id}, Status: LOCKED")

    # Verify Seat status is LOCKED in Postgres
    seat_status_db = run_sql(f"SELECT status FROM seats WHERE id = '{target_seat_id}';")
    print(f" -> DB Seat status: {seat_status_db}")
    assert seat_status_db == "LOCKED"

    # 4. Mock Expiration: Fast-forward lock_expires_at and expires_at into the past
    print("\n[Step 4] Mocking 10-minute lock expiration in DB (rewinding timestamps by 15 mins)...")
    run_sql(f"UPDATE seats SET lock_expires_at = NOW() - INTERVAL '15 minutes' WHERE id = '{target_seat_id}';")
    run_sql(f"UPDATE bookings SET expires_at = NOW() - INTERVAL '15 minutes' WHERE id = '{u1_booking_id}';")

    # Test Lazy Expiration check in Public Seat Map API
    print("\n[Step 4b] Testing Lazy Expiration Check on Public Seat Map...")
    _, seat_map_lazy = make_request("GET", f"/api/v1/events/{event_id}/seats")
    lazy_seat = next(s for s in seat_map_lazy["seats"] if s["id"] == target_seat_id)
    print(f" -> Live Seat Map status with expired lock: {lazy_seat['status']}")
    assert lazy_seat["status"] == "AVAILABLE", f"Expected AVAILABLE via lazy check, got {lazy_seat['status']}"
    print(" -> Lazy expiration check verified: Seat is presented as AVAILABLE!")

    # 5. Wait for Active 30-Second Sweeper Job
    print("\n[Step 5] Waiting for Active 30-second background Sweeper to process expired locks...")
    max_wait_seconds = 35
    sweeper_verified = False
    start_time = time.time()

    while time.time() - start_time < max_wait_seconds:
        time.sleep(3)
        booking_status = run_sql(f"SELECT status FROM bookings WHERE id = '{u1_booking_id}';")
        seat_status = run_sql(f"SELECT status FROM seats WHERE id = '{target_seat_id}';")
        locked_by = run_sql(f"SELECT locked_by_user_id FROM seats WHERE id = '{target_seat_id}';")

        if booking_status == "EXPIRED" and seat_status == "AVAILABLE":
            sweeper_verified = True
            print(f" -> Sweeper triggered! Booking status: {booking_status}, Seat status: {seat_status}, LockedBy: '{locked_by}'")
            break
        print(f"    ... waiting for sweeper cycle (elapsed: {int(time.time() - start_time)}s)")

    assert sweeper_verified, "Active sweeper did not transition booking to EXPIRED and seat to AVAILABLE within 35 seconds"
    print(" -> Active background sweeper verified successfully!")

    # 6. Test 10:01 Boundary Race Condition
    print("\n[Step 6] Testing 10:01 Boundary Race Condition:")
    print(" -> Step 6a: User 2 immediately locks the now-available Seat B1...")
    u2_booking_key = f"key-u2-{uuid.uuid4()}"
    status, u2_lock_res = make_request("POST", "/api/v1/bookings/lock", {
        "eventId": event_id,
        "seatIds": [target_seat_id]
    }, {"Authorization": f"Bearer {u2_token}", "Idempotency-Key": u2_booking_key})
    assert status == 201, f"User 2 failed to lock seat: {u2_lock_res}"
    u2_booking_id = u2_lock_res["bookingId"]
    print(f" -> User 2 acquired lock! Booking ID: {u2_booking_id}")

    # Verify Seat is now legitimately locked by User 2
    seat_owner = run_sql(f"SELECT locked_by_user_id FROM seats WHERE id = '{target_seat_id}';")
    assert seat_owner == str(u2_id), f"Seat should be locked by User 2 ({u2_id}), got {seat_owner}"
    print(f" -> Seat is actively locked by User 2 ({u2_id})")

    # Step 6b: User 1's late payment webhook arrives at 10:01!
    print("\n -> Step 6b: User 1's payment webhook arrives LATE after seat was re-allocated...")
    status, late_pay_res = make_request("POST", "/api/v1/webhooks/payment", {
        "bookingId": u1_booking_id,
        "paymentReference": f"LATE-PAY-{uuid.uuid4().hex[:8].upper()}",
        "amount": 80.00,
        "status": "SUCCESS"
    }, {"Idempotency-Key": f"pay-u1-late-{uuid.uuid4()}"})

    print(f" -> Late Payment Response: HTTP {status} -> Status: {late_pay_res.get('status')}")
    print(f"    Message: {late_pay_res.get('message')}")
    assert status == 200, f"Expected HTTP 200 with REFUND_INITIATED, got HTTP {status}"
    assert late_pay_res["status"] == "REFUND_INITIATED", f"Expected REFUND_INITIATED, got {late_pay_res['status']}"

    # Verify that User 2's lock was NOT overwritten!
    current_seat_owner = run_sql(f"SELECT locked_by_user_id FROM seats WHERE id = '{target_seat_id}';")
    current_seat_status = run_sql(f"SELECT status FROM seats WHERE id = '{target_seat_id}';")
    print(f" -> Current Seat Owner in DB: {current_seat_owner}")
    print(f" -> Current Seat Status in DB: {current_seat_status}")
    assert current_seat_owner == str(u2_id), "CRITICAL VIOLATION: User 2's lock was overwritten by User 1's late payment!"
    assert current_seat_status == "LOCKED", "Seat status was corrupted by late payment!"
    print(" -> Invariant Verified: User 2's lock was PROTECTED. Zero lock overwrite.")

    # Step 6c: User 2 confirms their legitimate booking
    print("\n -> Step 6c: User 2 completes payment for their active reservation...")
    status, u2_pay_res = make_request("POST", "/api/v1/webhooks/payment", {
        "bookingId": u2_booking_id,
        "paymentReference": f"VALID-PAY-{uuid.uuid4().hex[:8].upper()}",
        "amount": 80.00,
        "status": "SUCCESS"
    }, {"Idempotency-Key": f"pay-u2-valid-{uuid.uuid4()}"})
    assert status == 200, f"User 2 payment failed: {u2_pay_res}"
    assert u2_pay_res["status"] == "CONFIRMED", f"Expected CONFIRMED, got {u2_pay_res['status']}"

    final_seat_status = run_sql(f"SELECT status FROM seats WHERE id = '{target_seat_id}';")
    print(f" -> Final Seat Status in DB: {final_seat_status}")
    assert final_seat_status == "BOOKED"
    print(" -> User 2's booking is CONFIRMED and seat is permanently BOOKED!")

    print("\n" + "=" * 70)
    print(" ALL EDGE-CASE AND BOUNDARY RACE CONDITION TESTS PASSED!")
    print("=" * 70)

if __name__ == "__main__":
    run_edge_case_tests()
