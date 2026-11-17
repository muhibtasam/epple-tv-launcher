# Changelog

All notable changes to Epple TV are listed here. Versions follow [Semantic Versioning](https://semver.org/).

## [1.0.2] - 2026-11-18

A visual update: app tiles in a premium dark style, plus three Home fixes. Install it over 1.0.1; your settings and
data are kept.

### New
- **Dark app tiles.** On Home, in Apps and in Search, each app's logo sits on a charcoal tile in the style of dark-mode
  icons, with a light focus ring. Apps whose artwork can't be adapted cleanly keep their own. New and updated apps get
  their tile automatically.
- **Settings > App icons.** Choose Dark or Light (each app's original artwork). Switching is instant.

### Bug fixes
- **Home rows:** returning to the rows under the top row starts again at the first row and title.
- **Hold OK:** holding OK on a title opens its menu once, without also choosing the menu's first option.
- **Top row:** the Play button stays visible while moving between titles.

### Update and compatibility notes
- Signed with the same Epple TV release identity as 1.0.0 and 1.0.1, so it installs over them without uninstalling.
- The first start after updating prepares every app tile once in the background (a few seconds).
- Tested on Android 14 (Google TV) on a 16:9 display. Device compatibility may vary depending on Android TV version and
  manufacturer.

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
