from pathlib import Path

p = Path("TMessagesProj/src/main/java/org/telegram/ui/Components/MglaBrandedThemeOffer.java")
t = p.read_text(encoding="utf-8")
t2 = t.replace(
    'Theme.getTheme("Burgundy")',
    "Theme.getTheme(Theme.MGLA_THEME_AMOLED)",
).replace(
    'Theme.getTheme("Gray Burgundy")',
    "Theme.getTheme(Theme.MGLA_THEME_DARK)",
)
p.write_text(t2, encoding="utf-8", newline="\n")
print("changed", t != t2)
