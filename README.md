# Epple TV Launcher

Private Android TV launcher for the owner's own TV (TCL C6K). Not for publication or distribution.
Kotlin, platform Views, custom spring motion (decisions: tv-launcher-lab/HANDOFF.md). No Apple assets or marks.

Build: `bash scripts/build.sh` → `out/epple-tv-<flavor>-release.apk`
Flavours: `base` (no Google dependencies), `google` (Google TV capabilities behind interfaces).
Runs as a normal app (opened from the Apps row). It does not register as HOME; the stock launcher is untouched.
