package com.blink.pos.companion;

import android.content.*;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Base64;
import android.util.Log;
import androidx.appcompat.app.AppCompatActivity;
import net.nyx.printerservice.print.IPrinterService;
import net.nyx.printerservice.print.PrintTextFormat;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Blink Bitcoin Terminal - Print Companion App
 * 
 * This app receives print commands via deep links and sends them to:
 * 1. Bitcoinize POS built-in printer (via Nyx SDK)
 * 2. Generic Bluetooth thermal printers (via BluetoothPrinterManager)
 * 
 * Supported deep link protocols:
 * 
 * V1 (Legacy) - App renders receipt:
 *   blink-pos-companion://print?app=voucher&lnurl=...&voucherPrice=...
 *   blink-pos-companion://print?username=...&amount=...&paymentHash=...
 * 
 * V2 (New) - Web app sends pre-built ESC/POS data:
 *   blink-pos-companion://print?version=2&escpos=<base64>&printer=nyx|bluetooth
 * 
 * V2 allows full control over receipt layout from the web app.
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "BlinkPrintCompanion";
    
    // Printer service for Nyx SDK (Bitcoinize POS)
    private IPrinterService printerService;
    private ExecutorService singleThreadExecutor = Executors.newSingleThreadExecutor();
    private boolean isPrinterServiceBound = false;
    
    // Bluetooth printer manager for generic thermal printers
    private BluetoothPrinterManager bluetoothPrinterManager;
    
    // Pending print data (when service not yet bound)
    private PrintJob pendingPrintJob = null;
    
    // Service connection for Nyx printer
    private ServiceConnection connService = new ServiceConnection() {
        @Override
        public void onServiceDisconnected(ComponentName name) {
            printerService = null;
            isPrinterServiceBound = false;
            Log.d(TAG, "Nyx printer service disconnected");
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            printerService = IPrinterService.Stub.asInterface(service);
            isPrinterServiceBound = true;
            Log.d(TAG, "Nyx printer service connected");
            
            // Process pending job if any
            if (pendingPrintJob != null && pendingPrintJob.printerType == PrinterType.NYX) {
                executePrintJob(pendingPrintJob);
                pendingPrintJob = null;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Initialize Bluetooth printer manager
        bluetoothPrinterManager = new BluetoothPrinterManager(this);
        
        // Try to bind to Nyx service (will fail silently if not on Bitcoinize device)
        bindNyxService();
        
        // Handle the incoming intent
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /**
     * Handle incoming deep link intent
     */
    private void handleIntent(Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_VIEW.equals(action)) {
            return;
        }
        
        Uri data = intent.getData();
        if (data == null) {
            Log.e(TAG, "No data in intent");
            finish();
            return;
        }
        
        Log.d(TAG, "Received deep link: " + data.toString());
        
        // Parse the deep link and create print job
        PrintJob printJob = parsePrintJob(data);
        
        if (printJob == null) {
            Log.e(TAG, "Failed to parse print job");
            finish();
            return;
        }
        
        // Execute or queue the print job
        if (printJob.printerType == PrinterType.NYX) {
            if (isPrinterServiceBound) {
                executePrintJob(printJob);
            } else {
                pendingPrintJob = printJob;
                Log.d(TAG, "Queued print job, waiting for Nyx service");
            }
        } else if (printJob.printerType == PrinterType.BLUETOOTH) {
            executePrintJob(printJob);
        }
        
        // Close activity (runs in background)
        finish();
    }

    /**
     * Parse deep link URI into a PrintJob
     */
    private PrintJob parsePrintJob(Uri data) {
        // Check protocol version
        String version = data.getQueryParameter("version");
        
        if ("2".equals(version)) {
            // V2 Protocol: Pre-built ESC/POS data
            return parseV2PrintJob(data);
        } else {
            // V1 Protocol: Legacy format, app renders receipt
            return parseV1PrintJob(data);
        }
    }

    /**
     * Parse V2 protocol (pre-built ESC/POS data)
     */
    private PrintJob parseV2PrintJob(Uri data) {
        PrintJob job = new PrintJob();
        job.protocolVersion = 2;
        
        // Get ESC/POS data (base64 encoded)
        String escposBase64 = data.getQueryParameter("escpos");
        if (escposBase64 == null || escposBase64.isEmpty()) {
            Log.e(TAG, "V2 protocol requires 'escpos' parameter");
            return null;
        }
        
        try {
            job.escposData = Base64.decode(escposBase64, Base64.DEFAULT);
            Log.d(TAG, "Decoded ESC/POS data: " + job.escposData.length + " bytes");
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Failed to decode base64 ESC/POS data", e);
            return null;
        }
        
        // Determine printer type
        String printer = data.getQueryParameter("printer");
        if ("bluetooth".equals(printer)) {
            job.printerType = PrinterType.BLUETOOTH;
            job.bluetoothAddress = data.getQueryParameter("address");
        } else {
            // Default to Nyx (Bitcoinize POS)
            job.printerType = PrinterType.NYX;
        }
        
        // Optional: paper width hint
        String paperWidth = data.getQueryParameter("paper_width");
        if (paperWidth != null) {
            try {
                job.paperWidth = Integer.parseInt(paperWidth);
            } catch (NumberFormatException e) {
                job.paperWidth = 80;
            }
        }
        
        return job;
    }

    /**
     * Parse V1 protocol (legacy format)
     */
    private PrintJob parseV1PrintJob(Uri data) {
        PrintJob job = new PrintJob();
        job.protocolVersion = 1;
        job.printerType = PrinterType.NYX; // V1 always uses Nyx
        
        String app = data.getQueryParameter("app");
        
        if ("voucher".equals(app)) {
            job.receiptType = ReceiptType.VOUCHER;
            job.voucherData = new VoucherData();
            job.voucherData.lnurl = data.getQueryParameter("lnurl");
            job.voucherData.voucherPrice = data.getQueryParameter("voucherPrice");
            job.voucherData.voucherAmount = data.getQueryParameter("voucherAmount");
            job.voucherData.voucherSecret = data.getQueryParameter("voucherSecret");
            job.voucherData.commissionPercentage = data.getQueryParameter("commissionPercentage");
            job.voucherData.identifierCode = data.getQueryParameter("identifierCode");
            // New V1.1 fields
            job.voucherData.expiresAt = data.getQueryParameter("expiresAt");
            job.voucherData.issuedBy = data.getQueryParameter("issuedBy");
            job.voucherData.memo = data.getQueryParameter("memo");
        } else {
            job.receiptType = ReceiptType.PAYMENT;
            job.paymentData = new PaymentData();
            job.paymentData.username = data.getQueryParameter("username");
            job.paymentData.amount = data.getQueryParameter("amount");
            job.paymentData.paymentHash = data.getQueryParameter("paymentHash");
            job.paymentData.transactionId = data.getQueryParameter("id");
            job.paymentData.date = data.getQueryParameter("date");
            job.paymentData.time = data.getQueryParameter("time");
        }
        
        return job;
    }

    /**
     * Execute a print job
     */
    private void executePrintJob(PrintJob job) {
        if (job.protocolVersion == 2) {
            // V2: Send raw ESC/POS data
            executeV2PrintJob(job);
        } else {
            // V1: Render receipt in app
            executeV1PrintJob(job);
        }
    }

    /**
     * Execute V2 print job (raw ESC/POS data)
     */
    private void executeV2PrintJob(PrintJob job) {
        if (job.printerType == PrinterType.NYX) {
            printRawToNyx(job.escposData);
        } else if (job.printerType == PrinterType.BLUETOOTH) {
            printRawToBluetooth(job.escposData, job.bluetoothAddress);
        }
    }

    /**
     * Execute V1 print job (app renders receipt)
     */
    private void executeV1PrintJob(PrintJob job) {
        if (job.receiptType == ReceiptType.VOUCHER) {
            printVoucherReceipt(job.voucherData);
        } else {
            printPaymentReceipt(job.paymentData);
        }
    }

    /**
     * Send raw ESC/POS data to Nyx printer (Bitcoinize POS)
     */
    private void printRawToNyx(byte[] escposData) {
        singleThreadExecutor.submit(() -> {
            try {
                if (printerService == null) {
                    Log.e(TAG, "Nyx printer service not available");
                    return;
                }
                
                // The Nyx SDK has printRawData method for raw ESC/POS commands
                int result = printerService.printRawData(escposData, escposData.length);
                Log.d(TAG, "Nyx raw print result: " + result);
                
                // Feed paper
                paperOut();
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to print raw data to Nyx", e);
            }
        });
    }

    /**
     * Send raw ESC/POS data to Bluetooth thermal printer
     */
    private void printRawToBluetooth(byte[] escposData, String address) {
        singleThreadExecutor.submit(() -> {
            boolean success = bluetoothPrinterManager.printRaw(escposData, address);
            Log.d(TAG, "Bluetooth print result: " + success);
        });
    }

    /**
     * Print voucher receipt (V1 - app renders)
     */
    private void printVoucherReceipt(VoucherData voucher) {
        singleThreadExecutor.submit(() -> {
            try {
                PrintTextFormat dashedFormat = new PrintTextFormat();
                dashedFormat.setStyle(1); // Bold
                dashedFormat.setTextSize(27);
                dashedFormat.setAli(1); // Center

                // Blink Logo
                printLogo();

                // Dashed line
                String dashedLine = new String(new char[32]).replace("\0", "-");
                printerService.printText(dashedLine, dashedFormat);

                // Voucher details
                if (voucher.voucherPrice != null && !voucher.voucherPrice.isEmpty()) {
                    printKeyValue("Price:", voucher.voucherPrice);
                }
                if (voucher.voucherAmount != null && !voucher.voucherAmount.isEmpty()) {
                    printKeyValue("Value:", voucher.voucherAmount);
                }
                if (voucher.identifierCode != null && !voucher.identifierCode.isEmpty()) {
                    printKeyValue("ID:", voucher.identifierCode);
                }
                if (voucher.commissionPercentage != null && !voucher.commissionPercentage.isEmpty() 
                    && !"0".equals(voucher.commissionPercentage)) {
                    printKeyValue("Commission:", voucher.commissionPercentage + "%");
                }
                // New fields
                if (voucher.expiresAt != null && !voucher.expiresAt.isEmpty()) {
                    printKeyValue("Expires:", formatExpiry(voucher.expiresAt));
                }
                if (voucher.issuedBy != null && !voucher.issuedBy.isEmpty()) {
                    printKeyValue("Issued by:", voucher.issuedBy);
                }
                
                // Dashed line
                printerService.printText(dashedLine, dashedFormat);

                // QR Code with LNURL
                if (voucher.lnurl != null && !voucher.lnurl.isEmpty()) {
                    printerService.printQrCode(voucher.lnurl, 350, 350, 1);
                }
                
                printerService.printText(dashedLine, dashedFormat);

                // Voucher secret
                PrintTextFormat secretFormat = new PrintTextFormat();
                secretFormat.setAli(1);
                secretFormat.setTextSize(25);
                secretFormat.setStyle(1);
                
                printerService.printText("voucher secret", secretFormat);
                if (voucher.voucherSecret != null) {
                    printerService.printText(formatVoucherSecret(voucher.voucherSecret), secretFormat);
                }

                // Dashed line
                printerService.printText(dashedLine, dashedFormat);
                
                // App link
                printerService.printText("voucher.blink.sv", secretFormat);
                
                // Memo if present
                if (voucher.memo != null && !voucher.memo.isEmpty()) {
                    printerService.printText("\n", secretFormat);
                    printerService.printText(voucher.memo, secretFormat);
                }
                
                printerService.printText("\n", secretFormat);

                // Feed and cut
                paperOut();
                
            } catch (RemoteException | IOException e) {
                Log.e(TAG, "Failed to print voucher receipt", e);
            }
        });
    }

    /**
     * Print payment receipt (V1 - app renders)
     */
    private void printPaymentReceipt(PaymentData payment) {
        singleThreadExecutor.submit(() -> {
            try {
                PrintTextFormat dashedFormat = new PrintTextFormat();
                dashedFormat.setStyle(1);
                dashedFormat.setTextSize(27);
                dashedFormat.setAli(1);

                // Date/time formatting
                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.US);
                dateFormat.setTimeZone(TimeZone.getDefault());
                timeFormat.setTimeZone(TimeZone.getDefault());
                String currentDate = dateFormat.format(new Date());
                String currentTime = timeFormat.format(new Date());

                // Blink Logo
                printLogo();

                // Dashed line
                String dashedLine = new String(new char[32]).replace("\0", "-");
                printerService.printText(dashedLine, dashedFormat);

                // Payment details
                if (payment.username != null && !payment.username.isEmpty()) {
                    printKeyValue("Username:", payment.username);
                }
                if (payment.amount != null && !payment.amount.isEmpty()) {
                    printKeyValue("Amount:", payment.amount);
                }
                
                String date = (payment.date != null && !payment.date.isEmpty()) ? payment.date : currentDate;
                String time = (payment.time != null && !payment.time.isEmpty()) ? payment.time : currentTime;
                printKeyValue("Date:", date);
                printKeyValue("Time:", time);

                // Dashed line
                printerService.printText(dashedLine, dashedFormat);

                // Transaction details
                PrintTextFormat txFormat = new PrintTextFormat();
                txFormat.setAli(1);
                txFormat.setTextSize(23);
                txFormat.setStyle(1);

                if (payment.transactionId != null && !payment.transactionId.isEmpty()) {
                    printerService.printText("Blink Internal Id", txFormat);
                    printerService.printText(payment.transactionId, txFormat);
                    printerService.printText("\n", txFormat);
                }

                if (payment.paymentHash != null && !payment.paymentHash.isEmpty()) {
                    printerService.printText("Payment Hash", txFormat);
                    printerService.printText(payment.paymentHash, txFormat);
                    printerService.printText("\n", txFormat);
                }

                // Feed and cut
                paperOut();
                
            } catch (RemoteException | IOException e) {
                Log.e(TAG, "Failed to print payment receipt", e);
            }
        });
    }

    /**
     * Print the Blink logo
     */
    private void printLogo() throws IOException, RemoteException {
        Bitmap originalBitmap = BitmapFactory.decodeStream(getAssets().open("blink-logo.png"));
        int maxWidthPixels = 200;
        double aspectRatio = (double) originalBitmap.getWidth() / originalBitmap.getHeight();
        int newHeight = (int) (maxWidthPixels / aspectRatio);
        Bitmap resizedBitmap = Bitmap.createScaledBitmap(originalBitmap, maxWidthPixels, newHeight, true);
        printerService.printBitmap(resizedBitmap, 1, 1);
        originalBitmap.recycle();
    }

    /**
     * Print a key-value pair with proper spacing
     */
    private void printKeyValue(String key, String value) throws RemoteException {
        PrintTextFormat format = new PrintTextFormat();
        format.setStyle(1);
        format.setTextSize(23);
        
        // Calculate spacing for alignment
        int totalWidth = 32; // characters
        int keyLen = key.length();
        int valueLen = value.length();
        int spaces = Math.max(1, totalWidth - keyLen - valueLen);
        
        String spacing = new String(new char[spaces]).replace("\0", " ");
        printerService.printText(key + spacing + value, format);
    }

    /**
     * Format voucher secret with spaces every 4 characters
     */
    private String formatVoucherSecret(String secret) {
        if (secret == null) return "";
        StringBuilder formatted = new StringBuilder();
        for (int i = 0; i < secret.length(); i += 4) {
            if (i > 0) {
                formatted.append(" ");
            }
            formatted.append(secret.substring(i, Math.min(i + 4, secret.length())));
        }
        return formatted.toString();
    }

    /**
     * Format expiry timestamp
     */
    private String formatExpiry(String expiresAt) {
        try {
            long timestamp = Long.parseLong(expiresAt);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
            return sdf.format(new Date(timestamp));
        } catch (NumberFormatException e) {
            return expiresAt; // Return as-is if not a timestamp
        }
    }

    /**
     * Bind to Nyx printer service (Bitcoinize POS)
     */
    private void bindNyxService() {
        Intent intent = new Intent();
        intent.setPackage("net.nyx.printerservice");
        intent.setAction("net.nyx.printerservice.IPrinterService");
        try {
            bindService(intent, connService, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            Log.w(TAG, "Could not bind to Nyx service (not a Bitcoinize device?)", e);
        }
    }

    /**
     * Feed paper out
     */
    private void paperOut() {
        singleThreadExecutor.submit(() -> {
            try {
                if (printerService != null) {
                    printerService.paperOut(80);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to feed paper", e);
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isPrinterServiceBound) {
            unbindService(connService);
            isPrinterServiceBound = false;
        }
        if (bluetoothPrinterManager != null) {
            bluetoothPrinterManager.cleanup();
        }
    }

    // ============================================================
    // Data classes
    // ============================================================

    enum PrinterType {
        NYX,        // Bitcoinize POS built-in printer
        BLUETOOTH   // Generic Bluetooth thermal printer
    }

    enum ReceiptType {
        VOUCHER,
        PAYMENT
    }

    static class PrintJob {
        int protocolVersion = 1;
        PrinterType printerType = PrinterType.NYX;
        ReceiptType receiptType = ReceiptType.PAYMENT;
        
        // V2: Raw ESC/POS data
        byte[] escposData;
        int paperWidth = 80;
        
        // V1: Structured data
        VoucherData voucherData;
        PaymentData paymentData;
        
        // Bluetooth specific
        String bluetoothAddress;
    }

    static class VoucherData {
        String lnurl;
        String voucherPrice;
        String voucherAmount;
        String voucherSecret;
        String commissionPercentage;
        String identifierCode;
        // New fields
        String expiresAt;
        String issuedBy;
        String memo;
    }

    static class PaymentData {
        String username;
        String amount;
        String paymentHash;
        String transactionId;
        String date;
        String time;
    }
}
