package com.blink.pos.companion;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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
 * 
 * This class provides connectivity to standard Bluetooth SPP (Serial Port Profile)
 * thermal printers like:
 * - Netum (PT-210, etc.)
 * - MUNBYN
 * - GOOJPRT
 * - Epson TM-series (Bluetooth models)
 * - POS-5802, POS-5805
 * - And most other ESC/POS compatible Bluetooth printers
 * 
 * Features:
 * - Auto-discovery of paired Bluetooth printers
 * - Remembers last used printer
 * - Sends raw ESC/POS commands
 * - Connection retry logic
 */
public class BluetoothPrinterManager {
    private static final String TAG = "BluetoothPrinterManager";
    
    // Standard SPP UUID for serial port profile
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    
    // Preferences for storing last used printer
    private static final String PREFS_NAME = "BlinkPrintCompanion";
    private static final String PREF_LAST_PRINTER = "last_bluetooth_printer";
    
    private Context context;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket currentSocket;
    private SharedPreferences prefs;
    
    // Common thermal printer names/patterns for auto-detection
    private static final String[] PRINTER_NAME_PATTERNS = {
        "PT-", "POS-", "MTP-", "RPP", "SPP", "Printer", "PRINT",
        "Thermal", "Receipt", "NETUM", "MUNBYN", "GOOJPRT",
        "BlueTooth Printer", "Bluetooth Printer", "BT Printer",
        "58mm", "80mm", "Mini Printer"
    };

    public BluetoothPrinterManager(Context context) {
        this.context = context;
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Check if Bluetooth is available and enabled
     */
    public boolean isBluetoothAvailable() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    /**
     * Get list of paired Bluetooth devices that look like printers
     */
    public List<BluetoothDevice> getPairedPrinters() {
        List<BluetoothDevice> printers = new ArrayList<>();
        
        if (!isBluetoothAvailable()) {
            Log.w(TAG, "Bluetooth not available");
            return printers;
        }
        
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) 
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Missing BLUETOOTH_CONNECT permission");
            return printers;
        }
        
        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        
        for (BluetoothDevice device : pairedDevices) {
            String name = device.getName();
            if (name != null && isProbablyPrinter(name)) {
                printers.add(device);
                Log.d(TAG, "Found paired printer: " + name + " (" + device.getAddress() + ")");
            }
        }
        
        return printers;
    }

    /**
     * Check if device name looks like a thermal printer
     */
    private boolean isProbablyPrinter(String name) {
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
     * 
     * @param data ESC/POS command bytes
     * @param address Optional specific Bluetooth address. If null, uses last printer or first found.
     * @return true if print was successful
     */
    public boolean printRaw(byte[] data, String address) {
        if (data == null || data.length == 0) {
            Log.e(TAG, "No data to print");
            return false;
        }
        
        if (!isBluetoothAvailable()) {
            Log.e(TAG, "Bluetooth not available");
            return false;
        }
        
        // Find the target printer
        BluetoothDevice printer = findPrinter(address);
        if (printer == null) {
            Log.e(TAG, "No printer found");
            return false;
        }
        
        // Connect and print
        return connectAndPrint(printer, data);
    }

    /**
     * Find a printer by address, or fall back to last used / first available
     */
    private BluetoothDevice findPrinter(String address) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) 
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Missing BLUETOOTH_CONNECT permission");
            return null;
        }
        
        // If specific address provided, use it
        if (address != null && !address.isEmpty()) {
            try {
                BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
                if (device != null) {
                    Log.d(TAG, "Using specified printer: " + address);
                    return device;
                }
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Invalid Bluetooth address: " + address);
            }
        }
        
        // Try last used printer
        String lastPrinter = prefs.getString(PREF_LAST_PRINTER, null);
        if (lastPrinter != null) {
            try {
                BluetoothDevice device = bluetoothAdapter.getRemoteDevice(lastPrinter);
                if (device != null && device.getBondState() == BluetoothDevice.BOND_BONDED) {
                    Log.d(TAG, "Using last printer: " + device.getName());
                    return device;
                }
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Last printer no longer valid");
            }
        }
        
        // Fall back to first paired printer found
        List<BluetoothDevice> printers = getPairedPrinters();
        if (!printers.isEmpty()) {
            BluetoothDevice printer = printers.get(0);
            Log.d(TAG, "Using first available printer: " + printer.getName());
            return printer;
        }
        
        return null;
    }

    /**
     * Connect to printer and send data
     */
    private boolean connectAndPrint(BluetoothDevice printer, byte[] data) {
        BluetoothSocket socket = null;
        OutputStream outputStream = null;
        
        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) 
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            
            Log.d(TAG, "Connecting to printer: " + printer.getName());
            
            // Create socket
            socket = printer.createRfcommSocketToServiceRecord(SPP_UUID);
            
            // Cancel discovery to speed up connection
            bluetoothAdapter.cancelDiscovery();
            
            // Connect with timeout handling
            socket.connect();
            
            Log.d(TAG, "Connected, sending " + data.length + " bytes");
            
            // Get output stream and write data
            outputStream = socket.getOutputStream();
            outputStream.write(data);
            outputStream.flush();
            
            // Remember this printer for next time
            prefs.edit().putString(PREF_LAST_PRINTER, printer.getAddress()).apply();
            
            Log.d(TAG, "Print successful");
            return true;
            
        } catch (IOException e) {
            Log.e(TAG, "Print failed: " + e.getMessage());
            
            // Try fallback connection method for older devices
            return tryFallbackConnection(printer, data);
            
        } finally {
            // Clean up
            try {
                if (outputStream != null) outputStream.close();
                if (socket != null) socket.close();
            } catch (IOException e) {
                Log.w(TAG, "Error closing connection", e);
            }
        }
    }

    /**
     * Fallback connection method using reflection for older Android versions
     */
    private boolean tryFallbackConnection(BluetoothDevice printer, byte[] data) {
        BluetoothSocket socket = null;
        OutputStream outputStream = null;
        
        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) 
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            
            Log.d(TAG, "Trying fallback connection method");
            
            // Use reflection to create insecure socket (works better with some printers)
            socket = (BluetoothSocket) printer.getClass()
                    .getMethod("createRfcommSocket", int.class)
                    .invoke(printer, 1);
            
            socket.connect();
            
            outputStream = socket.getOutputStream();
            outputStream.write(data);
            outputStream.flush();
            
            prefs.edit().putString(PREF_LAST_PRINTER, printer.getAddress()).apply();
            
            Log.d(TAG, "Fallback print successful");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Fallback connection failed: " + e.getMessage());
            return false;
            
        } finally {
            try {
                if (outputStream != null) outputStream.close();
                if (socket != null) socket.close();
            } catch (IOException e) {
                Log.w(TAG, "Error closing fallback connection", e);
            }
        }
    }

    /**
     * Get the address of the last successfully used printer
     */
    public String getLastPrinterAddress() {
        return prefs.getString(PREF_LAST_PRINTER, null);
    }

    /**
     * Clear saved printer preference
     */
    public void forgetLastPrinter() {
        prefs.edit().remove(PREF_LAST_PRINTER).apply();
    }

    /**
     * Cleanup resources
     */
    public void cleanup() {
        if (currentSocket != null) {
            try {
                currentSocket.close();
            } catch (IOException e) {
                Log.w(TAG, "Error closing socket", e);
            }
            currentSocket = null;
        }
    }
}
