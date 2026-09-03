package com.example.services;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.telecom.Call;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.example.audio.AudioEncoder;
import com.example.audio.AudioReceiver;
import com.example.audio.ModulationManager;
import com.example.database.TransferDatabase;
import com.example.models.TransferItem;
import com.example.utils.AirLogger;
import com.example.utils.DataPacketManager;
import com.example.utils.FileAssembler;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class AudioTransferService extends Service implements AudioReceiver.AudioReceiverListener {

    private static final String TAG = "AudioTransferService";
    public static final String CHANNEL_ID = "audio_transfer_channel";
    private static final int NOTIFICATION_ID = 202;

    public static final String ACTION_SEND_TOKEN = "com.example.ACTION_SEND_TOKEN";
    public static final String ACTION_SEND_PHONETIC_IMAGE = "com.example.ACTION_SEND_PHONETIC_IMAGE";
    public static final String ACTION_SEND_RAW_BINARY = "com.example.ACTION_SEND_RAW_BINARY";
    public static final String ACTION_SEND_BINARY_FILE = "com.example.ACTION_SEND_BINARY_FILE";
    public static final String ACTION_SEND_AUDIO_DATA = "com.example.ACTION_SEND_AUDIO_DATA";
    public static final String ACTION_CALL_ACTIVE = "com.example.ACTION_CALL_ACTIVE";
    public static final String ACTION_EXECUTE_STAGED_TRANSMISSION = "com.example.ACTION_EXECUTE_STAGED_TRANSMISSION";
    public static final String ACTION_RECEIVER_MODE_ACTIVE = "com.example.ACTION_RECEIVER_MODE_ACTIVE";
    public static final String ACTION_STOP_SERVICE = "com.example.ACTION_STOP_SERVICE";
    public static final String ACTION_START_LOCAL_RECEIVER = "com.example.ACTION_START_LOCAL_RECEIVER";
    public static final String ACTION_SEND_LOCAL_PHONETIC = "com.example.ACTION_SEND_LOCAL_PHONETIC";

    public static final String EXTRA_TOKEN_PAYLOAD = "extra_token_payload";
    public static final String EXTRA_IMAGE_PATH = "extra_image_path";
    public static final String EXTRA_BINARY_FILE_PATH = "extra_binary_file_path";
    public static final String EXTRA_FILE_PATH = "extra_file_path";
    public static final String EXTRA_FILE_NAME = "extra_file_name";
    public static final String EXTRA_FILE_SIZE = "extra_file_size";
    public static final String EXTRA_FILE_ID = "extra_file_id";

    private static class StagedPayload {
        String action;
        byte[] rawPayloadBytes;
        String filePath;
        String fileName;
        long fileSize;
        String fileId;
    }

    private static StagedPayload stagedPayload = null;

    private AudioReceiver audioReceiver;
    private AudioEncoder audioEncoder;
    private NotificationManager notificationManager;
    private NotificationCompat.Builder notificationBuilder;
    private PowerManager.WakeLock wakeLock;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isListening = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        AirLogger.i(TAG, "Initializing AudioTransferService");

        createNotificationChannel();

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AirSignal::AudioTransferWakeLock");
        }

        int configuredBaud = ModulationManager.getInstance(this).getBaudRate();
        ModulationManager.Mode configuredMode = ModulationManager.getInstance(this).getMode();

        audioEncoder = new AudioEncoder(configuredBaud, configuredMode);
        audioReceiver = new AudioReceiver(this, this);
        audioReceiver.setBaudRate(configuredBaud);

        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("AirSignal Audio Data Mode Active")
                .setContentText("Awaiting transmission...")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true);

        startForeground(NOTIFICATION_ID, notificationBuilder.build());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(60 * 60 * 1000L /* 1 hour max */);
        }

        if (intent == null) {
            return START_STICKY;
        }

        String action = intent.getAction();
        AirLogger.i(TAG, "onStartCommand received action: " + action);

        if (ACTION_STOP_SERVICE.equals(action)) {
            stagedPayload = null;
            stopSelf();
            return START_NOT_STICKY;
        }

        // Standalone Local Receiver Mode
        if (ACTION_START_LOCAL_RECEIVER.equals(action)) {
            AirLogger.i(TAG, "Starting Standalone Local Receiver Mode");
            ensureAudioRoutingAndListening();
            updateNotification("Local Receiver Active: Listening for nearby sound...", 0);
            return START_STICKY;
        }

        // Standalone Local Transmission
        if (ACTION_SEND_LOCAL_PHONETIC.equals(action)) {
            String imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH);
            String fileName = intent.getStringExtra(EXTRA_FILE_NAME);
            long fileSize = intent.getLongExtra(EXTRA_FILE_SIZE, 0);

            executeLocalAudioTransmission(imagePath, fileName, fileSize);
            return START_STICKY;
        }

        // Call transitioned to ACTIVE: Engage receiver listening
        if (ACTION_CALL_ACTIVE.equals(action)) {
            ensureAudioRoutingAndListening();
            if (stagedPayload != null) {
                updateNotification("Call connected. Awaiting receiver handshake or tap transmit...", 0);
            } else {
                mainHandler.postDelayed(() -> {
                    ensureAudioRoutingAndListening();
                    audioEncoder.transmitReceiverReadyAck(null);
                }, 600);
            }
            return START_STICKY;
        }

        // Explicit user trigger from InCallActivity screen
        if (ACTION_EXECUTE_STAGED_TRANSMISSION.equals(action)) {
            AirLogger.i(TAG, "User triggered ACTION_EXECUTE_STAGED_TRANSMISSION from in-call screen.");
            ensureAudioRoutingAndListening();
            executeStagedPayloadIfPresent();
            return START_STICKY;
        }

        // Outbound transfer actions
        if (ACTION_SEND_TOKEN.equals(action)
                || ACTION_SEND_PHONETIC_IMAGE.equals(action)
                || ACTION_SEND_BINARY_FILE.equals(action)
                || ACTION_SEND_AUDIO_DATA.equals(action)
                || ACTION_SEND_RAW_BINARY.equals(action)) {

            StagedPayload payload = new StagedPayload();
            payload.action = action;
            payload.rawPayloadBytes = intent.getByteArrayExtra(EXTRA_TOKEN_PAYLOAD);
            payload.filePath = intent.getStringExtra(EXTRA_IMAGE_PATH);
            if (payload.filePath == null) {
                payload.filePath = intent.getStringExtra(EXTRA_FILE_PATH);
            }
            if (payload.filePath == null) {
                payload.filePath = intent.getStringExtra(EXTRA_BINARY_FILE_PATH);
            }
            payload.fileName = intent.getStringExtra(EXTRA_FILE_NAME);
            payload.fileSize = intent.getLongExtra(EXTRA_FILE_SIZE, 0);
            payload.fileId = intent.getStringExtra(EXTRA_FILE_ID);

            stagedPayload = payload;

            Call activeCall = AirSignalInCallService.getActiveCall();
            if (activeCall != null && activeCall.getState() == Call.STATE_ACTIVE) {
                AirLogger.i(TAG, "Call is active. Payload staged for immediate in-call execution.");
                ensureAudioRoutingAndListening();
                executeStagedPayloadIfPresent();
            } else {
                AirLogger.i(TAG, "Call is dialing/ringing. Payload staged safely.");
                updateNotification("Payload staged. Waiting for call connection...", 0);
            }
            return START_STICKY;
        }

        ensureAudioRoutingAndListening();
        return START_STICKY;
    }

    private void executeLocalAudioTransmission(final String filePath, final String fileName, final long fileSize) {
        new Thread(() -> {
            AirLogger.i(TAG, "Starting Local Acoustic Transmission sequence...");

            // 3-second delay to position phones
            for (int sec = 3; sec > 0; sec--) {
                final int s = sec;
                mainHandler.post(() -> updateNotification("Position phones nearby. Starting in " + s + "s...", (3 - s) * 33));
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {}
            }

            mainHandler.post(() -> {
                ensureAudioRoutingAndListening();
                transmitBinaryFileInternal(filePath, fileName, fileSize, "LOCAL_" + System.currentTimeMillis());
            });
        }).start();
    }

    private void ensureAudioRoutingAndListening() {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            try {
                if (!audioManager.isSpeakerphoneOn()) {
                    audioManager.setSpeakerphoneOn(true);
                    AirLogger.i(TAG, "Speakerphone set to TRUE for acoustic coupling");
                }

                int maxCallVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxCallVol, 0);

                int maxMusicVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusicVol, 0);
            } catch (Exception e) {
                AirLogger.e(TAG, "Error configuring AudioManager routing parameters", e);
            }
        }

        if (!isListening && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            audioReceiver.startListening();
            isListening = true;
            updateNotification("Active. Listening for audio data...", 0);
        }
    }

    private void executeStagedPayloadIfPresent() {
        if (stagedPayload == null) {
            return;
        }

        final StagedPayload payload = stagedPayload;
        stagedPayload = null;

        mainHandler.postDelayed(() -> {
            ensureAudioRoutingAndListening();
            updateNotification("Transmitting data into call stream...", 0);

            Intent startBroadcast = new Intent(FileAssembler.ACTION_TRANSFER_PROGRESS);
            startBroadcast.putExtra(FileAssembler.EXTRA_STATUS, "TRANSFERRING");
            sendBroadcast(startBroadcast);

            if (payload.rawPayloadBytes != null && payload.rawPayloadBytes.length > 0) {
                transmitRawBytesInternal(payload.rawPayloadBytes, payload.fileName);
            } else {
                transmitBinaryFileInternal(payload.filePath, payload.fileName, payload.fileSize, payload.fileId);
            }
        }, 500);
    }

    private void transmitRawBytesInternal(byte[] bytes, String fileName) {
        updateNotification("Transmitting audio payload...", 10);

        ModulationManager.Mode currentMode = ModulationManager.getInstance(this).getMode();
        audioEncoder.setModulationMode(currentMode);
        audioEncoder.setBaudRate(ModulationManager.getInstance(this).getBaudRate());

        audioEncoder.transmitDataOverAudio(bytes, new AudioEncoder.OnTransmissionProgressListener() {
            @Override
            public void onProgress(int currentPacket, int totalPackets, int percent) {
                updateNotification("Transmitting data: " + percent + "%", percent);
            }

            @Override
            public void onComplete() {
                AirLogger.i(TAG, "Raw payload transmission completed successfully.");
                updateNotification("Data Transmitted Successfully!", 100);

                Intent completeBroadcast = new Intent(FileAssembler.ACTION_TRANSFER_PROGRESS);
                completeBroadcast.putExtra(FileAssembler.EXTRA_STATUS, "COMPLETED");
                sendBroadcast(completeBroadcast);

                mainHandler.postDelayed(() -> updateNotification("Listening for incoming data...", 0), 3000);
            }

            @Override
            public void onError(Exception e) {
                AirLogger.e(TAG, "Data transmission error", e);
                updateNotification("Transmission Error: " + e.getMessage(), 0);
            }
        });
    }

    private void transmitBinaryFileInternal(String filePath, String fileName, long fileSize, String fileId) {
        if (filePath == null) {
            byte[] dummyData = "AirSignal Audio Data Transmission".getBytes(StandardCharsets.UTF_8);
            transmitRawBytesInternal(dummyData, "message.txt");
            return;
        }

        File file = new File(filePath);
        if (!file.exists()) {
            AirLogger.e(TAG, "Binary file does not exist: " + filePath);
            return;
        }

        byte[] fileBytes = readFileBytes(file);
        if (fileBytes == null || fileBytes.length == 0) {
            AirLogger.e(TAG, "Binary file read 0 bytes");
            return;
        }

        List<byte[]> binaryPackets = DataPacketManager.createBinaryPackets(fileBytes);
        final String effectiveFileId = (fileId != null) ? fileId : ("FILE_" + System.currentTimeMillis());
        final int totalPackets = binaryPackets.size();

        ModulationManager.Mode currentMode = ModulationManager.getInstance(this).getMode();
        audioEncoder.setModulationMode(currentMode);
        audioEncoder.setBaudRate(ModulationManager.getInstance(this).getBaudRate());

        updateNotification("Streaming " + totalPackets + " audio packets...", 0);

        audioEncoder.transmitRawStream(binaryPackets, currentMode, new AudioEncoder.OnTransmissionProgressListener() {
            @Override
            public void onProgress(int currentPacket, int total, int percent) {
                updateNotification("Sending: " + percent + "% (" + currentPacket + "/" + total + ")", percent);

                TransferDatabase db = TransferDatabase.getInstance(getApplicationContext());
                TransferItem item = new TransferItem(
                        effectiveFileId,
                        fileName != null ? fileName : file.getName(),
                        fileSize > 0 ? fileSize : file.length(),
                        percent,
                        "TRANSFERRING",
                        currentMode.name(),
                        total,
                        currentPacket
                );
                db.insertTransfer(item);

                Intent broadcast = new Intent(FileAssembler.ACTION_TRANSFER_PROGRESS);
                broadcast.putExtra(FileAssembler.EXTRA_STATUS, "TRANSFERRING");
                sendBroadcast(broadcast);
            }

            @Override
            public void onComplete() {
                AirLogger.i(TAG, "Binary stream completed successfully.");
                updateNotification("File Sent Successfully!", 100);

                TransferDatabase db = TransferDatabase.getInstance(getApplicationContext());
                TransferItem item = new TransferItem(
                        effectiveFileId,
                        fileName != null ? fileName : file.getName(),
                        fileSize > 0 ? fileSize : file.length(),
                        100,
                        TransferItem.STATUS_COMPLETED,
                        currentMode.name(),
                        totalPackets,
                        totalPackets
                );
                db.insertTransfer(item);

                Intent broadcast = new Intent(FileAssembler.ACTION_TRANSFER_PROGRESS);
                broadcast.putExtra(FileAssembler.EXTRA_STATUS, "COMPLETED");
                sendBroadcast(broadcast);

                mainHandler.postDelayed(() -> updateNotification("Listening for incoming data...", 0), 3000);
            }

            @Override
            public void onError(Exception e) {
                AirLogger.e(TAG, "Binary stream error", e);
                updateNotification("Stream Error: " + e.getMessage(), 0);
            }
        });
    }

    private byte[] readFileBytes(File file) {
        try (InputStream is = new FileInputStream(file);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = is.read(buf)) != -1) {
                bos.write(buf, 0, r);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            AirLogger.e(TAG, "Error reading file into byte array", e);
            return null;
        }
    }

    private void updateNotification(String text, int progress) {
        if (notificationBuilder != null && notificationManager != null) {
            notificationBuilder.setContentText(text);
            if (progress > 0 && progress <= 100) {
                notificationBuilder.setProgress(100, progress, false);
            } else {
                notificationBuilder.setProgress(0, 0, false);
            }
            notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
        }
    }

    @Override
    public void onDestroy() {
        AirLogger.i(TAG, "Destroying AudioTransferService");

        if (audioReceiver != null) {
            audioReceiver.stopListening();
            isListening = false;
        }

        if (audioEncoder != null) {
            audioEncoder.cancelTransmission();
        }

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }

        stagedPayload = null;
        super.onDestroy();
    }

    // =========================================================================
    // AudioReceiver Callback Handlers
    // =========================================================================

    @Override
    public void onByteDecoded(byte b) {
        // Raw byte received
    }

    @Override
    public void onReceiverReadyAckReceived() {
        AirLogger.i(TAG, "Received AIR_ACK:RECEIVER_READY from remote receiver!");
        if (stagedPayload != null) {
            ensureAudioRoutingAndListening();
            executeStagedPayloadIfPresent();
        }
    }

    @Override
    public void onReceiverActivationCommand() {
        AirLogger.i(TAG, "Remote ACTIVATE_RECEIVER signal received! Engaging Receiver Mode.");
        updateNotification("Receiver Mode Active: Incoming Audio Transfer...", 10);

        Intent broadcast = new Intent(ACTION_RECEIVER_MODE_ACTIVE);
        sendBroadcast(broadcast);
    }

    @Override
    public void onFrameDecoded(byte[] frameData) {
        if (frameData == null || frameData.length == 0) return;

        AirLogger.i(TAG, "Received raw binary frame (" + frameData.length + " bytes). Passing to Assembler.");
        FileAssembler.processIncomingBinaryFrame(getApplicationContext(), frameData);
    }

    @Override
    public void onPayloadDecoded(String payload) {
        if (payload == null || payload.isEmpty()) return;

        AirLogger.i(TAG, "Received decoded payload string (" + payload.length() + " chars)");
        updateNotification("Received Payload: " + payload.substring(0, Math.min(20, payload.length())) + "...", 100);

        try {
            File receivedDir = FileAssembler.getReceivedFilesDir(getApplicationContext());
            File textFile = new File(receivedDir, "received_message_" + System.currentTimeMillis() + ".txt");
            try (FileOutputStream fos = new FileOutputStream(textFile)) {
                fos.write(payload.getBytes(StandardCharsets.UTF_8));
            }

            TransferDatabase db = TransferDatabase.getInstance(getApplicationContext());
            TransferItem item = new TransferItem(
                    "MSG_" + System.currentTimeMillis(),
                    textFile.getName(),
                    textFile.length(),
                    100,
                    TransferItem.STATUS_COMPLETED,
                    "AUDIO_PAYLOAD",
                    1,
                    1
            );
            db.insertTransfer(item);

            Intent completeBroadcast = new Intent(FileAssembler.ACTION_TRANSFER_PROGRESS);
            completeBroadcast.putExtra(FileAssembler.EXTRA_STATUS, "COMPLETED");
            sendBroadcast(completeBroadcast);
        } catch (Exception e) {
            AirLogger.e(TAG, "Error saving decoded payload text", e);
        }
    }

    @Override
    public void onError(Exception e) {
        AirLogger.e(TAG, "AudioReceiver encountered error", e);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "AirSignal Audio Data Channel",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Maintains CPU wake locks and streams audio modem data.");
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}