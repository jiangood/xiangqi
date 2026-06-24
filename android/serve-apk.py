import http.server
import socket
import subprocess
import sys
import os
import webbrowser
from pathlib import Path
from urllib.parse import quote

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8080

apk_dir = Path(__file__).parent / "app" / "build" / "outputs" / "apk" / "debug"
apks = list(apk_dir.glob("*.apk"))
if not apks:
    print("No APK found. Run assembleDebug first.")
    sys.exit(1)
apk_file = max(apks, key=lambda f: f.stat().st_mtime)
apk_name = apk_file.name

ip = "localhost"
try:
    out = subprocess.check_output("ipconfig", shell=True, text=True)
    import re
    for m in re.finditer(r"IPv4[^:]*:\s*(\d+\.\d+\.\d+\.\d+)", out):
        addr = m.group(1)
        if not addr.startswith(("172.", "127.", "169.")):
            ip = addr
            break
except Exception:
    pass

url = f"http://{ip}:{PORT}/{apk_name}"
qr_url = f"https://api.qrserver.com/v1/create-qr-code/?size=300x300&data={quote(url)}"

print(f"Serving: {apk_file}")
print(f"URL:     {url}")
print()
print("Press Ctrl+C to stop.")
print()

os.chdir(apk_dir)
webbrowser.open(qr_url)

server = http.server.HTTPServer(("0.0.0.0", PORT), http.server.SimpleHTTPRequestHandler)
server.serve_forever()
