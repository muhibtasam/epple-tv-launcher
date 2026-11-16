# Changelog

All notable changes to Epple TV are listed here. Versions follow [Semantic Versioning](https://semver.org/).

## [1.0.1] - 2026-11-16

A maintenance release: smoother, lighter and more reliable. Install it over 1.0.0; your settings and data are kept.

### Improvements
- **Smoother, lighter Home screen.** Epple TV redraws far less while the Home screen is idle.
- **Calmer wallpaper motion.** The slow wallpaper zoom runs at a gentler pace, pauses while you use the remote and stops
  after 3 minutes without input, then resumes without a jump.
- **More efficient screensaver.** While the screensaver is showing, the Home wallpaper motion and artwork rotation pause.

### Bug fixes
- **Search:** the hint and the "No results" message now appear only when they should.
- **Notifications:** app icons in Control Center display correctly, and a notification you open is cleared from Epple
  TV and from the app that posted it. Hold OK still dismisses a notification without opening it.
- **Update notification:** it stays until the new version is actually running, and stays if you cancel or the
  installation fails, so you can try again.
- **Storage:** downloaded update files are removed once they are no longer needed.

### Update and compatibility notes
- Signed with the same Epple TV release identity as 1.0.0, so it installs over 1.0.0 without uninstalling.
- Tested on Android 14 (Google TV) on a 16:9 display. Device compatibility may vary depending on Android TV version and
  manufacturer.

## [1.0.0] - 2026-11

First public release.
