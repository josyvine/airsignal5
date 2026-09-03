package com.example.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import com.example.services.AirSignalInCallService;
import com.example.utils.AirLogger;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class AudioEncoder {

    private static final String TAG = "AudioEncoder";

    public static final int SAMPLE_RATE = 48000;
    public static final int FALLBACK_SAMPLE_RATE = 44100;

    // Bell 202 Frequencies (1200 Baud)
    public static final int BELL202_MARK_FREQ = 1200;   // Bit 1 (Hz)
    public static final int BELL202_SPACE_FREQ = 2200;  // Bit 0 (Hz)

    // Bell 103 Frequencies (300 Baud - Telecom / Cellular Voice Call Standard)
    public static final int BELL103_MARK_FREQ = 1270;   // Bit 1 (Hz)
    public static final int BELL103_SPACE_FREQ = 1070;  // Bit 0 (Hz)

    public static final byte SYNC_PREAMBLE = (byte) 0xAA;
    public static final byte START_FRAME_DELIMITER = (byte) 0x7E;

    // Handshake command strings for automated synchronization
    public static final String CMD_ACTIVATE_RECEIVER = "AIR_CMD:ACTIVATE_RECEIVER";
    public static final String CMD_RECEIVER_READY = "AIR_ACK:RECEIVER_READY";

    private int baudRate = 300; // 150, 300, 600, 1200, 2400
    private ModulationManager.Mode modulationMode = ModulationManager.Mode.FSK;
    private final AtomicBoolean isTransmitting = new AtomicBoolean(false);
    private AudioTrack activeAudioTrack;

    public interface OnTransmissionProgressListener {
        void onProgress(int currentPacket, int totalPackets, int percent);
        void onComplete();
        void onError(Exception e);
    }

    public AudioEncoder(int baudRate) {
        setBaudRate(baudRate);
    }

    public AudioEncoder(int baudRate, ModulationManager.Mode mode) {
        setBaudRate(baudRate);
        this.modulationMode = mode;
    }

    public void setBaudRate(int baudRate) {
        if (baudRate <= 0) {
            this.baudRate = 300;
        } else {
            this.baudRate = baudRate;
        }
    }

    public int getBaudRate() {
        return baudRate;
    }

    public void setModulationMode(ModulationManager.Mode mode) {
        this.modulationMode = mode;
    }

    public ModulationManager.Mode getModulationMode() {
        return modulationMode;
    }

    public boolean isTransmitting() {
        return isTransmitting.get();
    }

    public void cancelTransmission() {
        isTransmitting.set(false);
        if (activeAudioTrack != null) {
            try {
                activeAudioTrack.pause();
                activeAudioTrack.flush();
                activeAudioTrack.stop();
                activeAudioTrack.release();
            } catch (Exception ignored) {
            } finally {
                activeAudioTrack = null;
            }
        }
    }

    /**
     * Receiver-side Automation: Transmits acoustic handshake acknowledgment.
     */
    public void transmitReceiverReadyAck(final OnTransmissionProgressListener listener) {
        AirLogger.i(TAG, "Transmitting receiver ready acoustic acknowledgment (AIR_ACK:RECEIVER_READY)...");
        byte[] ackBytes = CMD_RECEIVER_READY.getBytes(StandardCharsets.UTF_8);
        transmitDataOverAudio(ackBytes, listener);
    }

    /**
     * Transmitter-side Command: Transmits ACTIVATE_RECEIVER command.
     */
    public void transmitActivationCommand(final OnTransmissionProgressListener listener) {
        AirLogger.i(TAG, "Transmitting remote ACTIVATE_RECEIVER acoustic command...");
        byte[] commandBytes = CMD_ACTIVATE_RECEIVER.getBytes(StandardCharsets.UTF_8);
        transmitDataOverAudio(commandBytes, listener);
    }

    public void transmitDataOverAudio(byte[] data) {
        transmitDataOverAudio(data, modulationMode, null);
    }

    public void transmitDataOverAudio(byte[] data, OnTransmissionProgressListener listener) {
        transmitDataOverAudio(data, modulationMode, listener);
    }

    /**
     * Transmits a single payload asynchronously using the specified modulation mode.
     */
    public void transmitDataOverAudio(final byte[] data, final ModulationManager.Mode mode, final OnTransmissionProgressListener listener) {
        if (data == null || data.length == 0) {
            if (listener != null) listener.onError(new IllegalArgumentException("Data payload is empty"));
            return;
        }

        new Thread(() -> {
            isTransmitting.set(true);
            try {
                executeTransmissionSync(data, mode, listener);
            } catch (Exception e) {
                AirLogger.e(TAG, "Transmission failed", e);
                if (listener != null) listener.onError(e);
            } finally {
                isTransmitting.set(false);
            }
        }).start();
    }

    /**
     * Core synchronous execution for encoding and playing acoustic data.
     */
    private void executeTransmissionSync(byte[] data, ModulationManager.Mode mode, OnTransmissionProgressListener listener) throws Exception {
        short[] pcmSamples = null;
        int targetSampleRate = SAMPLE_RATE;

        if (mode == ModulationManager.Mode.GGWAVE) {
            AirLogger.i(TAG, "Generating GGWave DSP acoustic waveform (" + data.length + " bytes)...");
            GGWaveEngine engine = GGWaveEngine.getInstance();
            boolean initialized = engine.init(SAMPLE_RATE);

            if (initialized) {
                // AUDIBLE_NORMAL provides high resilience over cellular voice calls
                pcmSamples = engine.encode(data, GGWaveEngine.PROTOCOL_AUDIBLE_NORMAL, 85);
            }
        }

        // Generate Continuous-Phase FSK if in FSK mode or if GGWave returned empty
        if (pcmSamples == null || pcmSamples.length == 0) {
            AirLogger.i(TAG, "Generating Continuous-Phase FSK waveform (" + data.length + " bytes @ " + baudRate + " baud)...");
            byte[] framedData = applyFrameEncapsulation(data);
            pcmSamples = generateContinuousPhaseFsk(framedData, baudRate, SAMPLE_RATE);
            targetSampleRate = SAMPLE_RATE;
        }

        playPcmTrack(pcmSamples, targetSampleRate);

        if (listener != null) {
            listener.onProgress(1, 1, 100);
            listener.onComplete();
        }
    }

    /**
     * Streams multi-packet raw binary dataset incrementally.
     */
    public void transmitRawStream(final List<byte[]> packets, final OnTransmissionProgressListener listener) {
        transmitRawStream(packets, modulationMode, listener);
    }

    public void transmitRawStream(final List<byte[]> packets, final ModulationManager.Mode mode, final OnTransmissionProgressListener listener) {
        if (packets == null || packets.isEmpty()) {
            if (listener != null) listener.onError(new IllegalArgumentException("Packet list is empty"));
            return;
        }

        new Thread(() -> {
            isTransmitting.set(true);
            try {
                int totalPackets = packets.size();

                for (int i = 0; i < totalPackets; i++) {
                    if (!isTransmitting.get()) break;

                    byte[] packetData = packets.get(i);
                    executeTransmissionSync(packetData, mode, null);

                    int progressPercent = (int) (((i + 1) / (float) totalPackets) * 100);
                    if (listener != null) {
                        listener.onProgress(i + 1, totalPackets, progressPercent);
                    }

                    // Acoustic guard interval to allow receiver DSP to finalize frame
                    Thread.sleep(150);
                }

                if (isTransmitting.get() && listener != null) {
                    listener.onComplete();
                }
            } catch (Exception e) {
                AirLogger.e(TAG, "Stream transmission error", e);
                if (listener != null) listener.onError(e);
            } finally {
                cancelTransmission();
            }
        }).start();
    }

    /**
     * Prepends sync bytes (0xAA 0xAA 0xAA 0x7E) for receiver preamble synchronization.
     */
    private byte[] applyFrameEncapsulation(byte[] payload) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(SYNC_PREAMBLE);
        baos.write(SYNC_PREAMBLE);
        baos.write(SYNC_PREAMBLE);
        baos.write(START_FRAME_DELIMITER);

        if (payload != null) {
            baos.write(payload, 0, payload.length);
        }
        return baos.toByteArray();
    }

    /**
     * Sample-accurate, continuous-phase FSK modulator with smooth envelope shaping.
     */
    public static short[] generateContinuousPhaseFsk(byte[] data, int baud, int sampleRate) {
        if (data == null || data.length == 0) return new short[0];

        // Select frequency pair based on baud rate
        int markFreq = (baud <= 600) ? BELL103_MARK_FREQ : BELL202_MARK_FREQ;
        int spaceFreq = (baud <= 600) ? BELL103_SPACE_FREQ : BELL202_SPACE_FREQ;

        double samplesPerBit = (double) sampleRate / (double) baud;
        int totalBits = data.length * 8;
        int totalSamples = (int) Math.round(totalBits * samplesPerBit);

        short[] output = new short[totalSamples];
        double currentPhase = 0.0;
        int sampleIndex = 0;

        // 3ms cosine envelope fade length to eliminate click harmonics
        int fadeLen = Math.min((int) (sampleRate * 0.003), (int) samplesPerBit / 2);

        for (byte b : data) {
            for (int bit = 7; bit >= 0; bit--) {
                int bitVal = (b >>> bit) & 1;
                double targetFreq = (bitVal == 1) ? markFreq : spaceFreq;
                double phaseIncrement = (2.0 * Math.PI * targetFreq) / sampleRate;

                int bitStartSample = sampleIndex;
                int bitLengthSamples = (int) Math.round(samplesPerBit);

                for (int s = 0; s < bitLengthSamples && sampleIndex < totalSamples; s++) {
                    double sampleVal = Math.sin(currentPhase);

                    // Apply soft-edge fade at overall stream boundaries
                    if (sampleIndex < fadeLen) {
                        sampleVal *= ((double) sampleIndex / (double) fadeLen);
                    } else if (sampleIndex > totalSamples - fadeLen) {
                        sampleVal *= ((double) (totalSamples - sampleIndex) / (double) fadeLen);
                    }

                    output[sampleIndex++] = (short) (sampleVal * 32767.0 * 0.75); // 75% volume prevents DAC clipping
                    currentPhase += phaseIncrement;
                    if (currentPhase >= 2.0 * Math.PI) {
                        currentPhase -= 2.0 * Math.PI;
                    }
                }
            }
        }
        return output;
    }

    private void playPcmTrack(short[] pcmSamples, int sampleRate) {
        if (pcmSamples == null || pcmSamples.length == 0) return;

        byte[] pcmBytes = convertShortsToBytes(pcmSamples);

        // USAGE_VOICE_COMMUNICATION ensures routing into active cellular calls without OS mute
        activeAudioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(pcmBytes.length)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build();

        activeAudioTrack.write(pcmBytes, 0, pcmBytes.length);
        activeAudioTrack.play();

        long durationMs = (long) (((double) pcmSamples.length / (double) sampleRate) * 1000.0) + 120;
        try {
            Thread.sleep(durationMs);
        } catch (InterruptedException ignored) {
        }

        try {
            activeAudioTrack.stop();
            activeAudioTrack.release();
        } catch (Exception ignored) {
        } finally {
            activeAudioTrack = null;
        }
    }

    private static byte[] convertShortsToBytes(short[] shorts) {
        byte[] bytes = new byte[shorts.length * 2];
        for (int i = 0; i < shorts.length; i++) {
            bytes[i * 2] = (byte) (shorts[i] & 0xFF);
            bytes[i * 2 + 1] = (byte) ((shorts[i] >>> 8) & 0xFF);
        }
        return bytes;
    }
}