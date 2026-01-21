# Blink Bitcoin Terminal - Print Companion App

Android companion app for printing receipts from [Blink Bitcoin Terminal](https://track.twentyone.ist).

## Overview

This app enables thermal receipt printing from Blink Bitcoin Terminal web app. It works with:

1. **Bitcoinize POS** - Built-in thermal printer (via Nyx SDK)
2. **Generic Bluetooth Thermal Printers** - Any ESC/POS compatible printer

The app runs invisibly in the background - no UI switching required!

## Supported Printers

### Built-in Printers
- Bitcoinize POS (all models with thermal printer)

### Bluetooth Thermal Printers
Works with most ESC/POS compatible Bluetooth printers:
- Netum (PT-210, PT-58DC, etc.)
- MUNBYN (IMP001, etc.)
- GOOJPRT (PT-210, etc.)
- POS-5802, POS-5805
- HOIN HOP-E200
- Milestone MHT-P8001
- Epson TM-series (Bluetooth models)
- And many more...

## Installation

### Option 1: Download APK
Download the latest release from the [Releases page](https://github.com/pretyflaco/pos-print-companion/releases).

### Option 2: Build from Source
```bash
# Clone the repository
git clone https://github.com/pretyflaco/pos-print-companion.git
cd pos-print-companion

# Build debug APK
./gradlew assembleDebug

# APK will be at: app/build/outputs/apk/debug/app-debug.apk
```

## Setup

### For Bitcoinize POS
1. Install the companion app
2. That's it! The app auto-connects to the built-in printer.

### For Bluetooth Printers
1. Install the companion app
2. Pair your Bluetooth printer with your Android device (in Settings → Bluetooth)
3. The app will auto-detect paired printers
4. Print from Blink Bitcoin Terminal - it will use the first available printer

## Deep Link Protocol

The app receives print commands via deep links from the web app.

### Protocol V1 (Legacy) - App Renders Receipt

Used when the app creates the receipt layout.

#### Voucher Receipt
```javascript
const deepLinkUrl = `blink-pos-companion://print?` + 
  `app=voucher&` +
  `lnurl=${encodeURIComponent(lnurl)}&` +
  `voucherPrice=${encodeURIComponent('USD 10.00')}&` +
  `voucherAmount=${encodeURIComponent('100000 sats')}&` +
  `voucherSecret=${encodeURIComponent('abc123def456')}&` +
  `commissionPercentage=${encodeURIComponent('5')}&` +
  `identifierCode=${encodeURIComponent('ABCD1234')}&` +
  // Optional new fields:
  `expiresAt=${encodeURIComponent('1735689600000')}&` +  // Unix timestamp ms
  `issuedBy=${encodeURIComponent('merchant_username')}&` +
  `memo=${encodeURIComponent('Happy Birthday!')}`;

window.location.href = deepLinkUrl;
```

#### Payment Receipt
```javascript
const deepLinkUrl = `blink-pos-companion://print?` +
  `username=${encodeURIComponent('blink_user')}&` +
  `amount=${encodeURIComponent('50,000 sats')}&` +
  `paymentHash=${encodeURIComponent('abc123...')}&` +
  `id=${encodeURIComponent('txn_12345')}&` +  // Optional
  `date=${encodeURIComponent('2024-01-15')}&` +  // Optional
  `time=${encodeURIComponent('14:30:00')}`;  // Optional

window.location.href = deepLinkUrl;
```

### Protocol V2 (New) - Pre-built ESC/POS Data

Used when the web app builds the complete receipt and sends raw ESC/POS commands.
This gives full control over receipt layout without modifying the companion app.

```javascript
// Build ESC/POS data in web app (using your ESC/POS library)
const escposData = buildReceipt(voucherData); // Returns Uint8Array

// Convert to base64
const base64Data = btoa(String.fromCharCode(...escposData));

// Build deep link
const deepLinkUrl = `blink-pos-companion://print?` +
  `version=2&` +
  `escpos=${encodeURIComponent(base64Data)}&` +
  `printer=nyx`;  // or 'bluetooth'

// For specific Bluetooth printer:
const deepLinkUrl = `blink-pos-companion://print?` +
  `version=2&` +
  `escpos=${encodeURIComponent(base64Data)}&` +
  `printer=bluetooth&` +
  `address=${encodeURIComponent('AA:BB:CC:DD:EE:FF')}`;

window.location.href = deepLinkUrl;
```

### Parameter Reference

| Parameter | Protocol | Description |
|-----------|----------|-------------|
| `version` | V2 | Set to `2` for V2 protocol |
| `escpos` | V2 | Base64 encoded ESC/POS command bytes |
| `printer` | V2 | `nyx` (Bitcoinize) or `bluetooth` |
| `address` | V2 | Bluetooth MAC address (optional) |
| `paper_width` | V2 | Paper width hint: `58` or `80` |
| `app` | V1 | `voucher` or omit for payment |
| `lnurl` | V1 | LNURL for voucher QR code |
| `voucherPrice` | V1 | Fiat price (e.g., "USD 10.00") |
| `voucherAmount` | V1 | Sats amount (e.g., "100000 sats") |
| `voucherSecret` | V1 | 12-char voucher secret |
| `identifierCode` | V1 | 8-char voucher ID |
| `commissionPercentage` | V1 | Commission % |
| `expiresAt` | V1 | Expiry timestamp (ms) |
| `issuedBy` | V1 | Issuer username |
| `memo` | V1 | Optional memo text |
| `username` | V1 | Payment username |
| `amount` | V1 | Payment amount |
| `paymentHash` | V1 | Lightning payment hash |

## Troubleshooting

### Bluetooth Printer Not Working

1. **Check Bluetooth is enabled** on your device
2. **Ensure printer is paired** in Android Settings → Bluetooth
3. **Printer must be turned on** and in range
4. **Grant Bluetooth permissions** when prompted

### Bitcoinize POS Not Printing

1. **Ensure paper is loaded** in the printer
2. **Check Nyx Printer Service** is installed (comes pre-installed on Bitcoinize)

### Nothing Happens When Clicking Print

1. **Ensure companion app is installed**
2. **Check the app is set as default** for `blink-pos-companion://` links
3. **Try reinstalling** the companion app

## Development

### Project Structure
```
app/src/main/java/com/blink/pos/companion/
├── MainActivity.java           # Deep link handler & print orchestrator
├── BluetoothPrinterManager.java # Generic Bluetooth printer support
├── Result.java                 # Result codes
├── SdkResult.java             # Nyx SDK result codes
└── Utils.java                 # Utility functions
```

### Building
```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease
```

### Testing Deep Links
```bash
# Test voucher print (V1)
adb shell am start -a android.intent.action.VIEW \
  -d "blink-pos-companion://print?app=voucher&lnurl=LNURL1...&voucherPrice=USD%2010&voucherAmount=10000%20sats&voucherSecret=abc123def456&commissionPercentage=5&identifierCode=ABCD1234"

# Test payment print (V1)  
adb shell am start -a android.intent.action.VIEW \
  -d "blink-pos-companion://print?username=testuser&amount=50000%20sats&paymentHash=abc123"
```

## Contributing

Pull requests welcome! Please ensure:
1. Code follows existing style
2. Test on both Bitcoinize POS and generic Bluetooth printer
3. Update README if adding new features

## License

MIT License - see LICENSE file

## Credits

- Based on [NyxPrinterClient](https://github.com/yyzz2333/NyxPrinterClient) SDK
- Developed for [Blink Bitcoin Terminal](https://track.twentyone.ist)
