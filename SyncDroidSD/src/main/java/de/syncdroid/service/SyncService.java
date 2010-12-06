package de.syncdroid.service;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Log;

import com.google.inject.Inject;

import de.syncdroid.AbstractActivity;
import de.syncdroid.GuiceService;
import de.syncdroid.R;
import de.syncdroid.SyncBroadcastReceiver;
import de.syncdroid.db.model.Profile;
import de.syncdroid.db.model.ProfileStatusLog;
import de.syncdroid.db.service.LocationService;
import de.syncdroid.db.service.ProfileService;
import de.syncdroid.db.service.ProfileStatusLogService;
import de.syncdroid.work.OneWayCopyJob;

public class SyncService extends GuiceService {
	private static final String TAG = "SyncDroid.SyncService";

	public static final String ACTION_TIMER_TICK  = "de.syncdroid.ACTION_TIMER_TICK";
	public static final String ACTION_START_TIMER = "de.syncdroid.ACTION_START_TIMER";

    Integer currentPollingInterval = null;
    PendingIntent alarmTimerPendingIntent = null;

	@Inject
	private ProfileService profileService;

	@Inject
	private ProfileStatusLogService profileStatusLogService;
	
	@Inject
	private LocationService locationService;

	private Thread thread = null;

    private String lastShortMessage = null;
    private String lastDetailMessage = null;

    private void enableAlarmTimer() {
        if(true || alarmTimerPendingIntent != null) {
            return ; // already enabled
        }

        Log.d(TAG, "enable sync timer every " + currentPollingInterval + " minutes.");
        AlarmManager mgr = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(this, SyncBroadcastReceiver.class);
        i.setAction(ACTION_TIMER_TICK);

        alarmTimerPendingIntent = PendingIntent.getBroadcast(this, 0, i, 0);

        mgr.setRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime(), currentPollingInterval * 1000 * 60, alarmTimerPendingIntent);
    }

    private void disableAlarmTimer() {
        AlarmManager mgr = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                mgr.cancel(alarmTimerPendingIntent);

        alarmTimerPendingIntent = null;
    }

	@Override
	public void onStart(Intent intent, int startId) {
		super.onStart(intent, startId);
		
		// handle intents
		if (intent != null && intent.getAction() != null) {
			Log.i(TAG, "Received intent= " + intent + " with action '" 
					+ intent.getAction() + "'");

			if (intent.getAction().equals(ACTION_TIMER_TICK)) {
				Log.d(TAG, "ACTION_TIMER_TICK");

                SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
                Integer newInterval = null;
                try {
                    newInterval = Integer.valueOf(preferences.getString("updateInterval",
                                    getResources().getString(R.string.pref_updateInterval_default))
                    );
                } catch (Exception e) {
                    Log.e(TAG, "error fetching current update interval from sharedPreferences", e);
                    return ;
                }

                if(newInterval != null && newInterval.equals(currentPollingInterval) == false && newInterval > 0) {
                    Log.d(TAG, "Changing Timer frequency from " + currentPollingInterval
                            + " to " + newInterval + " minutes");

                    currentPollingInterval = newInterval;
                    disableAlarmTimer();
                    enableAlarmTimer();
                } else {
                    Log.e(TAG, "not changing frequency ... still at " + currentPollingInterval + " minutes");
                }

				sync();
			} else if (intent.getAction().equals(ACTION_START_TIMER)
					|| intent.getAction().equals(Intent.ACTION_BOOT_COMPLETED)) {
                Log.d(TAG, "ACTION_START_TIMER");

                if(currentPollingInterval == null) {
			        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
                    try {
                        currentPollingInterval = Integer.valueOf(preferences.getString("updateInterval",
                                        getResources().getString(R.string.pref_updateInterval_default))
                        );

                        if(currentPollingInterval < 1) {
                            currentPollingInterval = 1;
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "error fetching current update interval from sharedPreferences", e);
                    }
                }

				enableAlarmTimer();
				sync();
			} else if(intent.getAction().equals(OneWayCopyJob.ACTION_PROFILE_UPDATE)) {
                // write profile status messages to database

                ProfileStatusLog log = (ProfileStatusLog)
                        intent.getSerializableExtra(AbstractActivity.EXTRA_PROFILE_UPDATE);

                if(log.getDetailMessage().equals(lastDetailMessage) && log.getShortMessage().equals(lastShortMessage)) {
                    Log.d(TAG, "ignoring duplicate message");
                } else {
                    Log.i(TAG, "saving profile status update to database ...");

                    profileStatusLogService.save(log);
                }

                lastDetailMessage = log.getDetailMessage();
                lastShortMessage = log.getShortMessage();

            }
		} else {
            Log.w(TAG, "unknown intent with action '" + intent.getAction() + "': " + intent);
		}
	}

	private void sync() {
		if (thread != null && thread.isAlive()) {
            return ;
        }

		Log.d(TAG, "sync()");

		if(profileService != null) {
			Runnable job = new Runnable() {
				public void run() {
					Looper.prepare();
					for(Profile profile : profileService.list()) {
                        new OneWayCopyJob(
                                SyncService.this, profile,
                                profileService, locationService).run();
					}
				}
			};
				  
			thread = new Thread(job);
			thread.start();
		} else {
			Log.e(TAG, "profileService is NULL");
		}
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

}