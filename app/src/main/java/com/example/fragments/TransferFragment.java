package com.example.fragments;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.R;
import com.example.adapters.TransferAdapter;
import com.example.audio.AudioEncoder;
import com.example.audio.ModulationManager;
import com.example.call.CallManager;
import com.example.database.TransferDatabase;
import com.example.models.DataPacket;
import com.example.models.TransferItem;
import com.example.services.AirSignalInCallService;
import com.example.services.AudioTransferService;
import com.example.services.SmsTransferService;
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
import java.util.UUID;

public class TransferFragment extends Fragment {

    private static final String TAG = "TransferFragment";

    private RecyclerView rvTransfers;
    private TransferAdapter adapter;
    private TransferDatabase transferDb;

    private Uri selectedFileUri = null;
    private String selectedFileName = "None";
    private long selectedFileSize = 0;
    private File localCachedFile = null;
    private String pastedPayloadText = null;

    private ActivityResultLauncher<String> filePickerLauncher;

    // Real-time UI progress updater for background transfers
    private final BroadcastReceiver progressReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (FileAssembler.ACTION_TRANSFER_PROGRESS.equals(intent.getAction())) {
                if (isAdded() && getActivity() != null) {
                    getActivity().runOnUiThread(() -> loadTransfers());
                }
            }
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Native Android Storage File Picker
        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) {
                        selectedFileUri = uri;
                        pastedPayloadText = null;
                        cacheSelectedFileLocally(uri);
                    }
                }
        );
    }

    @Override
    public void onResume() {
        super.onResume();
        loadTransfers();
        if (getContext() != null) {
            IntentFilter filter = new IntentFilter(FileAssembler.ACTION_TRANSFER_PROGRESS);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getContext().registerReceiver(progressReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                getContext().registerReceiver(progressReceiver, filter);
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (getContext() != null) {
            try {
                getContext().unregisterReceiver(progressReceiver);
            } catch (Exception ignored) {
            }
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_transfer, container, false);

        rvTransfers = view.findViewById(R.id.rvTransfers);
        transferDb = TransferDatabase.getInstance(requireContext());
        rvTransfers.setLayoutManager(new LinearLayoutManager(requireContext()));

        loadTransfers();

        view.findViewById(R.id.btnSelectFile).setOnClickListener(v -> showFileSelectionDialog());
        view.findViewById(R.id.btnSendSmsData).setOnClickListener(v -> showSmsDataDialog());
        view.findViewById(R.id.btnSendAudioData).setOnClickListener(v -> showAudioDataDialog());

        return view;
    }

    private void loadTransfers() {
        List<TransferItem> list = transferDb.getAllTransfers();
        if (adapter == null) {
            adapter = new TransferAdapter(list);
            rvTransfers.setAdapter(adapter);
        } else {
            rvTransfers.setAdapter(new TransferAdapter(list));
        }
    }

    /**
     * Clean 3-option dialog replacing the old bloated menu.
     */
    private void showFileSelectionDialog() {
        CharSequence[] options = new CharSequence[]{
                "1. Choose Real Image / File from Storage",
                "2. Paste / Input Raw Base64 or Text",
                "3. Generate & Transmit Audio (FSK / GGWave)"
        };

        new AlertDialog.Builder(requireContext())
                .setTitle("Offline Data Transfer Hub")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        filePickerLauncher.launch("*/*");
                    } else if (which == 1) {
                        showPasteBase64Dialog();
                    } else if (which == 2) {
                        showTransmitAudioOptionsDialog();
                    }
                })
                .show();
    }

    private void cacheSelectedFileLocally(Uri uri) {
        try {
            Cursor cursor = requireContext().getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIndex != -1) selectedFileName = cursor.getString(nameIndex);
                if (sizeIndex != -1) selectedFileSize = cursor.getLong(sizeIndex);
                cursor.close();
            }

            InputStream is = requireContext().getContentResolver().openInputStream(uri);
            if (is != null) {
                localCachedFile = new File(requireContext().getCacheDir(), selectedFileName != null ? selectedFileName : "transfer_payload.bin");
                FileOutputStream fos = new FileOutputStream(localCachedFile);
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) > 0) {
                    fos.write(buf, 0, len);
                }
                fos.flush();
                fos.close();
                is.close();
                selectedFileSize = localCachedFile.length();

                Toast.makeText(requireContext(), "Selected: " + selectedFileName + " (" + (selectedFileSize / 1024) + " KB)", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            AirLogger.e(TAG, "Error caching selected storage file", e);
            Toast.makeText(requireContext(), "Failed reading file from storage", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Option 2: Paste / Input Raw Base64 or Text.
     */
    private void showPasteBase64Dialog() {
        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dpToPx(20), dpToPx(12), dpToPx(20), dpToPx(12));

        TextView tvInfo = new TextView(requireContext());
        tvInfo.setText("Paste your Base64 string, token, or custom message below:");
        tvInfo.setTextColor(Color.LTGRAY);
        tvInfo.setTextSize(13);
        layout.addView(tvInfo);

        final EditText etInput = new EditText(requireContext());
        etInput.setHint("Paste Base64 or text here...");
        etInput.setMinLines(5);
        etInput.setMaxLines(10);
        etInput.setTypeface(Typeface.MONOSPACE);
        etInput.setTextSize(13);
        if (pastedPayloadText != null) {
            etInput.setText(pastedPayloadText);
        }
        layout.addView(etInput);

        new AlertDialog.Builder(requireContext())
                .setTitle("Raw Base64 / Text Input")
                .setView(layout)
                .setPositiveButton("Set as Active Payload", (dialog, which) -> {
                    String input = etInput.getText().toString().trim();
                    if (!input.isEmpty()) {
                        pastedPayloadText = input;
                        try {
                            localCachedFile = new File(requireContext().getCacheDir(), "pasted_payload.txt");
                            FileOutputStream fos = new FileOutputStream(localCachedFile);
                            fos.write(input.getBytes(StandardCharsets.UTF_8));
                            fos.flush();
                            fos.close();

                            selectedFileName = "pasted_payload.txt";
                            selectedFileSize = localCachedFile.length();
                            selectedFileUri = null;

                            Toast.makeText(requireContext(), "Payload set (" + selectedFileSize + " bytes)", Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            AirLogger.e(TAG, "Error caching pasted text", e);
                        }
                    } else {
                        Toast.makeText(requireContext(), "Input cannot be empty", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Option 3: Unified FSK & GGWave Audio Transmission Dialog.
     */
    private void showTransmitAudioOptionsDialog() {
        if ((localCachedFile == null || !localCachedFile.exists()) && pastedPayloadText == null) {
            Toast.makeText(requireContext(), "Please select a file or paste text first.", Toast.LENGTH_LONG).show();
            showFileSelectionDialog();
            return;
        }

        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dpToPx(20), dpToPx(16), dpToPx(20), dpToPx(16));

        TextView tvDetails = new TextView(requireContext());
        tvDetails.setText("File/Payload: " + selectedFileName + " (" + (selectedFileSize > 0 ? selectedFileSize + " B" : "Ready") + ")");
        tvDetails.setTextColor(Color.WHITE);
        tvDetails.setTypeface(Typeface.DEFAULT_BOLD);
        layout.addView(tvDetails);

        TextView tvModeLabel = new TextView(requireContext());
        tvModeLabel.setText("\nSelect Transmission Modulation:");
        tvModeLabel.setTextColor(Color.LTGRAY);
        layout.addView(tvModeLabel);

        Spinner spMode = new Spinner(requireContext());
        String[] modes = new String[]{
                "FSK Modulation (Bell 103 / 202 Audio Tones)",
                "GGWave Audio Engine (MFSK + Reed-Solomon FEC)"
        };
        ArrayAdapter<String> modeAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, modes);
        spMode.setAdapter(modeAdapter);
        layout.addView(spMode);

        TextView tvChannelLabel = new TextView(requireContext());
        tvChannelLabel.setText("\nSelect Output Channel:");
        tvChannelLabel.setTextColor(Color.LTGRAY);
        layout.addView(tvChannelLabel);

        Spinner spChannel = new Spinner(requireContext());
        String[] channels = new String[]{
                "1. Stream directly into Active Phone Call",
                "2. Play via Loudspeaker / Aux Cable (Air-Gap / No Call)",
                "3. Dial Number & Transmit on Connect"
        };
        ArrayAdapter<String> channelAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, channels);
        spChannel.setAdapter(channelAdapter);
        layout.addView(spChannel);

        new AlertDialog.Builder(requireContext())
                .setTitle("Transmit Audio Data")
                .setView(layout)
                .setPositiveButton("Start Transmission", (dialog, which) -> {
                    int modeIndex = spMode.getSelectedItemPosition();
                    int channelIndex = spChannel.getSelectedItemPosition();

                    ModulationManager.Mode selectedMode = (modeIndex == 0)
                            ? ModulationManager.Mode.FSK
                            : ModulationManager.Mode.GGWAVE;
                    ModulationManager.getInstance(requireContext()).setMode(selectedMode);

                    executeAudioTransmission(channelIndex);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void executeAudioTransmission(int channelIndex) {
        String fileId = "TX_" + UUID.randomUUID().toString().substring(0, 8);
        TransferItem item = new TransferItem(
                fileId,
                selectedFileName,
                selectedFileSize,
                0,
                "TRANSMITTING",
                ModulationManager.getInstance(requireContext()).getMode().name(),
                1,
                0
        );
        transferDb.insertTransfer(item);
        loadTransfers();

        Intent serviceIntent = new Intent(requireContext(), AudioTransferService.class);
        serviceIntent.setAction(AudioTransferService.ACTION_SEND_AUDIO_DATA);
        if (localCachedFile != null && localCachedFile.exists()) {
            serviceIntent.putExtra(AudioTransferService.EXTRA_FILE_PATH, localCachedFile.getAbsolutePath());
        }
        serviceIntent.putExtra(AudioTransferService.EXTRA_FILE_NAME, selectedFileName);
        serviceIntent.putExtra(AudioTransferService.EXTRA_FILE_SIZE, selectedFileSize);
        serviceIntent.putExtra(AudioTransferService.EXTRA_FILE_ID, fileId);

        if (channelIndex == 0) {
            // Stream to existing call
            if (AirSignalInCallService.getActiveCall() != null) {
                requireContext().startService(serviceIntent);
                Toast.makeText(requireContext(), "Streaming audio into active call...", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(requireContext(), "No active call found. Playing via local audio.", Toast.LENGTH_LONG).show();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    requireContext().startForegroundService(serviceIntent);
                } else {
                    requireContext().startService(serviceIntent);
                }
            }
        } else if (channelIndex == 1) {
            // Local speaker / Aux cable
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requireContext().startForegroundService(serviceIntent);
            } else {
                requireContext().startService(serviceIntent);
            }
            Toast.makeText(requireContext(), "Playing transmission audio...", Toast.LENGTH_SHORT).show();
        } else if (channelIndex == 2) {
            // Dial phone number
            promptPhoneNumberAndPlaceCall(null, localCachedFile);
        }
    }

    private void promptPhoneNumberAndPlaceCall(final String rawText, final File fileToTransmit) {
        final EditText etPhone = new EditText(requireContext());
        etPhone.setHint("Enter Target Phone Number to Call");
        etPhone.setPadding(dpToPx(20), dpToPx(16), dpToPx(20), dpToPx(16));

        new AlertDialog.Builder(requireContext())
                .setTitle("Dial & Stream Audio")
                .setMessage("Enter the recipient's phone number. AirSignal will place the call and transmit the audio once answered.")
                .setView(etPhone)
                .setPositiveButton("Call & Transmit", (dialog, which) -> {
                    String phone = etPhone.getText().toString().trim();
                    if (!phone.isEmpty()) {
                        String fileId = "CALL_" + UUID.randomUUID().toString().substring(0, 8);
                        Intent serviceIntent = new Intent(requireContext(), AudioTransferService.class);
                        serviceIntent.setAction(AudioTransferService.ACTION_SEND_AUDIO_DATA);
                        if (fileToTransmit != null) {
                            serviceIntent.putExtra(AudioTransferService.EXTRA_FILE_PATH, fileToTransmit.getAbsolutePath());
                        }
                        serviceIntent.putExtra(AudioTransferService.EXTRA_FILE_NAME, selectedFileName);
                        serviceIntent.putExtra(AudioTransferService.EXTRA_FILE_SIZE, selectedFileSize);
                        serviceIntent.putExtra(AudioTransferService.EXTRA_FILE_ID, fileId);
                        requireContext().startService(serviceIntent);

                        CallManager.placeCall(requireContext(), phone);
                        Toast.makeText(requireContext(), "Dialing " + phone + "...", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(requireContext(), "Please enter a valid phone number", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
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
            AirLogger.e(TAG, "Error reading file bytes", e);
            return new byte[0];
        }
    }

    private void showSmsDataDialog() {
        final EditText etPhone = new EditText(requireContext());
        etPhone.setHint("Enter Target Phone Number");
        etPhone.setPadding(dpToPx(20), dpToPx(16), dpToPx(20), dpToPx(16));

        new AlertDialog.Builder(requireContext())
                .setTitle("Send via SMS Data Mode")
                .setMessage("Payload will be sliced into Base64 packet chunks and transmitted via Cellular SMS.")
                .setView(etPhone)
                .setPositiveButton("Start SMS Transfer", (dialog, which) -> {
                    String phone = etPhone.getText().toString().trim();
                    if (!phone.isEmpty()) {
                        byte[] payloadBytes = (localCachedFile != null && localCachedFile.exists())
                                ? readFileBytes(localCachedFile)
                                : "AirSignal Offline Data Packet Payload".getBytes(StandardCharsets.UTF_8);

                        List<DataPacket> packets = DataPacketManager.createPackets(payloadBytes);
                        String fileId = packets.isEmpty() ? "SYS_01" : packets.get(0).getFileId();

                        TransferItem item = new TransferItem(
                                fileId,
                                selectedFileName.equals("None") ? "data_payload.bin" : selectedFileName,
                                payloadBytes.length,
                                100,
                                "COMPLETED",
                                "SMS_DATA",
                                packets.size(),
                                packets.size()
                        );
                        transferDb.insertTransfer(item);

                        Intent serviceIntent = new Intent(requireContext(), SmsTransferService.class);
                        serviceIntent.putExtra(SmsTransferService.EXTRA_TARGET_PHONE, phone);
                        requireContext().startService(serviceIntent);

                        Toast.makeText(requireContext(), "SMS Data Transfer Dispatched!", Toast.LENGTH_SHORT).show();
                        loadTransfers();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAudioDataDialog() {
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_audio_transfer, null);
        final EditText etPhone = dialogView.findViewById(R.id.etTargetPhone);

        final AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setView(dialogView)
                .create();

        dialogView.findViewById(R.id.btnCancelAudioDialog).setOnClickListener(v -> dialog.dismiss());

        dialogView.findViewById(R.id.btnStartAudioTransfer).setOnClickListener(v -> {
            String phone = etPhone.getText().toString().trim();
            if (!phone.isEmpty()) {
                String fileId = "SYS_AUD_" + System.currentTimeMillis();
                TransferItem item = new TransferItem(
                        fileId,
                        selectedFileName.equals("None") ? "stream_audio_data.bin" : selectedFileName,
                        selectedFileSize > 0 ? selectedFileSize : 8192,
                        0,
                        "QUEUED",
                        ModulationManager.getInstance(requireContext()).getMode().name(),
                        1,
                        0
                );
                transferDb.insertTransfer(item);

                Intent serviceIntent = new Intent(requireContext(), AudioTransferService.class);
                serviceIntent.setAction(AudioTransferService.ACTION_SEND_AUDIO_DATA);
                if (localCachedFile != null && localCachedFile.exists()) {
                    serviceIntent.putExtra(AudioTransferService.EXTRA_FILE_PATH, localCachedFile.getAbsolutePath());
                }
                serviceIntent.putExtra(AudioTransferService.EXTRA_FILE_NAME, selectedFileName);
                serviceIntent.putExtra(AudioTransferService.EXTRA_FILE_SIZE, selectedFileSize);
                serviceIntent.putExtra(AudioTransferService.EXTRA_FILE_ID, fileId);
                requireContext().startService(serviceIntent);

                CallManager.placeCall(requireContext(), phone);

                Toast.makeText(requireContext(), "Dialing " + phone + ". Audio will stream when answered.", Toast.LENGTH_LONG).show();
                dialog.dismiss();
                loadTransfers();
            }
        });

        dialog.show();
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                getResources().getDisplayMetrics()
        );
    }
}