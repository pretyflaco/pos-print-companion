package com.blink.pos.companion;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.bluetooth.BluetoothDevice;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import net.nyx.printerservice.print.IPrinterService;
import net.nyx.printerservice.print.PrintTextFormat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Blink Bitcoin Terminal - Print Companion App
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "BlinkPrintCompanion";
    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 1001;
    
    private IPrinterService printerService;
    private ExecutorService executor;
    private boolean isPrinterServiceBound = false;
    private BluetoothPrinterManager bluetoothPrinterManager;
    private PrintJob pendingPrintJob = null;
    private Uri pendingDeepLinkUri = null;
    private boolean isSetupMode = false;
    
    private TextView statusText;
    private TextView printerListText;
    private Handler mainHandler;
    
    private ServiceConnection connService = new ServiceConnection() {
        @Override
        public void onServiceDisconnected(ComponentName name) {
            printerService = null;
            isPrinterServiceBound = false;
            Log.d(TAG, "Nyx printer service disconnected");
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            try {
                printerService = IPrinterService.Stub.asInterface(service);
                isPrinterServiceBound = true;
                Log.d(TAG, "Nyx printer service connected");
                
                // Handle pending deep link that was waiting for Nyx service
                if (pendingDeepLinkUri != null) {
                    Log.d(TAG, "Processing pending deep link now that Nyx is ready");
                    Uri uri = pendingDeepLinkUri;
                    pendingDeepLinkUri = null;
                    processPendingDeepLink(uri);
                }
                
                if (pendingPrintJob != null && pendingPrintJob.printerType == PrinterType.NYX) {
                    executePrintJob(pendingPrintJob);
                    pendingPrintJob = null;
                }
                
                if (isSetupMode && mainHandler != null) {
                    mainHandler.post(() -> updateStatus());
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in onServiceConnected", e);
            }
        }
    };
    
    private void processPendingDeepLink(Uri data) {
        PrintJob job = parsePrintJob(data);
        if (job == null) {
            showToast("Invalid print data");
            finish();
            return;
        }
        
        Log.d(TAG, "Processing pending deep link: printerType=" + job.printerType);
        executePrintJob(job);
        
        // Finish after delay
        mainHandler.postDelayed(this::finish, 3000);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        Log.d(TAG, "=== onCreate START ===");
        
        try {
            mainHandler = new Handler(Looper.getMainLooper());
            executor = Executors.newSingleThreadExecutor();
            
            // Initialize Bluetooth manager safely
            try {
                bluetoothPrinterManager = new BluetoothPrinterManager(this);
                Log.d(TAG, "BluetoothPrinterManager initialized");
            } catch (Exception e) {
                Log.e(TAG, "Failed to init BluetoothPrinterManager", e);
                bluetoothPrinterManager = null;
            }
            
            // Try to bind to Nyx service
            try {
                bindNyxService();
            } catch (Exception e) {
                Log.e(TAG, "Failed to bind Nyx service", e);
            }
            
            // Check intent
            Intent intent = getIntent();
            String action = intent != null ? intent.getAction() : null;
            Log.d(TAG, "Intent action: " + action);
            
            if (Intent.ACTION_VIEW.equals(action)) {
                Log.d(TAG, "Deep link mode");
                handleDeepLinkSafely(intent);
            } else {
                Log.d(TAG, "Setup UI mode");
                isSetupMode = true;
                showSetupUI();
            }
            
            Log.d(TAG, "=== onCreate END ===");
            
        } catch (Exception e) {
            Log.e(TAG, "FATAL ERROR in onCreate", e);
            showErrorAndFinish("Startup error: " + e.getMessage());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        try {
            setIntent(intent);
            if (Intent.ACTION_VIEW.equals(intent.getAction())) {
                handleDeepLinkSafely(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onNewIntent", e);
        }
    }
    
    private void handleDeepLinkSafely(Intent intent) {
        try {
            if (hasBluetoothPermissions()) {
                handleDeepLink(intent);
            } else {
                Uri data = intent.getData();
                if (data != null) {
                    pendingPrintJob = parsePrintJob(data);
                }
                requestBluetoothPermissions();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling deep link", e);
            showToast("Print error: " + e.getMessage());
            finish();
        }
    }
    
    private void showErrorAndFinish(String message) {
        try {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "Could not show toast", e);
        }
        finish();
    }
    
    private void showSetupUI() {
        try {
            Log.d(TAG, "Building setup UI");
            
            // Create scroll view with dark background
            ScrollView scrollView = new ScrollView(this);
            scrollView.setBackgroundColor(Color.parseColor("#1A1A2E"));
            scrollView.setFillViewport(true);
            
            // Main layout
            LinearLayout mainLayout = new LinearLayout(this);
            mainLayout.setOrientation(LinearLayout.VERTICAL);
            mainLayout.setPadding(48, 80, 48, 48);
            
            // Title
            TextView titleText = new TextView(this);
            titleText.setText("Blink Print Companion");
            titleText.setTextSize(22f);
            titleText.setTextColor(Color.WHITE);
            titleText.setGravity(Gravity.CENTER);
            mainLayout.addView(titleText);
            
            // Spacer
            View spacer1 = new View(this);
            spacer1.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 48));
            mainLayout.addView(spacer1);
            
            // Status section
            TextView statusLabel = new TextView(this);
            statusLabel.setText("Status:");
            statusLabel.setTextSize(16f);
            statusLabel.setTextColor(Color.parseColor("#AAAAAA"));
            mainLayout.addView(statusLabel);
            
            statusText = new TextView(this);
            statusText.setTextSize(14f);
            statusText.setTextColor(Color.parseColor("#CCCCCC"));
            statusText.setPadding(0, 8, 0, 24);
            mainLayout.addView(statusText);
            
            // Printer list section
            TextView printerLabel = new TextView(this);
            printerLabel.setText("Paired Printers:");
            printerLabel.setTextSize(16f);
            printerLabel.setTextColor(Color.parseColor("#AAAAAA"));
            mainLayout.addView(printerLabel);
            
            printerListText = new TextView(this);
            printerListText.setTextSize(13f);
            printerListText.setTextColor(Color.parseColor("#888888"));
            printerListText.setPadding(0, 8, 0, 32);
            mainLayout.addView(printerListText);
            
            // Buttons
            addButton(mainLayout, "Grant Bluetooth Permission", "#4A90D9", v -> {
                requestBluetoothPermissions();
            });
            
            addButton(mainLayout, "Refresh Status", "#9C27B0", v -> {
                updateStatus();
            });
            
            addButton(mainLayout, "Test Print", "#4CAF50", v -> {
                testPrint();
            });
            
            addButton(mainLayout, "Close", "#666666", v -> {
                finish();
            });
            
            // Instructions
            View spacer2 = new View(this);
            spacer2.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 32));
            mainLayout.addView(spacer2);
            
            TextView instructions = new TextView(this);
            instructions.setText(
                "How to use:\n\n" +
                "1. Grant Bluetooth permission\n" +
                "2. Pair printer in Android Settings\n" +
                "3. Tap Refresh to see printers\n" +
                "4. Test Print to verify\n" +
                "5. Print from Blink web app!"
            );
            instructions.setTextSize(13f);
            instructions.setTextColor(Color.parseColor("#666666"));
            mainLayout.addView(instructions);
            
            scrollView.addView(mainLayout);
            setContentView(scrollView);
            
            Log.d(TAG, "Setup UI created, updating status");
            updateStatus();
            
        } catch (Exception e) {
            Log.e(TAG, "Error creating setup UI", e);
            showErrorAndFinish("UI Error: " + e.getMessage());
        }
    }
    
    private void addButton(LinearLayout parent, String text, String color, View.OnClickListener listener) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setAllCaps(false);
        
        try {
            btn.setBackgroundColor(Color.parseColor(color));
        } catch (Exception e) {
            btn.setBackgroundColor(Color.GRAY);
        }
        
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, 16);
        btn.setLayoutParams(params);
        btn.setPadding(16, 24, 16, 24);
        
        btn.setOnClickListener(v -> {
            try {
                listener.onClick(v);
            } catch (Exception e) {
                Log.e(TAG, "Button click error", e);
                showToast("Error: " + e.getMessage());
            }
        });
        
        parent.addView(btn);
    }
    
    private void updateStatus() {
        Log.d(TAG, "updateStatus called");
        
        if (statusText == null) {
            Log.w(TAG, "statusText is null");
            return;
        }
        
        try {
            StringBuilder status = new StringBuilder();
            
            // Permission status
            boolean hasPerm = hasBluetoothPermissions();
            status.append(hasPerm ? "[OK] " : "[X] ");
            status.append("Bluetooth permission: ");
            status.append(hasPerm ? "Granted" : "Not granted");
            status.append("\n");
            
            // Bluetooth availability
            boolean btAvailable = bluetoothPrinterManager != null && 
                                  bluetoothPrinterManager.isBluetoothAvailable();
            status.append(btAvailable ? "[OK] " : "[X] ");
            status.append("Bluetooth: ");
            status.append(btAvailable ? "Enabled" : "Disabled/Unavailable");
            status.append("\n");
            
            // Nyx printer
            status.append(isPrinterServiceBound ? "[OK] " : "[-] ");
            status.append("Bitcoinize POS: ");
            status.append(isPrinterServiceBound ? "Connected" : "Not available");
            
            statusText.setText(status.toString());
            
            // Update printer list
            updatePrinterList();
            
        } catch (Exception e) {
            Log.e(TAG, "Error updating status", e);
            statusText.setText("Error getting status");
        }
    }
    
    private void updatePrinterList() {
        if (printerListText == null) return;
        
        try {
            if (!hasBluetoothPermissions()) {
                printerListText.setText("Grant permission first, then tap Refresh");
                return;
            }
            
            if (bluetoothPrinterManager == null || !bluetoothPrinterManager.isBluetoothAvailable()) {
                printerListText.setText("Bluetooth not available");
                return;
            }
            
            List<BluetoothDevice> printers = bluetoothPrinterManager.getPairedPrinters();
            
            if (printers.isEmpty()) {
                printerListText.setText("No printers found.\nPair your printer in Android Settings > Bluetooth");
            } else {
                StringBuilder sb = new StringBuilder();
                for (BluetoothDevice device : printers) {
                    String name = "Unknown";
                    try {
                        name = device.getName();
                        if (name == null) name = "Unknown";
                    } catch (SecurityException e) {
                        Log.w(TAG, "Permission error getting device name");
                    }
                    sb.append("- ").append(name).append("\n");
                }
                printerListText.setText(sb.toString());
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error updating printer list", e);
            printerListText.setText("Error listing printers");
        }
    }
    
    private void testPrint() {
        Log.d(TAG, "testPrint called");
        
        if (!hasBluetoothPermissions()) {
            showToast("Grant Bluetooth permission first");
            return;
        }
        
        showToast("Starting test print...");
        
        // Try Nyx first
        if (isPrinterServiceBound && printerService != null) {
            executor.submit(() -> {
                try {
                    PrintTextFormat format = new PrintTextFormat();
                    format.setAli(1);
                    format.setTextSize(24);
                    
                    printerService.printText("=== TEST ===", format);
                    printerService.printText("Blink Companion", format);
                    printerService.printText(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()), format);
                    printerService.printText("Success!", format);
                    printerService.paperOut(80);
                    
                    mainHandler.post(() -> showToast("Test print OK!"));
                } catch (Exception e) {
                    Log.e(TAG, "Nyx test print error", e);
                    mainHandler.post(() -> showToast("Print error: " + e.getMessage()));
                }
            });
            return;
        }
        
        // Try Bluetooth
        if (bluetoothPrinterManager != null && bluetoothPrinterManager.isBluetoothAvailable()) {
            List<BluetoothDevice> printers = new ArrayList<>();
            try {
                printers = bluetoothPrinterManager.getPairedPrinters();
            } catch (Exception e) {
                Log.e(TAG, "Error getting printers", e);
            }
            
            if (!printers.isEmpty()) {
                byte[] testData = buildTestReceipt();
                executor.submit(() -> {
                    try {
                        boolean success = bluetoothPrinterManager.printRaw(testData, null);
                        mainHandler.post(() -> {
                            showToast(success ? "Test print OK!" : "Print failed");
                        });
                    } catch (Exception e) {
                        Log.e(TAG, "BT test print error", e);
                        mainHandler.post(() -> showToast("Error: " + e.getMessage()));
                    }
                });
                return;
            }
        }
        
        showToast("No printer found. Pair one first.");
    }
    
    private byte[] buildTestReceipt() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            baos.write(new byte[]{0x1B, 0x40}); // Init
            baos.write(new byte[]{0x1B, 0x61, 0x01}); // Center
            baos.write("=== TEST PRINT ===\n".getBytes());
            baos.write("Blink Companion\n".getBytes());
            baos.write("----------------\n".getBytes());
            baos.write((new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date()) + "\n").getBytes());
            baos.write("\nPrinter working!\n".getBytes());
            baos.write("\n\n\n".getBytes());
            return baos.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "Error building test receipt", e);
            return new byte[0];
        }
    }
    
    private boolean hasBluetoothPermissions() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) 
                        == PackageManager.PERMISSION_GRANTED;
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error checking permissions", e);
            return false;
        }
    }
    
    private void requestBluetoothPermissions() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Log.d(TAG, "Requesting BT permissions");
                ActivityCompat.requestPermissions(this, 
                    new String[]{
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                    }, 
                    REQUEST_BLUETOOTH_PERMISSIONS);
            } else {
                showToast("Permission already granted");
                updateStatus();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error requesting permissions", e);
            showToast("Error: " + e.getMessage());
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        try {
            if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
                boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
                Log.d(TAG, "Permission result: " + granted);
                
                showToast(granted ? "Permission granted!" : "Permission denied");
                
                if (isSetupMode) {
                    updateStatus();
                }
                
                if (granted && pendingPrintJob != null) {
                    executePrintJob(pendingPrintJob);
                    pendingPrintJob = null;
                    if (!isSetupMode) finish();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in permission result", e);
        }
    }
    
    private void showToast(String msg) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Toast error", e);
        }
    }

    private void handleDeepLink(Intent intent) {
        Uri data = intent.getData();
        if (data == null) {
            Log.e(TAG, "No data in deep link");
            showToast("No print data");
            finish();
            return;
        }
        
        Log.d(TAG, "Deep link: " + data);
        
        // Check if we're on a device that likely has Nyx printer (Bitcoinize POS)
        boolean isNyxDevice = isNyxPrinterDevice();
        Log.d(TAG, "isNyxDevice=" + isNyxDevice + ", isPrinterServiceBound=" + isPrinterServiceBound);
        
        // If this looks like a Nyx device but service isn't bound yet, wait for it
        if (isNyxDevice && !isPrinterServiceBound) {
            Log.d(TAG, "Nyx device detected but service not ready, waiting...");
            pendingDeepLinkUri = data;
            // The service connection callback will handle the print
            return;
        }
        
        // Parse and execute
        PrintJob job = parsePrintJob(data);
        if (job == null) {
            showToast("Invalid print data");
            finish();
            return;
        }
        
        Log.d(TAG, "PrintJob parsed: printerType=" + job.printerType + 
                   ", receiptType=" + job.receiptType +
                   ", isPrinterServiceBound=" + isPrinterServiceBound +
                   ", printerService=" + (printerService != null));
        
        if (job.printerType == PrinterType.NYX) {
            if (isPrinterServiceBound && printerService != null) {
                Log.d(TAG, "Executing Nyx print job immediately");
                executePrintJob(job);
            } else {
                Log.d(TAG, "Nyx service not ready, queuing job");
                pendingPrintJob = job;
            }
        } else {
            Log.d(TAG, "Executing Bluetooth print job");
            executePrintJob(job);
        }
        
        // Finish after longer delay to allow print to complete
        mainHandler.postDelayed(this::finish, 3000);
    }
    
    /**
     * Check if this device likely has a Nyx printer (Bitcoinize POS)
     * We check if the Nyx printer service package is installed
     */
    private boolean isNyxPrinterDevice() {
        try {
            getPackageManager().getPackageInfo("net.nyx.printerservice", 0);
            Log.d(TAG, "Nyx printer service package found");
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            Log.d(TAG, "Nyx printer service package not found");
            return false;
        }
    }

    private PrintJob parsePrintJob(Uri data) {
        try {
            String version = data.getQueryParameter("version");
            return "2".equals(version) ? parseV2PrintJob(data) : parseV1PrintJob(data);
        } catch (Exception e) {
            Log.e(TAG, "Error parsing print job", e);
            return null;
        }
    }

    private PrintJob parseV2PrintJob(Uri data) {
        PrintJob job = new PrintJob();
        job.protocolVersion = 2;
        
        String escposBase64 = data.getQueryParameter("escpos");
        if (escposBase64 == null || escposBase64.isEmpty()) {
            return null;
        }
        
        try {
            job.escposData = Base64.decode(escposBase64, Base64.DEFAULT);
        } catch (Exception e) {
            Log.e(TAG, "Base64 decode error", e);
            return null;
        }
        
        String printer = data.getQueryParameter("printer");
        if ("bluetooth".equals(printer)) {
            job.printerType = PrinterType.BLUETOOTH;
            job.bluetoothAddress = data.getQueryParameter("address");
        } else {
            job.printerType = PrinterType.NYX;
        }
        
        return job;
    }

    private PrintJob parseV1PrintJob(Uri data) {
        PrintJob job = new PrintJob();
        job.protocolVersion = 1;
        
        // V1 protocol: Try Nyx first, but fallback to Bluetooth if Nyx not available
        // This allows V1 deep links to work on both Bitcoinize POS and regular Android phones
        if (isPrinterServiceBound && printerService != null) {
            job.printerType = PrinterType.NYX;
        } else {
            job.printerType = PrinterType.BLUETOOTH;
        }
        
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

    private void executePrintJob(PrintJob job) {
        Log.d(TAG, "executePrintJob: version=" + job.protocolVersion + 
                   ", printerType=" + job.printerType + 
                   ", receiptType=" + job.receiptType);
        showToast("Printing...");
        
        if (job.protocolVersion == 2) {
            if (job.printerType == PrinterType.BLUETOOTH) {
                printRawToBluetooth(job.escposData, job.bluetoothAddress);
            } else {
                printRawToNyx(job.escposData);
            }
        } else {
            // V1 protocol - route to appropriate printer
            if (job.printerType == PrinterType.BLUETOOTH) {
                // Build ESC/POS commands and print to Bluetooth
                if (job.receiptType == ReceiptType.VOUCHER) {
                    Log.d(TAG, "Routing to printVoucherToBluetooth");
                    printVoucherToBluetooth(job.voucherData);
                } else {
                    Log.d(TAG, "Routing to printPaymentToBluetooth");
                    printPaymentToBluetooth(job.paymentData);
                }
            } else {
                // Use Nyx SDK
                if (job.receiptType == ReceiptType.VOUCHER) {
                    Log.d(TAG, "Routing to printVoucherReceipt (Nyx)");
                    printVoucherReceipt(job.voucherData);
                } else {
                    Log.d(TAG, "Routing to printPaymentReceipt (Nyx)");
                    printPaymentReceipt(job.paymentData);
                }
            }
        }
    }

    private void printRawToNyx(byte[] data) {
        executor.submit(() -> {
            try {
                if (printerService != null) {
                    PrintTextFormat f = new PrintTextFormat();
                    f.setAli(1);
                    printerService.printText("V2 not supported on Nyx", f);
                    printerService.paperOut(80);
                }
            } catch (Exception e) {
                Log.e(TAG, "Nyx print error", e);
            }
        });
    }

    private void printRawToBluetooth(byte[] data, String address) {
        executor.submit(() -> {
            try {
                boolean ok = bluetoothPrinterManager != null && 
                             bluetoothPrinterManager.printRaw(data, address);
                mainHandler.post(() -> showToast(ok ? "Printed!" : "Print failed"));
            } catch (Exception e) {
                Log.e(TAG, "BT print error", e);
                mainHandler.post(() -> showToast("Error: " + e.getMessage()));
            }
        });
    }

    /**
     * Print voucher to Bluetooth thermal printer using ESC/POS commands
     */
    private void printVoucherToBluetooth(VoucherData v) {
        executor.submit(() -> {
            try {
                byte[] escposData = buildVoucherEscPos(v);
                boolean ok = bluetoothPrinterManager != null && 
                             bluetoothPrinterManager.printRaw(escposData, null);
                mainHandler.post(() -> showToast(ok ? "Voucher printed!" : "Print failed"));
            } catch (Exception e) {
                Log.e(TAG, "BT voucher print error", e);
                mainHandler.post(() -> showToast("Error: " + e.getMessage()));
            }
        });
    }

    /**
     * Print payment receipt to Bluetooth thermal printer using ESC/POS commands
     */
    private void printPaymentToBluetooth(PaymentData p) {
        executor.submit(() -> {
            try {
                byte[] escposData = buildPaymentEscPos(p);
                boolean ok = bluetoothPrinterManager != null && 
                             bluetoothPrinterManager.printRaw(escposData, null);
                mainHandler.post(() -> showToast(ok ? "Receipt printed!" : "Print failed"));
            } catch (Exception e) {
                Log.e(TAG, "BT payment print error", e);
                mainHandler.post(() -> showToast("Error: " + e.getMessage()));
            }
        });
    }

    /**
     * Build ESC/POS byte array for voucher
     */
    private byte[] buildVoucherEscPos(VoucherData v) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            
            // ESC @ - Initialize printer
            baos.write(new byte[]{0x1B, 0x40});
            
            // ESC a 1 - Center alignment
            baos.write(new byte[]{0x1B, 0x61, 0x01});
            
            // Print Blink logo
            try {
                Bitmap logoBitmap = BitmapFactory.decodeStream(getAssets().open("blink-logo.png"));
                if (logoBitmap != null) {
                    // Scale logo to fit thermal printer (max ~384 pixels wide for 58mm, ~576 for 80mm)
                    int logoWidth = 200;
                    double ratio = (double) logoBitmap.getWidth() / logoBitmap.getHeight();
                    int logoHeight = (int) (logoWidth / ratio);
                    Bitmap scaledLogo = Bitmap.createScaledBitmap(logoBitmap, logoWidth, logoHeight, true);
                    
                    // Flatten transparency onto white background
                    Bitmap flattenedLogo = flattenTransparency(scaledLogo);
                    
                    byte[] logoEscPos = bitmapToEscPos(flattenedLogo);
                    baos.write(logoEscPos);
                    
                    logoBitmap.recycle();
                    scaledLogo.recycle();
                    if (flattenedLogo != scaledLogo) flattenedLogo.recycle();
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not print logo, using text fallback", e);
                // Fallback to text title
                baos.write(new byte[]{0x1B, 0x45, 0x01}); // Bold on
                baos.write(new byte[]{0x1D, 0x21, 0x11}); // Double height+width
                baos.write("BLINK VOUCHER\n".getBytes());
                baos.write(new byte[]{0x1D, 0x21, 0x00}); // Normal size
                baos.write(new byte[]{0x1B, 0x45, 0x00}); // Bold off
            }
            
            baos.write("================================\n".getBytes());
            
            // Voucher details
            if (v.voucherPrice != null && !v.voucherPrice.isEmpty()) {
                baos.write(("Price: " + v.voucherPrice + "\n").getBytes());
            }
            if (v.voucherAmount != null && !v.voucherAmount.isEmpty()) {
                baos.write(("Value: " + v.voucherAmount + "\n").getBytes());
            }
            if (v.identifierCode != null && !v.identifierCode.isEmpty()) {
                baos.write(("ID: " + v.identifierCode + "\n").getBytes());
            }
            if (v.commissionPercentage != null && !v.commissionPercentage.isEmpty() && !"0".equals(v.commissionPercentage)) {
                baos.write(("Commission: " + v.commissionPercentage + "%\n").getBytes());
            }
            if (v.expiresAt != null && !v.expiresAt.isEmpty()) {
                baos.write(("Expires: " + formatExpiry(v.expiresAt) + "\n").getBytes());
            }
            if (v.issuedBy != null && !v.issuedBy.isEmpty()) {
                baos.write(("Issued by: " + v.issuedBy + "\n").getBytes());
            }
            
            baos.write("================================\n".getBytes());
            
            // QR Code for LNURL
            if (v.lnurl != null && !v.lnurl.isEmpty()) {
                // Generate QR code bitmap and convert to ESC/POS
                try {
                    Bitmap qrBitmap = generateQRCode(v.lnurl, 300);
                    if (qrBitmap != null) {
                        byte[] qrEscPos = bitmapToEscPos(qrBitmap);
                        baos.write(qrEscPos);
                        qrBitmap.recycle();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not print QR code", e);
                    baos.write(("LNURL: " + v.lnurl.substring(0, Math.min(30, v.lnurl.length())) + "...\n").getBytes());
                }
            }
            
            baos.write("================================\n".getBytes());
            
            // Voucher secret (emphasized)
            baos.write(new byte[]{0x1B, 0x45, 0x01}); // Bold on
            baos.write("VOUCHER SECRET\n".getBytes());
            baos.write(new byte[]{0x1B, 0x45, 0x00}); // Bold off
            
            if (v.voucherSecret != null) {
                baos.write((formatSecret(v.voucherSecret) + "\n").getBytes());
            }
            
            baos.write("================================\n".getBytes());
            baos.write("blink.sv\n".getBytes());
            
            if (v.memo != null && !v.memo.isEmpty()) {
                baos.write(("\n" + v.memo + "\n").getBytes());
            }
            
            // Feed and cut
            baos.write("\n\n\n\n".getBytes());
            
            return baos.toByteArray();
            
        } catch (Exception e) {
            Log.e(TAG, "Error building voucher ESC/POS", e);
            return new byte[0];
        }
    }

    /**
     * Build ESC/POS byte array for payment receipt
     */
    private byte[] buildPaymentEscPos(PaymentData p) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            
            // ESC @ - Initialize printer
            baos.write(new byte[]{0x1B, 0x40});
            
            // ESC a 1 - Center alignment
            baos.write(new byte[]{0x1B, 0x61, 0x01});
            
            // Print Blink logo
            try {
                Bitmap logoBitmap = BitmapFactory.decodeStream(getAssets().open("blink-logo.png"));
                if (logoBitmap != null) {
                    int logoWidth = 200;
                    double ratio = (double) logoBitmap.getWidth() / logoBitmap.getHeight();
                    int logoHeight = (int) (logoWidth / ratio);
                    Bitmap scaledLogo = Bitmap.createScaledBitmap(logoBitmap, logoWidth, logoHeight, true);
                    
                    // Flatten transparency onto white background
                    Bitmap flattenedLogo = flattenTransparency(scaledLogo);
                    
                    byte[] logoEscPos = bitmapToEscPos(flattenedLogo);
                    baos.write(logoEscPos);
                    
                    logoBitmap.recycle();
                    scaledLogo.recycle();
                    if (flattenedLogo != scaledLogo) flattenedLogo.recycle();
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not print logo, using text fallback", e);
                baos.write(new byte[]{0x1B, 0x45, 0x01}); // Bold on
                baos.write(new byte[]{0x1D, 0x21, 0x11}); // Double height+width
                baos.write("PAYMENT RECEIPT\n".getBytes());
                baos.write(new byte[]{0x1D, 0x21, 0x00}); // Normal size
                baos.write(new byte[]{0x1B, 0x45, 0x00}); // Bold off
            }
            
            baos.write("================================\n".getBytes());
            
            // Payment details
            if (p.username != null && !p.username.isEmpty()) {
                baos.write(("Username: " + p.username + "\n").getBytes());
            }
            if (p.amount != null && !p.amount.isEmpty()) {
                baos.write(("Amount: " + p.amount + "\n").getBytes());
            }
            
            SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            SimpleDateFormat tf = new SimpleDateFormat("HH:mm:ss", Locale.US);
            String date = (p.date != null && !p.date.isEmpty()) ? p.date : df.format(new Date());
            String time = (p.time != null && !p.time.isEmpty()) ? p.time : tf.format(new Date());
            baos.write(("Date: " + date + "\n").getBytes());
            baos.write(("Time: " + time + "\n").getBytes());
            
            baos.write("================================\n".getBytes());
            
            if (p.transactionId != null && !p.transactionId.isEmpty()) {
                baos.write(new byte[]{0x1B, 0x45, 0x01}); // Bold on
                baos.write("Blink Internal ID\n".getBytes());
                baos.write(new byte[]{0x1B, 0x45, 0x00}); // Bold off
                baos.write((p.transactionId + "\n\n").getBytes());
            }
            
            if (p.paymentHash != null && !p.paymentHash.isEmpty()) {
                baos.write(new byte[]{0x1B, 0x45, 0x01}); // Bold on
                baos.write("Payment Hash\n".getBytes());
                baos.write(new byte[]{0x1B, 0x45, 0x00}); // Bold off
                // Break long hash into lines
                String hash = p.paymentHash;
                int lineLen = 32;
                for (int i = 0; i < hash.length(); i += lineLen) {
                    baos.write((hash.substring(i, Math.min(i + lineLen, hash.length())) + "\n").getBytes());
                }
            }
            
            // Feed and cut
            baos.write("\n\n\n\n".getBytes());
            
            return baos.toByteArray();
            
        } catch (Exception e) {
            Log.e(TAG, "Error building payment ESC/POS", e);
            return new byte[0];
        }
    }

    /**
     * Generate QR code bitmap using Android's built-in capabilities
     */
    private Bitmap generateQRCode(String content, int size) {
        try {
            // Use ZXing library if available, otherwise return null
            // For now, we'll use a simple approach with the built-in libraries
            com.google.zxing.qrcode.QRCodeWriter writer = new com.google.zxing.qrcode.QRCodeWriter();
            com.google.zxing.common.BitMatrix bitMatrix = writer.encode(
                content, 
                com.google.zxing.BarcodeFormat.QR_CODE, 
                size, 
                size
            );
            
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    bitmap.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return bitmap;
            
        } catch (Exception e) {
            Log.e(TAG, "QR generation failed", e);
            return null;
        } catch (NoClassDefFoundError e) {
            Log.w(TAG, "ZXing not available for QR generation");
            return null;
        }
    }

    /**
     * Flatten a bitmap with transparency onto a white background
     */
    private Bitmap flattenTransparency(Bitmap source) {
        try {
            // Create a new bitmap with white background
            Bitmap result = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(result);
            
            // Fill with white
            canvas.drawColor(Color.WHITE);
            
            // Draw the source image on top
            canvas.drawBitmap(source, 0, 0, null);
            
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Error flattening transparency", e);
            return source;
        }
    }

    /**
     * Convert a bitmap to ESC/POS raster image commands
     * Handles transparency by treating transparent pixels as white
     */
    private byte[] bitmapToEscPos(Bitmap bitmap) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            
            // Ensure width is multiple of 8
            int printWidth = (width + 7) / 8 * 8;
            
            // GS v 0 - Print raster bit image
            // Format: GS v 0 m xL xH yL yH d1...dk
            // m = 0 (normal), 1 (double width), 2 (double height), 3 (quadruple)
            int xL = (printWidth / 8) & 0xFF;
            int xH = ((printWidth / 8) >> 8) & 0xFF;
            int yL = height & 0xFF;
            int yH = (height >> 8) & 0xFF;
            
            baos.write(new byte[]{0x1D, 0x76, 0x30, 0x00, (byte)xL, (byte)xH, (byte)yL, (byte)yH});
            
            // Convert bitmap to monochrome raster data
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < printWidth; x += 8) {
                    int b = 0;
                    for (int bit = 0; bit < 8; bit++) {
                        int px = x + bit;
                        if (px < width) {
                            int pixel = bitmap.getPixel(px, y);
                            
                            // Extract alpha channel - if transparent, treat as white (don't print)
                            int alpha = Color.alpha(pixel);
                            if (alpha < 128) {
                                // Transparent pixel = white = don't set bit
                                continue;
                            }
                            
                            // For opaque pixels, check if dark enough to print
                            int red = Color.red(pixel);
                            int green = Color.green(pixel);
                            int blue = Color.blue(pixel);
                            int gray = (red + green + blue) / 3;
                            
                            // Dark pixels (gray < 128) should be printed (bit = 1)
                            if (gray < 128) {
                                b |= (0x80 >> bit);
                            }
                        }
                    }
                    baos.write(b);
                }
            }
            
            baos.write('\n');
            return baos.toByteArray();
            
        } catch (Exception e) {
            Log.e(TAG, "Error converting bitmap to ESC/POS", e);
            return new byte[0];
        }
    }

    private void printVoucherReceipt(VoucherData v) {
        Log.d(TAG, "printVoucherReceipt called, printerService=" + (printerService != null));
        
        executor.submit(() -> {
            try {
                if (printerService == null) {
                    Log.e(TAG, "printerService is null in printVoucherReceipt");
                    mainHandler.post(() -> showToast("Printer not ready"));
                    return;
                }
                
                Log.d(TAG, "Starting Nyx voucher print");
                
                PrintTextFormat fmt = new PrintTextFormat();
                fmt.setStyle(1);
                fmt.setTextSize(27);
                fmt.setAli(1);

                printLogo();
                
                String line = "--------------------------------";
                printerService.printText(line, fmt);

                if (v.voucherPrice != null && !v.voucherPrice.isEmpty())
                    printKV("Price:", v.voucherPrice);
                if (v.voucherAmount != null && !v.voucherAmount.isEmpty())
                    printKV("Value:", v.voucherAmount);
                if (v.identifierCode != null && !v.identifierCode.isEmpty())
                    printKV("ID:", v.identifierCode);
                if (v.commissionPercentage != null && !v.commissionPercentage.isEmpty() && !"0".equals(v.commissionPercentage))
                    printKV("Commission:", v.commissionPercentage + "%");
                if (v.expiresAt != null && !v.expiresAt.isEmpty())
                    printKV("Expires:", formatExpiry(v.expiresAt));
                if (v.issuedBy != null && !v.issuedBy.isEmpty())
                    printKV("Issued by:", v.issuedBy);
                
                printerService.printText(line, fmt);

                if (v.lnurl != null && !v.lnurl.isEmpty())
                    printerService.printQrCode(v.lnurl, 350, 350, 1);
                
                printerService.printText(line, fmt);

                PrintTextFormat sf = new PrintTextFormat();
                sf.setAli(1);
                sf.setTextSize(25);
                sf.setStyle(1);
                
                printerService.printText("voucher secret", sf);
                if (v.voucherSecret != null)
                    printerService.printText(formatSecret(v.voucherSecret), sf);

                printerService.printText(line, fmt);
                printerService.printText("blink.sv", sf);
                
                if (v.memo != null && !v.memo.isEmpty()) {
                    printerService.printText("\n", sf);
                    printerService.printText(v.memo, sf);
                }
                
                printerService.printText("\n", sf);
                printerService.paperOut(80);
                
                Log.d(TAG, "Nyx voucher print complete");
                mainHandler.post(() -> showToast("Voucher printed"));
                
            } catch (Exception e) {
                Log.e(TAG, "Voucher print error: " + e.getMessage(), e);
                final String errorMsg = e.getMessage();
                mainHandler.post(() -> showToast("Print failed: " + errorMsg));
            }
        });
    }

    private void printPaymentReceipt(PaymentData p) {
        executor.submit(() -> {
            try {
                if (printerService == null) {
                    mainHandler.post(() -> showToast("Printer not ready"));
                    return;
                }
                
                PrintTextFormat fmt = new PrintTextFormat();
                fmt.setStyle(1);
                fmt.setTextSize(27);
                fmt.setAli(1);

                printLogo();

                String line = "--------------------------------";
                printerService.printText(line, fmt);

                if (p.username != null && !p.username.isEmpty())
                    printKV("Username:", p.username);
                if (p.amount != null && !p.amount.isEmpty())
                    printKV("Amount:", p.amount);
                
                SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                SimpleDateFormat tf = new SimpleDateFormat("HH:mm:ss", Locale.US);
                String date = (p.date != null && !p.date.isEmpty()) ? p.date : df.format(new Date());
                String time = (p.time != null && !p.time.isEmpty()) ? p.time : tf.format(new Date());
                printKV("Date:", date);
                printKV("Time:", time);

                printerService.printText(line, fmt);

                PrintTextFormat txf = new PrintTextFormat();
                txf.setAli(1);
                txf.setTextSize(23);
                txf.setStyle(1);

                if (p.transactionId != null && !p.transactionId.isEmpty()) {
                    printerService.printText("Blink Internal Id", txf);
                    printerService.printText(p.transactionId, txf);
                    printerService.printText("\n", txf);
                }

                if (p.paymentHash != null && !p.paymentHash.isEmpty()) {
                    printerService.printText("Payment Hash", txf);
                    printerService.printText(p.paymentHash, txf);
                    printerService.printText("\n", txf);
                }

                printerService.paperOut(80);
                mainHandler.post(() -> showToast("Receipt printed"));
                
            } catch (Exception e) {
                Log.e(TAG, "Payment print error", e);
                mainHandler.post(() -> showToast("Print failed"));
            }
        });
    }

    private void printLogo() throws IOException, RemoteException {
        try {
            Bitmap bmp = BitmapFactory.decodeStream(getAssets().open("blink-logo.png"));
            int w = 200;
            double r = (double) bmp.getWidth() / bmp.getHeight();
            int h = (int) (w / r);
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, w, h, true);
            printerService.printBitmap(scaled, 1, 1);
            bmp.recycle();
        } catch (Exception e) {
            Log.w(TAG, "Could not print logo", e);
        }
    }

    private void printKV(String k, String v) throws RemoteException {
        PrintTextFormat f = new PrintTextFormat();
        f.setStyle(1);
        f.setTextSize(23);
        int sp = Math.max(1, 32 - k.length() - v.length());
        String pad = new String(new char[sp]).replace("\0", " ");
        printerService.printText(k + pad + v, f);
    }

    private String formatSecret(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i += 4) {
            if (i > 0) sb.append(" ");
            sb.append(s.substring(i, Math.min(i + 4, s.length())));
        }
        return sb.toString();
    }

    private String formatExpiry(String e) {
        try {
            long ts = Long.parseLong(e);
            return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(ts));
        } catch (Exception ex) {
            return e;
        }
    }

    private void bindNyxService() {
        try {
            Intent intent = new Intent();
            intent.setPackage("net.nyx.printerservice");
            intent.setAction("net.nyx.printerservice.IPrinterService");
            bindService(intent, connService, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            Log.w(TAG, "Nyx bind failed", e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (isPrinterServiceBound) {
                unbindService(connService);
                isPrinterServiceBound = false;
            }
        } catch (Exception e) {
            Log.w(TAG, "Unbind error", e);
        }
        try {
            if (bluetoothPrinterManager != null)
                bluetoothPrinterManager.cleanup();
        } catch (Exception e) {
            Log.w(TAG, "Cleanup error", e);
        }
        try {
            if (executor != null)
                executor.shutdown();
        } catch (Exception e) {
            Log.w(TAG, "Executor shutdown error", e);
        }
    }

    enum PrinterType { NYX, BLUETOOTH }
    enum ReceiptType { VOUCHER, PAYMENT }

    static class PrintJob {
        int protocolVersion = 1;
        PrinterType printerType = PrinterType.NYX;
        ReceiptType receiptType = ReceiptType.PAYMENT;
        byte[] escposData;
        VoucherData voucherData;
        PaymentData paymentData;
        String bluetoothAddress;
    }

    static class VoucherData {
        String lnurl, voucherPrice, voucherAmount, voucherSecret;
        String commissionPercentage, identifierCode, expiresAt, issuedBy, memo;
    }

    static class PaymentData {
        String username, amount, paymentHash, transactionId, date, time;
    }
}
