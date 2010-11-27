package de.syncdroid.work.ftp;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.telephony.TelephonyManager;
import android.telephony.gsm.GsmCellLocation;
import android.util.Log;
import android.widget.Toast;
import com.zehon.FileTransferClient;
import com.zehon.exception.FileTransferException;
import com.zehon.ftp.FTPClient;
import com.zehon.ftps.FTPsClient;
import com.zehon.sftp.SFTPClient;
import de.syncdroid.AbstractActivity;
import de.syncdroid.R;
import de.syncdroid.activity.ProfileListActivity;
import de.syncdroid.db.model.Location;
import de.syncdroid.db.model.LocationCell;
import de.syncdroid.db.model.Profile;
import de.syncdroid.db.service.LocationService;
import de.syncdroid.db.service.ProfileService;
import jcifs.smb.NtlmPasswordAuthentication;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbFileOutputStream;

import java.io.*;
import java.util.Date;
import java.util.List;

/**
 * Created by IntelliJ IDEA.
 * User: arturh
 * Date: 27.11.10
 * Time: 23:20
 * To change this template use File | Settings | File Templates.
 */
public class OneWaySmbCopyJob extends AbstractCopyJob implements Runnable {


    private static final String TAG = "FtpCopyJob";

    public static final String ACTION_PROFILE_UPDATE
        = "de.syncdroid.ACTION_PROFILE_UPDATE";

    private Context context;
    private Profile profile;
    private ProfileService profileService;
    private LocationService locationService;

    private Notification notification;
    private NotificationManager notificationManager;

    public OneWaySmbCopyJob(Context context, Profile profile,
            ProfileService profileService, LocationService locatonService) {
        this.context = context;

        if(profile.getRemotePath().startsWith("/")) {
            profile.setRemotePath(profile.getRemotePath().substring(1));
        }

        this.profileService = profileService;
        this.locationService = locatonService;

        this.profile = profile;
    }

    private void uploadFiles(RemoteFile dir, NtlmPasswordAuthentication auth, Long lastUpload) throws IOException, FileTransferException {
        Log.d(TAG, "beginning sync of " + dir.name);

        for (RemoteFile item : dir.children) {
            updateStatus("uploading: " + item.name);
            Log.d(TAG, "uploading" + item.name);

            if (item.isDirectory) {
                uploadFiles(item, auth, lastUpload);
            } else if (lastUpload == null || (
                    item.source != null && item.source.lastModified()
                    > lastUpload)) {
                BufferedInputStream inputStream=null;
                inputStream = new BufferedInputStream(
                        new FileInputStream(item.source));
                //fileTransferClient.enterLocalPassiveMode();

                Log.i(TAG, "transfering '" + item.source + "' to '" + item.fullpath + "'");

                SmbFile smbFile = new SmbFile(
                        "smb://" + profile.getHostname() + "/" + item.fullpath, auth);

                Log.i(TAG, "smbPath: " + smbFile.getPath());

                SmbFileOutputStream outStream = new SmbFileOutputStream(smbFile);

                FileReader in = new FileReader(item.source);
                OutputStreamWriter out = new OutputStreamWriter(outStream);
                int c;

                while ((c = in.read()) != -1)
                  out.write(c);

                in.close();
                out.close();

                transferedFiles  ++;

                String msg = "transfering ... uploaded " + transferedFiles + "/"
                    + filesToTransfer;
                Log.d(TAG, msg);
                PendingIntent contentIntent = PendingIntent.getActivity(context, 0,
                        new Intent(context, ProfileListActivity.class), 0);

                // Set the info for the views that show in the notification panel.
                notification.setLatestEventInfo(context,
                        "upload success", msg, contentIntent);
                notificationManager.notify(R.string.remote_service_started, notification);
                inputStream.close();
            } else {
                filesToTransfer --;
            }
        }
        //fileTransferClient.changeToParentDirectory();
    }

    private void updateStatus(String msg) {
        Log.d(TAG, "message: " + msg);
        Intent broadcastIntent = new Intent();
        broadcastIntent.setAction(ACTION_PROFILE_UPDATE);
        broadcastIntent.putExtra(AbstractActivity.EXTRA_ID, profile.getId());
        broadcastIntent.putExtra(AbstractActivity.EXTRA_MESSAGE, msg);
        this.context.sendBroadcast(broadcastIntent);

/*
		messageService.sendMessageToClients(SyncService.PROFILE_STATUS_UPDATED,
				new ProfileHelper(profile.getId(), mgs));
				*/

    }

