/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package se.oort.clockify.alarms;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnErrorListener;
import android.media.RingtoneManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Vibrator;

import se.oort.clockify.Log;
import se.oort.clockify.R;
import se.oort.clockify.SpotifyProxy;
import se.oort.clockify.provider.AlarmInstance;

import java.io.IOException;

/**
 * Manages playing ringtone and vibrating the device.
 */
public class AlarmKlaxon {
    private static final long[] sVibratePattern = new long[] { 500, 500 };

    // Volume suggested by media team for in-call alarms.
    private static final float IN_CALL_VOLUME = 0.125f;

    private static boolean sStarted = false;
    private static MediaPlayer sMediaPlayer = null;
    private static SpotifyProxy spotify = SpotifyProxy.getInstance();

    private static boolean isNetworkAvailable(Context context) {
        ConnectivityManager connectivityManager
                = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }

    public static void stop(Context context) {
        Log.v("AlarmKlaxon.stop()");

        spotify.init(context);

        if (sStarted) {
            sStarted = false;
            spotify.pause(context);
            // Stop audio playing
            if (sMediaPlayer != null) {
                sMediaPlayer.stop();
                AudioManager audioManager = (AudioManager)
                        context.getSystemService(Context.AUDIO_SERVICE);
                audioManager.abandonAudioFocus(null);
                sMediaPlayer.release();
                sMediaPlayer = null;
            }

            ((Vibrator)context.getSystemService(Context.VIBRATOR_SERVICE)).cancel();
        }
    }

    public static void start(final Context context, AlarmInstance instance,
            boolean inTelephoneCall) {
        Log.v("AlarmKlaxon.start()");

        spotify.init(context);

        // Make sure we are stop before starting
        stop(context);

        if (!AlarmInstance.NO_RINGTONE_URI.equals(instance.mRingtone)) {
            Uri alarmNoise = instance.mRingtone;
            boolean spotifyPlaying = false;
            // Fall back on the default alarm if the database does not have an
            // alarm stored.<
            if (alarmNoise == null || !isNetworkAvailable(context)) {
                alarmNoise = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                if (Log.LOGV) {
                    Log.v("Using default alarm: " + alarmNoise.toString());
                }
            } else {
                spotifyPlaying = true;
            }

            // TODO: Reuse mMediaPlayer instead of creating a new one and/or use RingtoneManager.
            sMediaPlayer = new MediaPlayer();
            sMediaPlayer.setOnErrorListener(new OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    Log.e("Error occurred while playing audio. Stopping AlarmKlaxon.");
                    AlarmKlaxon.stop(context);
                    return true;
                }
            });

            try {
                // Check if we are in a call. If we are, use the in-call alarm
                // resource at a low volume to not disrupt the call.
                if (inTelephoneCall) {
                    Log.v("Using the in-call alarm");
                    sMediaPlayer.setVolume(IN_CALL_VOLUME, IN_CALL_VOLUME);
                    if (spotifyPlaying) {
                        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC,
                                (int) (IN_CALL_VOLUME * audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)),
                                0);
                    } else {
                        setDataSourceFromResource(context, sMediaPlayer, R.raw.in_call_alarm);
                    }
                } else {
                    if (!spotifyPlaying) {
                        sMediaPlayer.setDataSource(context, alarmNoise);
                    }
                }
                startAlarm(context, sMediaPlayer, spotifyPlaying, alarmNoise);
            } catch (Exception ex) {
                Log.v("Using the fallback ringtone: " + ex);
                // The alarmNoise may be on the sd card which could be busy right
                // now. Use the fallback ringtone.
                try {
                    // Must reset the media player to clear the error state.
                    sMediaPlayer.reset();
                    setDataSourceFromResource(context, sMediaPlayer, R.raw.fallbackring);
                    startAlarm(context, sMediaPlayer, spotifyPlaying, alarmNoise);
                } catch (Exception ex2) {
                    // At this point we just don't play anything.
                    Log.e("Failed to play fallback ringtone", ex2);
                }
            }
        }

        if (instance.mVibrate) {
            Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            vibrator.vibrate(sVibratePattern, 0);
        }

        sStarted = true;
    }

    // Do the common stuff when starting the alarm.
    private static void startAlarm(Context context, MediaPlayer player, boolean spotifyPlaying, Uri uri) throws IOException {
        if (spotifyPlaying) {
            String[] parts = uri.toString().split("/");
            spotify.play(context, parts[0]);
        } else {
            AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            // do not play alarms if stream volume is 0 (typically because ringer mode is silent).
            if (audioManager.getStreamVolume(AudioManager.STREAM_ALARM) != 0) {
                player.setAudioStreamType(AudioManager.STREAM_ALARM);
                player.setLooping(true);
                player.prepare();
                audioManager.requestAudioFocus(null,
                        AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
                player.start();
            }
        }
    }

    private static void setDataSourceFromResource(Context context, MediaPlayer player, int res)
            throws IOException {
        AssetFileDescriptor afd = context.getResources().openRawResourceFd(res);
        if (afd != null) {
            player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
        }
    }
}
