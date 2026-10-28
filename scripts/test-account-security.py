"""Exercise a private Local Edition instance; never connects to the configured dev database."""
import json
import os
from pathlib import Path
import secrets
import re
import socket
import sqlite3
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
from contextlib import closing

ROOT = Path(__file__).resolve().parents[1]


def main():
    with tempfile.TemporaryDirectory(prefix="fabmatrix-account-") as temporary:
        data = Path(temporary)
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        env = os.environ.copy()
        env.update(FARM_DATA_DIR=str(data), SERVER_PORT=str(port), FARM_MONITOR_ENABLED="false",
                   FARM_DISCOVERY_ENABLED="false", JWT_SECRET_KEY=secrets.token_urlsafe(48),
                   ADMIN_SECRET_KEY=secrets.token_urlsafe(48))
        log_dir = ROOT / "target/account-security"
        log_dir.mkdir(parents=True, exist_ok=True)
        log = open(log_dir / "local-smoke.log", "wb")
        settings = subprocess.run(["java", "-XshowSettings:properties", "-version"], capture_output=True, text=True, check=True)
        java_home = re.search(r"(?m)^\s*java.home = (.+)$", settings.stderr).group(1).strip()
        java = str(Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java"))
        process = subprocess.Popen([java, "-jar", str(ROOT / "target/fabmatrix-server-1.0.0.jar"),
                                    "--spring.profiles.active=local", "--server.address=127.0.0.1"],
                                   env=env, stdout=log, stderr=subprocess.STDOUT,
                                   creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))

        def call(method, path, body=None, token=None, expected=200):
            headers = {"Content-Type": "application/json"}
            if token:
                headers["Authorization"] = "Bearer " + token
            request = urllib.request.Request(f"http://127.0.0.1:{port}{path}",
                                             data=None if body is None else json.dumps(body).encode(),
                                             headers=headers, method=method)
            try:
                response = urllib.request.urlopen(request, timeout=10)
            except urllib.error.HTTPError as error:
                response = error
            payload = json.load(response)
            assert response.status == expected, f"{method} {path}: HTTP {response.status}, expected {expected}"
            if path == "/actuator/health":
                assert payload["status"] == "UP"
                return payload
            if expected == 200:
                assert payload["code"] == 200, f"{method} {path}: business failure"
            return payload.get("data")

        def login(username, password):
            return call("POST", "/api/v1/auth/login", {"username": username, "password": password})

        try:
            deadline = time.monotonic() + 90
            while True:
                try:
                    call("GET", "/actuator/health")
                    break
                except (OSError, AssertionError):
                    if process.poll() is not None or time.monotonic() > deadline:
                        raise AssertionError("Private Local instance did not become healthy; see local-smoke.log")
                    time.sleep(0.5)
            bootstrap = call("GET", "/api/v1/auth/setup/bootstrap-token")["token"]
            admin_password = secrets.token_urlsafe(12)
            admin = call("POST", "/api/v1/auth/setup/admin", {"username": "admin", "password": admin_password,
                         "confirmPassword": admin_password, "bootstrapToken": bootstrap})["token"]
            call("POST", "/api/v1/auth/login", {"username": "admin", "password": "InvalidProbe123"}, expected=401)
            call("GET", "/actuator/health")
            call("GET", "/api/v1/auth/me", token=admin)
            temporary_password, employee_password = secrets.token_urlsafe(12), secrets.token_urlsafe(12)
            user_id = call("POST", "/api/v1/auth/admin/users", {"username": "viewer", "role": "VIEWER",
                           "password": temporary_password, "confirmPassword": temporary_password}, admin)
            initial = login("viewer", temporary_password)
            assert initial["mustChangePassword"] is True and "job:operate" not in initial["permissions"]
            temporary_token = initial["token"]
            call("GET", "/api/v1/printers/page", token=temporary_token, expected=403)
            assert call("GET", "/api/v1/auth/me", token=temporary_token)["mustChangePassword"] is True
            call("POST", f"/api/v1/auth/{user_id}/change-password", {"oldPassword": temporary_password,
                         "newPassword": employee_password, "confirmPassword": employee_password}, temporary_token)
            call("GET", "/api/v1/auth/me", token=temporary_token, expected=401)
            first, second = login("viewer", employee_password)["token"], login("viewer", employee_password)["token"]
            call("GET", "/api/v1/printers/page", token=first)
            call("POST", "/api/v1/control/1/pause", {}, first, expected=403)
            call("POST", "/api/v1/print-jobs/create", {}, first, expected=403)
            sessions = call("GET", "/api/v1/auth/sessions", token=first)
            assert len(sessions) == 2
            other = next(row for row in sessions if not row["current"])
            call("POST", f"/api/v1/auth/sessions/{other['id']}/revoke", {}, admin, expected=404)
            websocket_test = subprocess.run(["node", str(ROOT / "scripts/test-account-websocket.mjs")],
                    input=json.dumps({"port": port, "first": first, "second": second, "otherId": other["id"]}),
                    text=True, capture_output=True, timeout=30)
            assert websocket_test.returncode == 0, "Private WebSocket revocation failed"
            print(websocket_test.stdout.strip())
            call("GET", "/api/v1/auth/me", token=second, expected=401)
            call("GET", "/api/v1/auth/me", token=first)
            reset_password = secrets.token_urlsafe(12)
            call("POST", f"/api/v1/auth/admin/users/{user_id}/reset-password",
                 {"password": reset_password, "confirmPassword": reset_password}, admin)
            call("GET", "/api/v1/auth/me", token=first, expected=401)
            reset = login("viewer", reset_password)
            assert reset["mustChangePassword"] is True
            call("GET", "/api/v1/printers/page", token=reset["token"], expected=403)
            logs = call("GET", "/api/v1/auth/admin/audit-logs?action=USER_PASSWORD_RESET", token=admin)
            assert logs["total"] == 1
            with closing(sqlite3.connect(data / "farm.db")) as db:
                password_hash = db.execute("SELECT password_hash FROM farm_user WHERE id=?", (user_id,)).fetchone()[0]
                assert password_hash.startswith("$2") and password_hash != reset_password
                labels = " ".join(str(row) for row in db.execute("SELECT target_label FROM farm_audit_log"))
                assert all(value not in labels for value in (employee_password, reset_password, temporary_password))
            print("PASS: private SQLite bootstrap, forced password change, VIEWER write denial, independent sessions, reset and audit.")
        finally:
            process.terminate()
            try:
                process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
            log.close()


if __name__ == "__main__":
    main()
