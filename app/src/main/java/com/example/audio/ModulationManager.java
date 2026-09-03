package com.example.audio;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Central controller for AirSignal acoustic data transmission.
 * Manages modulation mode switching (FSK vs. GGWave), data compression,
 * packet framing with CRC-16 checksums, and transmission lifecycle.
 */
public class ModulationManager {

    private static final String TAG = "ModulationManager";
    private static final String PREFS_NAME = "airsignal_settings";
    
    public static final String KEY_MODULATION_MODE = "pref_audio_modulation_mode";
    public static final String KEY_BAUD_RATE = "pref_audio_baud_rate";
    public static final String KEY_ENABLE_COMPRESSION = "pref_enable_compression";

    public static final int DEFAULT_BAUD_RATE = 300;
    public static final int PROTOCOL_VERSION = 1;

    public enum Mode {
        FSK,
        GGWAVE
    }

    public interface TransmissionCallback {
        void onProgress(int percent, String status);
        void onComplete();
        void onError(String errorMessage);
    }

    public interface ReceptionCallback {
        void onPayloadReceived(String payload, boolean wasCompressed);
        void onRawBytesReceived(byte[] data);
        void onError(String errorMessage);
    }

    private static volatile ModulationManager instance;
    private final Context context;
    private final SharedPreferences prefs;
    private final Handler mainHandler;

    private Mode currentMode = Mode.FSK;
    private int currentBaudRate = DEFAULT_BAUD_RATE;
    private boolean isCompressEnabled = true;
    private boolean isTransmitting = false;

    private TransmissionCallback transmissionCallback;
    private ReceptionCallback receptionCallback;