    public void run() {
        Log.d(TAG, "lastSync: " + profile.getLastSync());

        if(profile.getOnlyIfWifi()) {
            Log.d(TAG, "checking for wifi");
            WifiManager wifiManager = (WifiManager)
                context.getSystemService(Activity.WIFI_SERVICE);

            if(wifiManager.getWifiState() != WifiManager.WIFI_STATE_ENABLED) {

                Log.d(TAG, "wifi is off");
                updateStatus("wifi is off");
                return;
            } else {
                Log.d(TAG, "wifi is on");
            }
        }

        if(profile.getLocation() != null) {
            Log.d(TAG, "checking location '"
                    + profile.getLocation().getName() + "'");
            updateStatus("checking location '"
                    + profile.getLocation().getName() + "'");

            TelephonyManager tm = (TelephonyManager)
                    context.getSystemService(Activity.TELEPHONY_SERVICE);
            GsmCellLocation location = (GsmCellLocation) tm.getCellLocation();

            List<Location> locations =
                locationService.locate(location.getCid(), location.getLac());
            Log.d(TAG, "at cell '" + new LocationCell(location.getCid(),
                    location.getLac()) + "'");

            if(locations.contains(profile.getLocation()) == false) {
                updateStatus("not at location '" + profile.getLocation().getName() + "'");
                Log.d(TAG, "not at location '" + profile.getLocation().getName() + "'");

                for(LocationCell cell : profile.getLocation().getLocationCells()) {
                    Log.d(TAG, " - " + cell);
                }

                return;
            } else {
                Log.d(TAG, "at location '"
                        + profile.getLocation().getName() + "'");
            }
        }

        updateStatus("checking for file status ...");

        try {
            String path = profile.getLocalPath();
            if(path == null) {
                Log.e(TAG, "path is null :-(");
                Toast.makeText(context, "invalid path for profile '"
                        + profile.getName() + "'", 2000).show();
                return ;
            }

            File file = new File(path);

            if(file.exists() == false) {
                updateStatus("file not found: " + path);
                Log.e(TAG, "file not found: " + path);
                Toast.makeText(context, "localPath not found for profile '"
                        + profile.getName() + "': " + path, 2000).show();
                return ;
            }

            transferedFiles = 0;
            filesToTransfer = 0;
            RemoteFile rootRemote = buildTree(file, profile.getRemotePath());

            if (profile.getLastSync() != null
                    && rootRemote.newest <= profile.getLastSync().getTime()) {
                Log.d(TAG, "nothing to do");
                updateStatus("nothing to do");
                return;
            }

            notification = new Notification(R.drawable.icon,
                    "upload started for '" + profile.getName() + "'",
                    System.currentTimeMillis());


            // The PendingIntent to launch our activity if the user selects this
            // notification
            PendingIntent contentIntent = PendingIntent.getActivity(context, 0,
                    new Intent(context, ProfileListActivity.class), 0);
            notificationManager =
                (NotificationManager) context.getSystemService(Activity.NOTIFICATION_SERVICE);

            // Set the info for the views that show in the notification panel.
            notification.setLatestEventInfo(context,
                    "upload success", "starting uploading ...", contentIntent);

            notificationManager.notify(R.string.remote_service_started, notification);


            String domain = null;
            String username = null;

            if(profile.getUsername().indexOf("\\") != -1) {
                domain = profile.getUsername().substring(0, profile.getUsername().indexOf("\\"));
                username = profile.getUsername().substring(profile.getUsername().indexOf("\\") + 1);
            } else {
                domain = "";
                username = profile.getUsername();
            }

            NtlmPasswordAuthentication auth = new NtlmPasswordAuthentication(domain, username, profile.getPassword());

            Long transferBegin = System.currentTimeMillis();


            uploadFiles(rootRemote, auth, profile.getLastSync() != null ?
                    profile.getLastSync().getTime() : null);

            Long transferFinish = System.currentTimeMillis();

            Long transferTimeSeconds = (transferFinish - transferBegin) / 1000;

            String msg = "finished. uploaded " + transferedFiles + "/"
                + filesToTransfer + " in " + transferTimeSeconds + " seconds";
            Log.d(TAG, msg);

            notification.setLatestEventInfo(context,
                    "upload success", msg, contentIntent);

            notificationManager.notify(R.string.remote_service_started, notification);
            notification.tickerText = msg;

            profile.setLastSync(new Date());
            profileService.update(profile);

            // disconnect from ftp server
            /*ftpClient.logout();
            ftpClient.disconnect();*/
            //FileTransferClient.closeCache();
        } catch(Exception e) {
            Log.e(TAG, "whoa, exception: ", e);
            updateStatus("error");

            Toast.makeText(context, e.toString(), 2000).show();
            return;
        }

    }

}