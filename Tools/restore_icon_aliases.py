import subprocess
from pathlib import Path

src = subprocess.check_output(
    ["git", "show", "749761d56:TMessagesProj/src/main/AndroidManifest.xml"],
    text=True,
    encoding="utf-8",
)
manifest_path = Path("TMessagesProj/src/main/AndroidManifest.xml")
cur = manifest_path.read_text(encoding="utf-8")

MARKER_START = '        <!-- usesCleartextTraffic for browser supporting opening http:// links -->\n\n'
MARKER_END = '        <activity\n            android:name="org.telegram.ui.LaunchActivity"'

def extract_aliases(text: str) -> str:
    start = text.find(MARKER_START)
    if start < 0:
        raise SystemExit("start marker not found")
    start += len(MARKER_START)
    end = text.find(MARKER_END, start)
    if end < 0:
        raise SystemExit("end marker not found")
    return text[start:end]

old_aliases = extract_aliases(src)
# current: replace everything between start marker and LaunchActivity
start = cur.find(MARKER_START)
if start < 0:
    raise SystemExit("current start marker not found")
start += len(MARKER_START)
end = cur.find(MARKER_END, start)
if end < 0:
    raise SystemExit("current end marker not found")

new = cur[:start] + old_aliases + cur[end:]
manifest_path.write_text(new, encoding="utf-8", newline="\n")
print("ok aliases=", old_aliases.count("<activity-alias"))
print("has Dymka=", "DymkaIcon" in new)
print("has Vintage=", "VintageIcon" in new)
