package com.eppletv.launcher.pair

/** The page the phone opens from the QR code. Self-contained: no external fonts, scripts or images. */
object PhonePage {
    // M1 placeholder: pairing and saving are exercised directly against the API; the full page arrives in M2.
    fun html(nonce: String): String = """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="referrer" content="no-referrer">
<title>Epple TV setup</title>
<style nonce="$nonce">body{font:17px system-ui,-apple-system,sans-serif;margin:0;padding:32px 22px;background:#0b0c10;color:#f2f2f2}
h1{font-size:26px;margin:0 0 12px}p{color:#b8bcc6;line-height:1.5}</style></head>
<body><h1>Epple TV</h1><p>Phone setup is being built. Use the TV remote for now.</p></body></html>"""
}
