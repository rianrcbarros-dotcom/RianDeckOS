package br.com.rian.deck;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.NoiseSuppressor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

final class UsbVoiceCapture {
    private static final int RATE = 16000;
    private static final int CHUNK = RATE / 20;
    private static final int LIMIT = RATE * 12;
    private static final int SILENCE = RATE * 12 / 10;

    static byte[] record(BooleanSupplier active, IntConsumer level) {
        int minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) throw new IllegalStateException();
        AudioRecord audio = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minimum, CHUNK * 4));
        NoiseSuppressor noise = null;
        try {
            if (audio.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException();
            if (NoiseSuppressor.isAvailable()) {
                try {
                    noise = NoiseSuppressor.create(audio.getAudioSessionId());
                    if (noise != null) noise.setEnabled(true);
                } catch (RuntimeException ignored) { }
            }
            short[] buffer = new short[CHUNK];
            ByteBuffer wave = ByteBuffer.allocate(44 + LIMIT * 2).order(ByteOrder.LITTLE_ENDIAN);
            wave.position(44);
            int recorded = 0, quiet = 0;
            boolean heard = false;
            audio.startRecording();
            while (recorded < LIMIT && active.getAsBoolean()) {
                int count = audio.read(buffer, 0, Math.min(buffer.length, LIMIT - recorded));
                if (count <= 0) throw new IllegalStateException();
                long energy = 0;
                for (int i = 0; i < count; i++) {
                    wave.putShort(buffer[i]);
                    energy += (long)buffer[i] * buffer[i];
                }
                int rms = (int)Math.sqrt((double)energy / count);
                level.accept(Math.min(100, rms / 35));
                recorded += count;
                if (rms >= 380) { heard = true; quiet = 0; }
                else quiet += count;
                if ((heard && quiet >= SILENCE) || (!heard && recorded >= RATE * 4)) break;
            }
            if (!heard || !active.getAsBoolean()) return null;
            wave.position(0);
            wave.putInt(0x46464952).putInt(36 + recorded * 2).putInt(0x45564157);
            wave.putInt(0x20746d66).putInt(16).putShort((short)1).putShort((short)1);
            wave.putInt(RATE).putInt(RATE * 2).putShort((short)2).putShort((short)16);
            wave.putInt(0x61746164).putInt(recorded * 2);
            return java.util.Arrays.copyOf(wave.array(), 44 + recorded * 2);
        } finally {
            if (noise != null) noise.release();
            if (audio.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) audio.stop();
            audio.release();
        }
    }

    private UsbVoiceCapture() {}
}
