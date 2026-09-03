package com.example.audio;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import com.example.utils.AirLogger;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

public class AudioReceiver {

    private static final String TAG = "AudioReceiver";

    public static final int DEFAULT_SAMPLE_RATE = 48000;
    public static final int FALLBACK_SAMPLE_RATE = 44100;
    public static final byte SYNC_PREAMBLE = (byte) 0xAA;
    public static final byte START_FRAME_DELIMITER = (byte) 0x7E;

    public static final int MAX_STREAM_BUFFER_SIZE = 65536; // 64 KB maximum buffer

    // Handshake command strings
    public static final String CMD_ACTIVATE_RECEIVER = "AIR_CMD:ACTIVATE_RECEIVER";
    public static final String CMD_RECEIVER_READY = "AIR_ACK:RECEIVER_READY";

    private int baudRate = 300; // 150, 300, 600, 1200, 2400
    private int activeSampleRate = DEFAULT_SAMPLE_RATE;
    private final AtomicBoolean isListening = new AtomicBoolean(false);
    private AudioRecord audioRecord;
    private AudioReceiverListener listener;
    private Context context;

    public interface AudioReceiverListener {
        void onByteDecoded(byte b);
        void onFrameDecoded(byte[] frameData);
        void onPayloadDecoded(String payload);
        void onReceiverActivationCommand();
        void onReceiverReadyAckReceived();
        void onError(Exception e);
    }

    public interface AudioDecoderListener {
        void onByteDecoded(byte b);
    }

    public AudioReceiver(AudioDecoderListener legacyListener) {
        this.listener = new AudioReceiverListener() {
            @Override
            public void onByteDecoded(byte b) {
                if (legacyListener != null) legacyListener.onByteDecoded(b);
            }

            @Override
            public void onFrameDecoded(byte[] frameData) {}

            @Override
            public void onPayloadDecoded(String payload) {}

            @Override
            public void onReceiverActivationCommand() {}

            @Override
            public void onReceiverReadyAckReceived() {}

            @Override
            public void onError(Exception e) {}
        };
    }

    public AudioReceiver(AudioReceiverListener listener) {
        this.listener = listener;
    }

    public AudioReceiver(Context context, AudioReceiverListener listener) {
        this.context = context;
        this.listener = listener;
    }

    public void setBaudRate(int baudRate) {
        if (baudRate > 0) {
            this.baudRate = baudRate;
        }
    }

    public int getBaudRate() {
        return baudRate;
    }

    public int getActiveSampleRate() {
        return activeSampleRate;
    }

    public boolean isListening() {
        return isListening.get();
    }

