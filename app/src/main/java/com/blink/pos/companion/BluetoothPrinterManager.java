package com.blink.pos.companion;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * BluetoothPrinterManager - Handles printing to generic Bluetooth thermal printers
 */
public class BluetoothPrinterManager {
    private static final String TAG = "BluetoothPrinterManager";
    
    // Standard SPP UUID for serial port profile
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    
    private static final String PREFS_NAME = "BlinkPrintCompanion";
    private static final String PREF_LAST_PRINTER = "last_bluetooth_printer";
    
    private Context context;
    private BluetoothAdapter bluetoothAdapter;
    private SharedPreferences prefs;
    
    // Common thermal printer names/patterns
    private static final String[] PRINTER_NAME_PATTERNS = {
        "PT-", "POS-", "MTP-", "RPP", "SPP", "Printer", "PRINT",
        "Thermal", "Receipt", "NETUM", "MUNBYN", "GOOJPRT",
        "BlueTooth Printer", "Bluetooth Printer", "BT Printer",
        "58mm", "80mm", "Mini Printer",
        "MP-", "MP5", "MP583"  // Mobile printer patterns
    };

    public BluetoothPrinterManager(Context context) {
        this.context = context;
        try {
            this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        } catch (Exception e) {
            Log.e(TAG, "Failed to get BluetoothAdapter", e);
            this.bluetoothAdapter = null;
        }
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public boolean isBluetoothAvailable() {
        try {
            return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
        } catch (Exception e) {
            Log.e(TAG, "Error checking Bluetooth", e);
            return false;
        }
    }

    public List<BluetoothDevice> getPairedPrinters() {
        List<BluetoothDevice> printers = new ArrayList<>();
        
        try {
            if (!isBluetoothAvailable()) {
                Log.w(TAG, "Bluetooth not available");
                return printers;
            }
            
            if (!hasBluetoothPermission()) {
                Log.w(TAG, "Missing BLUETOOTH_CONNECT permission");
                return printers;
            }
            
            Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
            if (pairedDevices == null) {
                return printers;
            }
            
            for (BluetoothDevice device : pairedDevices) {
                try {
                    String name = device.getName();
                    if (name != null && isProbablyPrinter(name)) {
                        printers.add(device);
                        Log.d(TAG, "Found printer: " + name + " (" + device.getAddress() + ")");
                    }
                } catch (SecurityException e) {
                    Log.w(TAG, "Permission error getting device name", e);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting paired printers", e);
        }
        
        return printers;
    }
    
    private boolean hasBluetoothPermission() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) 
                        == PackageManager.PERMISSION_GRANTED;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isProbablyPrinter(String name) {
        if (name == null) return false;
        String upperName = name.toUpperCase();
        for (String pattern : PRINTER_NAME_PATTERNS) {
            if (upperName.contains(pattern.toUpperCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Print raw ESC/POS data to a Bluetooth printer
     * Will try multiple printers if the first one fails
     */
    public boolean printRaw(byte[] data, String address) {
        Log.d(TAG, "printRaw called, data size: " + (data != null ? data.length : 0));
        
        if (data == null || data.length == 0) {
            Log.e(TAG, "No data to print");
            return false;
        }
        
        if (!isBluetoothAvailable()) {
            Log.e(TAG, "Bluetooth not available");
            return false;
        }
        
        if (!hasBluetoothPermission()) {
            Log.e(TAG, "No Bluetooth permission");
            return false;
        }
        
        // Cancel any ongoing discovery
        try {
            bluetoothAdapter.cancelDiscovery();
        } catch (Exception e) {
            Log.w(TAG, "Could not cancel discovery", e);
        }
        
        // Build list of printers to try
        List<BluetoothDevice> printersToTry = new ArrayList<>();
        
        // If specific address provided, try it first
        if (address != null && !address.isEmpty()) {
            try {
                BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
                if (device != null) {
                    printersToTry.add(device);
                    Log.d(TAG, "Will try specified printer first: " + address);
                }
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Invalid address: " + address);
            }
        }
        
        // Add last used printer if not already in list
        String lastPrinter = prefs.getString(PREF_LAST_PRINTER, null);
        if (lastPrinter != null && !lastPrinter.equals(address)) {
            try {
                BluetoothDevice device = bluetoothAdapter.getRemoteDevice(lastPrinter);
                if (device != null && device.getBondState() == BluetoothDevice.BOND_BONDED) {
                    printersToTry.add(device);
                    Log.d(TAG, "Will try last printer: " + lastPrinter);
                }
            } catch (Exception e) {
                Log.w(TAG, "Last printer not valid", e);
            }
        }
        
        // Add all other paired printers
        List<BluetoothDevice> allPrinters = getPairedPrinters();
        for (BluetoothDevice p : allPrinters) {
            boolean alreadyInList = false;
            for (BluetoothDevice existing : printersToTry) {
                if (existing.getAddress().equals(p.getAddress())) {
                    alreadyInList = true;
                    break;
                }
            }
            if (!alreadyInList) {
                printersToTry.add(p);
            }
        }
        
        if (printersToTry.isEmpty()) {
            Log.e(TAG, "No printers found");
            return false;
        }
        
        Log.d(TAG, "Will try " + printersToTry.size() + " printer(s)");
        
        // Try each printer until one works
        for (BluetoothDevice printer : printersToTry) {
            String printerName = "unknown";
            try {
                printerName = printer.getName();
            } catch (SecurityException e) {
                // ignore
            }
            
            Log.d(TAG, "Trying printer: " + printerName + " (" + printer.getAddress() + ")");
            
            boolean success = tryPrintToDevice(printer, data);
            
            if (success) {
                Log.d(TAG, "Print successful to: " + printerName);
                // Save this as the last working printer
                prefs.edit().putString(PREF_LAST_PRINTER, printer.getAddress()).apply();
                return true;
            }
            
            Log.d(TAG, "Failed to print to " + printerName + ", trying next...");
            
            // Wait before trying next printer
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                // ignore
            }
        }
        
        Log.e(TAG, "All printers failed");
        return false;
    }
    
    /**
     * Try to print to a specific device using all connection methods
     */
    private boolean tryPrintToDevice(BluetoothDevice printer, byte[] data) {
        // Small delay to let any previous connection fully close
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            // ignore
        }
        
        // Try primary connection method (SPP UUID)
        boolean success = connectAndPrint(printer, data);
        
        if (!success) {
            Log.d(TAG, "Primary method failed, trying fallback channel 1...");
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                // ignore
            }
            success = tryFallbackConnection(printer, data, 1);
        }
        
        if (!success) {
            Log.d(TAG, "Channel 1 failed, trying channel 2...");
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                // ignore
            }
            success = tryFallbackConnection(printer, data, 2);
        }
        
        if (!success) {
            Log.d(TAG, "Channel 2 failed, trying channel 3...");
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                // ignore
            }
            success = tryFallbackConnection(printer, data, 3);
        }
        
        if (!success) {
            Log.d(TAG, "All secure methods failed, trying insecure connection...");
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                // ignore
            }
            success = tryInsecureConnection(printer, data);
        }
        
        return success;
    }