    private ModulationManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.mainHandler = new Handler(Looper.getMainLooper());
        loadSettings();
    }

    public static ModulationManager getInstance(Context context) {
        if (instance == null) {
            synchronized (ModulationManager.class) {
                if (instance == null) {
                    instance = new ModulationManager(context);
                }
            }
        }
        return instance;
    }

    public void loadSettings() {
        String modeStr = prefs.getString(KEY_MODULATION_MODE, Mode.FSK.name());
        try {
            this.currentMode = Mode.valueOf(modeStr);
        } catch (IllegalArgumentException e) {
            this.currentMode = Mode.FSK;
        }

        this.currentBaudRate = prefs.getInt(KEY_BAUD_RATE, DEFAULT_BAUD_RATE);
        this.isCompressEnabled = prefs.getBoolean(KEY_ENABLE_COMPRESSION, true);
        Log.d(TAG, "Loaded settings: Mode=" + currentMode + ", Baud=" + currentBaudRate + ", GZip=" + isCompressEnabled);
    }

    public void saveSettings(Mode mode, int baudRate, boolean enableCompression) {
        this.currentMode = mode;
        this.currentBaudRate = baudRate;
        this.isCompressEnabled = enableCompression;

        prefs.edit()
                .putString(KEY_MODULATION_MODE, mode.name())
                .putInt(KEY_BAUD_RATE, baudRate)
                .putBoolean(KEY_ENABLE_COMPRESSION, enableCompression)
                .apply();

        Log.d(TAG, "Saved settings: Mode=" + mode + ", Baud=" + baudRate + ", GZip=" + enableCompression);
    }

    public Mode getMode() {
        return currentMode;
    }

    public void setMode(Mode mode) {
        this.currentMode = mode;
        prefs.edit().putString(KEY_MODULATION_MODE, mode.name()).apply();
    }

    public int getBaudRate() {
        return currentBaudRate;
    }

    public void setBaudRate(int baudRate) {
        this.currentBaudRate = baudRate;
        prefs.edit().putInt(KEY_BAUD_RATE, baudRate).apply();
    }

    public boolean isCompressionEnabled() {
        return isCompressEnabled;
    }

    public void setCompressionEnabled(boolean enabled) {
        this.isCompressEnabled = enabled;
        prefs.edit().putBoolean(KEY_ENABLE_COMPRESSION, enabled).apply();
    }

    public boolean isTransmitting() {
        return isTransmitting;
    }

    public void setTransmissionCallback(TransmissionCallback callback) {
        this.transmissionCallback = callback;
    }

    public void setReceptionCallback(ReceptionCallback callback) {
        this.receptionCallback = callback;
    }

    /**
     * Packages a text or Base64 payload with headers, optional compression, and CRC-16 checksum.
     *
     * Packet Structure:
     * [0]     : Magic Byte (0x7E)
     * [1]     : Protocol Version & Flags (Bit 7: Compressed flag, Bits 0-3: Version)
     * [2..3]  : Payload Length (16-bit Big Endian)
     * [4..N]  : Payload Data (Compressed or Raw UTF-8 bytes)
     * [N+1..N+2]: CRC-16 Checksum (Big Endian)
     */
    public byte[] packPayload(String rawText) {
        if (rawText == null || rawText.isEmpty()) {
            return new byte[0];
        }

        byte[] rawBytes = rawText.getBytes(StandardCharsets.UTF_8);
        byte[] payloadBytes;
        boolean compressed = false;

        if (isCompressEnabled && rawBytes.length > 32) {
            byte[] gzipBytes = compressGzip(rawBytes);
            if (gzipBytes != null && gzipBytes.length < rawBytes.length) {
                payloadBytes = gzipBytes;
                compressed = true;
            } else {
                payloadBytes = rawBytes;
            }
        } else {
            payloadBytes = rawBytes;
        }

        int payloadLength = payloadBytes.length;
        byte[] packet = new byte[1 + 1 + 2 + payloadLength + 2];

        // Header
        packet[0] = 0x7E; // Magic sync byte
        byte flags = (byte) (PROTOCOL_VERSION & 0x0F);
        if (compressed) {
            flags |= (byte) 0x80; // Set compressed bit
        }
        packet[1] = flags;

        // Length (16-bit)
        packet[2] = (byte) ((payloadLength >> 8) & 0xFF);
        packet[3] = (byte) (payloadLength & 0xFF);

        // Payload
        System.arraycopy(payloadBytes, 0, packet, 4, payloadLength);

        // Calculate CRC16 on flags + length + payload
        int crc = calculateCrc16(packet, 1, 3 + payloadLength);
        int crcOffset = 4 + payloadLength;
        packet[crcOffset] = (byte) ((crc >> 8) & 0xFF);
        packet[crcOffset + 1] = (byte) (crc & 0xFF);

        return packet;
    }

    /**
     * Unpacks a received binary packet, verifies CRC-16, and decompresses if needed.
     */
    public String unpackPayload(byte[] packet) {
        if (packet == null || packet.length < 6) {
            notifyReceptionError("Packet too short");
            return null;
        }

        if (packet[0] != 0x7E) {
            notifyReceptionError("Invalid sync byte: " + String.format("0x%02X", packet[0]));
            return null;
        }

        byte flags = packet[1];
        boolean isCompressed = (flags & 0x80) != 0;
        int version = flags & 0x0F;

        if (version != PROTOCOL_VERSION) {
            notifyReceptionError("Unsupported protocol version: " + version);
            return null;
        }

        int payloadLength = ((packet[2] & 0xFF) << 8) | (packet[3] & 0xFF);
        if (packet.length < 4 + payloadLength + 2) {
            notifyReceptionError("Incomplete packet: expected " + (6 + payloadLength) + " bytes, got " + packet.length);
            return null;
        }

        // Verify CRC16
        int expectedCrc = ((packet[4 + payloadLength] & 0xFF) << 8) | (packet[5 + payloadLength] & 0xFF);
        int computedCrc = calculateCrc16(packet, 1, 3 + payloadLength);

        if (expectedCrc != computedCrc) {
            notifyReceptionError("CRC mismatch: expected " + expectedCrc + ", computed " + computedCrc);
            return null;
        }

        byte[] payloadBytes = Arrays.copyOfRange(packet, 4, 4 + payloadLength);
        byte[] finalBytes;

        if (isCompressed) {
            finalBytes = decompressGzip(payloadBytes);
            if (finalBytes == null) {
                notifyReceptionError("GZIP decompression failed");
                return null;
            }
        } else {
            finalBytes = payloadBytes;
        }

        String result = new String(finalBytes, StandardCharsets.UTF_8);
        notifyPayloadReceived(result, isCompressed);
        return result;
    }

    public static byte[] compressGzip(byte[] data) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            gzos.write(data);
            gzos.finish();
            return baos.toByteArray();
        } catch (IOException e) {
            Log.e(TAG, "Compression error", e);
            return null;
        }
    }

    public static byte[] decompressGzip(byte[] compressedData) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compressedData);
             GZIPInputStream gzis = new GZIPInputStream(bais);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int len;
            while ((len = gzis.read(buffer)) > 0) {
                baos.write(buffer, 0, len);
            }
            return baos.toByteArray();
        } catch (IOException e) {
            Log.e(TAG, "Decompression error", e);
            return null;
        }
    }

    public static int calculateCrc16(byte[] data, int offset, int length) {
        int crc = 0xFFFF; // CCITT initial value
        for (int i = offset; i < offset + length; i++) {
            crc ^= (data[i] & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 0x8000) != 0) {
                    crc = (crc << 1) ^ 0x1021; // Polynomial x^16 + x^12 + x^5 + 1
                } else {
                    crc = crc << 1;
                }
                crc &= 0xFFFF;
            }
        }
        return crc;
    }

    public void notifyTransmissionProgress(int percent, String status) {
        isTransmitting = (percent < 100);
        mainHandler.post(() -> {
            if (transmissionCallback != null) {
                transmissionCallback.onProgress(percent, status);
            }
        });
    }

    public void notifyTransmissionComplete() {
        isTransmitting = false;
        mainHandler.post(() -> {
            if (transmissionCallback != null) {
                transmissionCallback.onComplete();
            }
        });
    }

    public void notifyTransmissionError(String error) {
        isTransmitting = false;
        mainHandler.post(() -> {
            if (transmissionCallback != null) {
                transmissionCallback.onError(error);
            }
        });
    }

    public void notifyPayloadReceived(String payload, boolean wasCompressed) {
        mainHandler.post(() -> {
            if (receptionCallback != null) {
                receptionCallback.onPayloadReceived(payload, wasCompressed);
            }
        });
    }

    public void notifyRawBytesReceived(byte[] data) {
        mainHandler.post(() -> {
            if (receptionCallback != null) {
                receptionCallback.onRawBytesReceived(data);
            }
        });
    }

    public void notifyReceptionError(String error) {
        mainHandler.post(() -> {
            if (receptionCallback != null) {
                receptionCallback.onError(error);
            }
        });
    }
}