    @SuppressLint("MissingPermission")
    public void startListening() {
        if (isListening.get()) return;

        // Hardware Compatibility Probe Matrix (Supports Samsung, Oppo/ColorOS, Xiaomi, Huawei)
        int[] sampleRates = new int[]{48000, 44100, 16000, 8000};
        
        int[] audioSources = new int[]{
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, // Best source to bypass active in-call muting
                MediaRecorder.AudioSource.MIC,                 // Standard microphone
                MediaRecorder.AudioSource.DEFAULT,
                9,                                             // AudioSource.UNPROCESSED (Direct hardware ADC)
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.CAMCORDER
        };

        boolean initialized = false;

        for (int source : audioSources) {
            for (int rate : sampleRates) {
                try {
                    int minBufferSize = AudioRecord.getMinBufferSize(
                            rate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT
                    );

                    if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                        continue;
                    }

                    int bufferSize = Math.max(minBufferSize * 4, 8192);

                    audioRecord = new AudioRecord(
                            source,
                            rate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bufferSize
                    );

                    if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                        activeSampleRate = rate;
                        initialized = true;
                        AirLogger.i(TAG, "AudioRecord initialized: Source=" + sourceToString(source) +
                                ", Rate=" + rate + " Hz, Baud=" + baudRate);
                        break;
                    } else {
                        audioRecord.release();
                        audioRecord = null;
                    }
                } catch (Exception e) {
                    if (audioRecord != null) {
                        try {
                            audioRecord.release();
                        } catch (Exception ignored) {}
                        audioRecord = null;
                    }
                }
            }
            if (initialized) break;
        }

        if (!initialized || audioRecord == null) {
            AirLogger.e(TAG, "AudioRecord failed to initialize across all hardware configurations.");
            if (listener != null) {
                listener.onError(new IllegalStateException("Microphone hardware probe failed across all sample rates."));
            }
            return;
        }

        try {
            isListening.set(true);
            audioRecord.startRecording();
            AirLogger.i(TAG, "AudioReceiver recording active.");
            new Thread(this::listenLoop).start();
        } catch (Exception e) {
            AirLogger.e(TAG, "Failed starting AudioRecord stream", e);
            if (listener != null) listener.onError(e);
            stopListening();
        }
    }

    private double calculateRmsEnergy(short[] buffer, int readSize) {
        if (readSize <= 0) return 0;
        double sum = 0;
        for (int i = 0; i < readSize; i++) {
            sum += buffer[i] * buffer[i];
        }
        return Math.sqrt(sum / readSize);
    }

    private void listenLoop() {
        // Initialize native GGWave DSP engine
        GGWaveEngine ggwaveEngine = GGWaveEngine.getInstance();
        boolean ggwaveReady = ggwaveEngine.init(activeSampleRate);

        int pcmFrameSize = 1024;
        short[] pcmBuffer = new short[pcmFrameSize];

        double samplesPerBit = (double) activeSampleRate / (double) baudRate;
        int bitSampleLen = Math.max((int) Math.round(samplesPerBit), 1);

        int currentByteAccumulator = 0;
        int bitCount = 0;
        int consecutiveSilenceCount = 0;
        int consecutiveZeroEnergyCount = 0;

        // Frame Detection State Machine
        boolean isLockedOnPreamble = false;
        ByteArrayOutputStream frameBuffer = new ByteArrayOutputStream();
        StringBuilder slidingWindow = new StringBuilder();

        while (isListening.get()) {
            if (audioRecord == null || audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                break;
            }

            int read = audioRecord.read(pcmBuffer, 0, pcmBuffer.length);
            if (read > 0) {
                // 1. Primary DSP Path: GGWave native demodulator
                if (ggwaveReady) {
                    byte[] decodedPayload = ggwaveEngine.decode(pcmBuffer, read);
                    if (decodedPayload != null && decodedPayload.length > 0) {
                        AirLogger.i(TAG, "GGWave native decoder received packet (" + decodedPayload.length + " bytes)");
                        handleDecodedPayload(decodedPayload);
                    }
                }

                // Diagnostic check for zero-energy in-call privacy muting
                double currentRms = calculateRmsEnergy(pcmBuffer, read);
                if (currentRms == 0.0) {
                    consecutiveZeroEnergyCount++;
                    if (consecutiveZeroEnergyCount % 100 == 1) {
                        AirLogger.w(TAG, "DIAGNOSTIC: Zero-energy PCM buffer detected (" 
                                + consecutiveZeroEnergyCount + " frames). Audio source may be muted by OS call privacy filters.");
                    }
                } else {
                    consecutiveZeroEnergyCount = 0;
                }

                // 2. Secondary / FSK DSP Path: Goertzel bit discriminator
                int bitVal = AudioDecoder.detectBit(pcmBuffer, 0, Math.min(read, bitSampleLen), activeSampleRate, baudRate);

                if (bitVal == -1) {
                    consecutiveSilenceCount++;

                    // Deliver accumulated frame on silence interval
                    if (isLockedOnPreamble && frameBuffer.size() > 4 && consecutiveSilenceCount > 25) {
                        byte[] completedFrame = frameBuffer.toByteArray();
                        AirLogger.i(TAG, "FSK frame delivered via silence interval (" + completedFrame.length + " bytes).");
                        handleDecodedPayload(completedFrame);
                        isLockedOnPreamble = false;
                        consecutiveSilenceCount = 0;
                        frameBuffer.reset();
                        slidingWindow.setLength(0);
                    }
                    continue;
                }

                consecutiveSilenceCount = 0;
                currentByteAccumulator = (currentByteAccumulator << 1) | (bitVal & 1);
                bitCount++;

                if (bitCount == 8) {
                    byte completedByte = (byte) (currentByteAccumulator & 0xFF);
                    currentByteAccumulator = 0;
                    bitCount = 0;

                    if (listener != null) {
                        listener.onByteDecoded(completedByte);
                    }

                    char c = (char) (completedByte & 0xFF);
                    if (slidingWindow.length() > 64) {
                        slidingWindow.deleteCharAt(0);
                    }
                    slidingWindow.append(c);
                    String currentWindowStr = slidingWindow.toString();

                    // Check for remote Handshake ACK
                    if (currentWindowStr.contains(CMD_RECEIVER_READY)) {
                        AirLogger.i(TAG, "Acoustic AIR_ACK:RECEIVER_READY detected!");
                        if (listener != null) {
                            listener.onReceiverReadyAckReceived();
                        }
                        slidingWindow.setLength(0);
                        isLockedOnPreamble = false;
                        frameBuffer.reset();
                        continue;
                    }

                    // Check for remote Handshake Command
                    if (currentWindowStr.contains(CMD_ACTIVATE_RECEIVER)) {
                        AirLogger.i(TAG, "Remote ACTIVATE_RECEIVER command detected!");
                        if (listener != null) {
                            listener.onReceiverActivationCommand();
                        }
                        slidingWindow.setLength(0);
                        isLockedOnPreamble = false;
                        frameBuffer.reset();
                        continue;
                    }

                    // Standard 0x7E delimiter lock
                    if (!isLockedOnPreamble) {
                        if (completedByte == START_FRAME_DELIMITER) {
                            isLockedOnPreamble = true;
                            frameBuffer.reset();
                        }
                    } else {
                        if (completedByte == START_FRAME_DELIMITER) {
                            if (frameBuffer.size() > 4) {
                                byte[] completedFrame = frameBuffer.toByteArray();
                                AirLogger.i(TAG, "FSK frame delivered via frame delimiter (" + completedFrame.length + " bytes).");
                                handleDecodedPayload(completedFrame);
                            }
                            frameBuffer.reset();
                        } else {
                            frameBuffer.write(completedByte);
                        }
                    }
                }
            }
        }

        // Flush remaining buffer
        if (frameBuffer.size() > 4) {
            handleDecodedPayload(frameBuffer.toByteArray());
        }
    }

    private void handleDecodedPayload(byte[] payload) {
        if (payload == null || payload.length == 0) return;

        String asText = new String(payload, StandardCharsets.UTF_8);

        if (asText.contains(CMD_RECEIVER_READY)) {
            AirLogger.i(TAG, "Decoded AIR_ACK:RECEIVER_READY");
            if (listener != null) listener.onReceiverReadyAckReceived();
            return;
        }

        if (asText.contains(CMD_ACTIVATE_RECEIVER)) {
            AirLogger.i(TAG, "Decoded AIR_CMD:ACTIVATE_RECEIVER");
            if (listener != null) listener.onReceiverActivationCommand();
            return;
        }

        // Attempt ModulationManager unpack (checks sync byte 0x7E, CRC-16, and GZIP decompression)
        if (context != null) {
            String unpackedText = ModulationManager.getInstance(context).unpackPayload(payload);
            if (unpackedText != null && !unpackedText.isEmpty()) {
                AirLogger.i(TAG, "Successfully unpacked payload via ModulationManager (" + unpackedText.length() + " chars)");
                if (listener != null) {
                    listener.onPayloadDecoded(unpackedText);
                }
            }
        }

        if (listener != null) {
            listener.onFrameDecoded(payload);
        }
    }

    public void stopListening() {
        isListening.set(false);
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
                audioRecord.release();
            } catch (Exception e) {
                AirLogger.e(TAG, "Error releasing AudioRecord", e);
            } finally {
                audioRecord = null;
            }
        }
        AirLogger.i(TAG, "AudioReceiver stopped listening");
    }

    private String sourceToString(int source) {
        switch (source) {
            case MediaRecorder.AudioSource.MIC: return "MIC";
            case MediaRecorder.AudioSource.VOICE_RECOGNITION: return "VOICE_RECOGNITION";
            case MediaRecorder.AudioSource.VOICE_COMMUNICATION: return "VOICE_COMMUNICATION";
            case MediaRecorder.AudioSource.CAMCORDER: return "CAMCORDER";
            case 9: return "UNPROCESSED";
            case MediaRecorder.AudioSource.DEFAULT: return "DEFAULT";
            default: return "SOURCE_" + source;
        }
    }
}