    private BluetoothDevice findPrinter(String address) {
        try {
            // If specific address provided, use it
            if (address != null && !address.isEmpty()) {
                try {
                    BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
                    if (device != null) {
                        Log.d(TAG, "Using specified printer: " + address);
                        return device;
                    }
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "Invalid address: " + address);
                }
            }
            
            // Try last used printer
            String lastPrinter = prefs.getString(PREF_LAST_PRINTER, null);
            if (lastPrinter != null) {
                try {
                    BluetoothDevice device = bluetoothAdapter.getRemoteDevice(lastPrinter);
                    if (device != null && device.getBondState() == BluetoothDevice.BOND_BONDED) {
                        Log.d(TAG, "Using last printer: " + lastPrinter);
                        return device;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Last printer not valid", e);
                }
            }
            
            // Fall back to first paired printer
            List<BluetoothDevice> printers = getPairedPrinters();
            if (!printers.isEmpty()) {
                BluetoothDevice printer = printers.get(0);
                Log.d(TAG, "Using first available: " + printer.getName());
                return printer;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error finding printer", e);
        }
        
        return null;
    }

    /**
     * Primary connection method using createRfcommSocketToServiceRecord
     */
    private boolean connectAndPrint(BluetoothDevice printer, byte[] data) {
        BluetoothSocket socket = null;
        OutputStream outputStream = null;
        
        try {
            String printerName = "unknown";
            try {
                printerName = printer.getName();
            } catch (SecurityException e) {
                // ignore
            }
            Log.d(TAG, "Connecting to: " + printerName);
            
            socket = printer.createRfcommSocketToServiceRecord(SPP_UUID);
            socket.connect();
            
            Log.d(TAG, "Connected, sending " + data.length + " bytes");
            
            outputStream = socket.getOutputStream();
            outputStream.write(data);
            outputStream.flush();
            
            // Wait for data to be sent
            Thread.sleep(300);
            
            Log.d(TAG, "Print successful (primary)");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Primary connection failed: " + e.getMessage());
            return false;
            
        } finally {
            closeQuietly(outputStream);
            closeSocketQuietly(socket);
        }
    }

    /**
     * Fallback using reflection method with specified channel
     */
    private boolean tryFallbackConnection(BluetoothDevice printer, byte[] data, int channel) {
        BluetoothSocket socket = null;
        OutputStream outputStream = null;
        
        try {
            Log.d(TAG, "Trying fallback (reflection channel " + channel + ")");
            
            socket = (BluetoothSocket) printer.getClass()
                    .getMethod("createRfcommSocket", int.class)
                    .invoke(printer, channel);
            
            socket.connect();
            
            outputStream = socket.getOutputStream();
            outputStream.write(data);
            outputStream.flush();
            
            Thread.sleep(300);
            
            Log.d(TAG, "Print successful (fallback channel " + channel + ")");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Fallback channel " + channel + " failed: " + e.getMessage());
            return false;
            
        } finally {
            closeQuietly(outputStream);
            closeSocketQuietly(socket);
        }
    }
    
    /**
     * Try insecure RFCOMM connection
     */
    private boolean tryInsecureConnection(BluetoothDevice printer, byte[] data) {
        BluetoothSocket socket = null;
        OutputStream outputStream = null;
        
        try {
            Log.d(TAG, "Trying insecure connection");
            
            socket = printer.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
            socket.connect();
            
            outputStream = socket.getOutputStream();
            outputStream.write(data);
            outputStream.flush();
            
            Thread.sleep(300);
            
            Log.d(TAG, "Print successful (insecure)");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Insecure connection failed: " + e.getMessage());
            return false;
            
        } finally {
            closeQuietly(outputStream);
            closeSocketQuietly(socket);
        }
    }
    
    private void closeQuietly(OutputStream os) {
        if (os != null) {
            try {
                os.close();
            } catch (IOException e) {
                Log.w(TAG, "Error closing output stream", e);
            }
        }
    }
    
    private void closeSocketQuietly(BluetoothSocket socket) {
        if (socket != null) {
            try {
                // Give the output buffer time to flush
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // ignore
            }
            try {
                socket.close();
                Log.d(TAG, "Socket closed");
            } catch (IOException e) {
                Log.w(TAG, "Error closing socket", e);
            }
        }
    }

    public String getLastPrinterAddress() {
        return prefs.getString(PREF_LAST_PRINTER, null);
    }

    public void forgetLastPrinter() {
        prefs.edit().remove(PREF_LAST_PRINTER).apply();
    }

    public void cleanup() {
        // Nothing to clean up - we close sockets immediately after use
        Log.d(TAG, "cleanup called");
    }
}
