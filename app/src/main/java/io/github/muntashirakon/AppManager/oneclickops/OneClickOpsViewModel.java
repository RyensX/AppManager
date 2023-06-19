// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.oneclickops;

import static io.github.muntashirakon.AppManager.compat.PackageManagerCompat.MATCH_DISABLED_COMPONENTS;
import static io.github.muntashirakon.AppManager.compat.PackageManagerCompat.MATCH_UNINSTALLED_PACKAGES;

import android.Manifest;
import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.RemoteException;
import android.os.UserHandleHidden;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Pair;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Future;

import io.github.muntashirakon.AppManager.BuildConfig;
import io.github.muntashirakon.AppManager.compat.ApplicationInfoCompat;
import io.github.muntashirakon.AppManager.compat.ManifestCompat;
import io.github.muntashirakon.AppManager.compat.PackageManagerCompat;
import io.github.muntashirakon.AppManager.compat.StorageManagerCompat;
import io.github.muntashirakon.AppManager.rules.compontents.ComponentUtils;
import io.github.muntashirakon.AppManager.self.SelfPermissions;
import io.github.muntashirakon.AppManager.settings.Ops;
import io.github.muntashirakon.AppManager.users.Users;
import io.github.muntashirakon.AppManager.utils.PackageUtils;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;
import io.github.muntashirakon.io.Paths;

public class OneClickOpsViewModel extends AndroidViewModel {
    public static final String TAG = OneClickOpsViewModel.class.getSimpleName();

    private final PackageManager pm;
    private final MutableLiveData<List<ItemCount>> trackerCount = new MutableLiveData<>();
    private final MutableLiveData<Pair<List<ItemCount>, String[]>> componentCount = new MutableLiveData<>();
    private final MutableLiveData<Pair<List<AppOpCount>, Pair<int[], Integer>>> appOpsCount = new MutableLiveData<>();
    private final MutableLiveData<List<String>> clearDataCandidates = new MutableLiveData<>();
    private final MutableLiveData<Boolean> trimCachesResult = new MutableLiveData<>();
    private final MutableLiveData<String[]> appsInstalledByAmForDexOpt = new MutableLiveData<>();

    @Nullable
    private Future<?> futureResult;

    public OneClickOpsViewModel(@NonNull Application application) {
        super(application);
        pm = application.getPackageManager();
    }

    @Override
    protected void onCleared() {
        if (futureResult != null) {
            futureResult.cancel(true);
        }
        super.onCleared();
    }

    public LiveData<List<ItemCount>> watchTrackerCount() {
        return trackerCount;
    }

    public LiveData<Pair<List<ItemCount>, String[]>> watchComponentCount() {
        return componentCount;
    }

    public LiveData<Pair<List<AppOpCount>, Pair<int[], Integer>>> watchAppOpsCount() {
        return appOpsCount;
    }

    public LiveData<List<String>> getClearDataCandidates() {
        return clearDataCandidates;
    }

    public LiveData<Boolean> watchTrimCachesResult() {
        return trimCachesResult;
    }

    public MutableLiveData<String[]> getAppsInstalledByAmForDexOpt() {
        return appsInstalledByAmForDexOpt;
    }

    @AnyThread
    public void blockTrackers(boolean systemApps) {
        if (futureResult != null) {
            futureResult.cancel(true);
        }
        futureResult = ThreadUtils.postOnBackgroundThread(() -> {
            int flags = PackageManager.GET_ACTIVITIES | PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS
                    | PackageManager.GET_SERVICES | MATCH_DISABLED_COMPONENTS | MATCH_UNINSTALLED_PACKAGES
                    | PackageManagerCompat.MATCH_STATIC_SHARED_AND_SDK_LIBRARIES;
            boolean canChangeComponentState = SelfPermissions.checkSelfOrRemotePermission(Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE);
            if (!canChangeComponentState) {
                // Since there's no permission, it can only change its own component states
                try {
                    PackageInfo packageInfo = getApplication().getPackageManager().getPackageInfo(BuildConfig.APPLICATION_ID, flags);
                    if (systemApps || !ApplicationInfoCompat.isSystemApp(packageInfo.applicationInfo)) {
                        ItemCount trackerCount = getTrackerCountForApp(packageInfo);
                        if (trackerCount.count > 0) {
                            this.trackerCount.postValue(Collections.singletonList(trackerCount));
                            return;
                        }
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    e.printStackTrace();
                }
                this.trackerCount.postValue(Collections.emptyList());
                return;
            }
            boolean crossUserPermission = SelfPermissions.checkSelfOrRemotePermission(ManifestCompat.permission.INTERACT_ACROSS_USERS)
                    || SelfPermissions.checkSelfOrRemotePermission(ManifestCompat.permission.INTERACT_ACROSS_USERS_FULL);
            boolean isShell = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Users.getSelfOrRemoteUid() == Ops.SHELL_UID;
            List<ItemCount> trackerCounts = new ArrayList<>();
            HashSet<String> packageNames = new HashSet<>();
            for (PackageInfo packageInfo : PackageUtils.getAllPackages(flags, !crossUserPermission)) {
                if (packageNames.contains(packageInfo.packageName)) {
                    continue;
                }
                packageNames.add(packageInfo.packageName);
                if (ThreadUtils.isInterrupted()) return;
                ApplicationInfo applicationInfo = packageInfo.applicationInfo;
                if (isShell && !ApplicationInfoCompat.isTestOnly(applicationInfo)) {
                    continue;
                }
                if (!systemApps && ApplicationInfoCompat.isSystemApp(applicationInfo)) {
                    continue;
                }
                ItemCount trackerCount = getTrackerCountForApp(packageInfo);
                if (trackerCount.count > 0) {
                    trackerCounts.add(trackerCount);
                }
            }
            this.trackerCount.postValue(trackerCounts);
        });
    }

    @AnyThread
    public void blockComponents(boolean systemApps, @NonNull String[] signatures) {
        if (signatures.length == 0) return;
        if (futureResult != null) {
            futureResult.cancel(true);
        }
        futureResult = ThreadUtils.postOnBackgroundThread(() -> {
            boolean canChangeComponentState = SelfPermissions.checkSelfOrRemotePermission(Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE);
            if (!canChangeComponentState) {
                // Since there's no permission, it can only change its own component states
                ApplicationInfo applicationInfo = getApplication().getApplicationInfo();
                if (systemApps || !ApplicationInfoCompat.isSystemApp(applicationInfo)) {
                    ItemCount componentCount = new ItemCount();
                    componentCount.packageName = applicationInfo.packageName;
                    componentCount.packageLabel = applicationInfo.loadLabel(pm).toString();
                    componentCount.count = PackageUtils.getFilteredComponents(applicationInfo.packageName,
                            UserHandleHidden.myUserId(), signatures).size();
                    if (componentCount.count > 0) {
                        this.componentCount.postValue(new Pair<>(Collections.singletonList(componentCount), signatures));
                        return;
                    }
                }
                this.componentCount.postValue(new Pair<>(Collections.emptyList(), signatures));
                return;
            }
            boolean crossUserPermission = SelfPermissions.checkSelfOrRemotePermission(ManifestCompat.permission.INTERACT_ACROSS_USERS)
                    || SelfPermissions.checkSelfOrRemotePermission(ManifestCompat.permission.INTERACT_ACROSS_USERS_FULL);
            boolean isShell = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Users.getSelfOrRemoteUid() == Ops.SHELL_UID;
            List<ItemCount> componentCounts = new ArrayList<>();
            HashSet<String> packageNames = new HashSet<>();
            for (ApplicationInfo applicationInfo : PackageUtils.getAllApplications(MATCH_UNINSTALLED_PACKAGES
                    | PackageManagerCompat.MATCH_STATIC_SHARED_AND_SDK_LIBRARIES, !crossUserPermission)) {
                if (packageNames.contains(applicationInfo.packageName)) {
                    continue;
                }
                packageNames.add(applicationInfo.packageName);
                if (ThreadUtils.isInterrupted()) return;
                if (isShell && !ApplicationInfoCompat.isTestOnly(applicationInfo))
                    continue;
                if (!systemApps && (applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0)
                    continue;
                ItemCount componentCount = new ItemCount();
                componentCount.packageName = applicationInfo.packageName;
                componentCount.packageLabel = applicationInfo.loadLabel(pm).toString();
                componentCount.count = PackageUtils.getFilteredComponents(applicationInfo.packageName,
                        UserHandleHidden.myUserId(), signatures).size();
                if (componentCount.count > 0) componentCounts.add(componentCount);
            }
            this.componentCount.postValue(new Pair<>(componentCounts, signatures));
        });
    }

    @AnyThread
    public void setAppOps(int[] appOpList, int mode, boolean systemApps) {
        if (futureResult != null) {
            futureResult.cancel(true);
        }
        futureResult = ThreadUtils.postOnBackgroundThread(() -> {
            Pair<int[], Integer> appOpsModePair = new Pair<>(appOpList, mode);
            List<AppOpCount> appOpCounts = new ArrayList<>();
            HashSet<String> packageNames = new HashSet<>();
            for (ApplicationInfo applicationInfo : PackageUtils.getAllApplications(MATCH_UNINSTALLED_PACKAGES
                    | PackageManagerCompat.MATCH_STATIC_SHARED_AND_SDK_LIBRARIES)) {
                if (packageNames.contains(applicationInfo.packageName)) {
                    continue;
                }
                packageNames.add(applicationInfo.packageName);
                if (ThreadUtils.isInterrupted()) return;
                if (!systemApps && (applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0)
                    continue;
                AppOpCount appOpCount = new AppOpCount();
                appOpCount.packageName = applicationInfo.packageName;
                appOpCount.packageLabel = applicationInfo.loadLabel(pm).toString();
                appOpCount.appOps = PackageUtils.getFilteredAppOps(applicationInfo.packageName,
                        UserHandleHidden.myUserId(), appOpList, mode);
                appOpCount.count = appOpCount.appOps.size();
                if (appOpCount.count > 0) appOpCounts.add(appOpCount);
            }
            this.appOpsCount.postValue(new Pair<>(appOpCounts, appOpsModePair));
        });
    }

    public void clearData() {
        if (futureResult != null) {
            futureResult.cancel(true);
        }
        futureResult = ThreadUtils.postOnBackgroundThread(() -> {
            HashSet<String> packageNames = new HashSet<>();
            for (ApplicationInfo applicationInfo : PackageUtils.getAllApplications(MATCH_UNINSTALLED_PACKAGES
                    | PackageManagerCompat.MATCH_STATIC_SHARED_AND_SDK_LIBRARIES)) {
                if (packageNames.contains(applicationInfo.packageName) || isInstalled(applicationInfo)) {
                    continue;
                }
                packageNames.add(applicationInfo.packageName);
                if (ThreadUtils.isInterrupted()) {
                    return;
                }
            }
            this.clearDataCandidates.postValue(new ArrayList<>(packageNames));
        });
    }

    @AnyThread
    public void trimCaches() {
        ThreadUtils.postOnBackgroundThread(() -> {
            long size = 1024L * 1024L * 1024L * 1024L;  // 1 TB
            try {
                // TODO: 30/8/21 Iterate all volumes?
                PackageManagerCompat.freeStorageAndNotify(null /* internal */, size,
                        StorageManagerCompat.FLAG_ALLOCATE_DEFY_ALL_RESERVED);
                this.trimCachesResult.postValue(true);
            } catch (RemoteException e) {
                this.trimCachesResult.postValue(false);
            }
        });
    }

    public void listAppsInstalledByAmForDexOpt() {
        ThreadUtils.postOnBackgroundThread(() -> {
            HashSet<String> packageNames = new HashSet<>();
            for (ApplicationInfo applicationInfo : PackageUtils.getAllApplications(0)) {
                if (packageNames.contains(applicationInfo.packageName)) {
                    continue;
                }
                packageNames.add(applicationInfo.packageName);
                if (ThreadUtils.isInterrupted()) {
                    return;
                }
            }
            appsInstalledByAmForDexOpt.postValue(packageNames.toArray(new String[0]));
        });
    }

    @NonNull
    private ItemCount getTrackerCountForApp(@NonNull PackageInfo packageInfo) {
        ItemCount trackerCount = new ItemCount();
        trackerCount.packageName = packageInfo.packageName;
        trackerCount.packageLabel = packageInfo.applicationInfo.loadLabel(pm).toString();
        trackerCount.count = ComponentUtils.getTrackerComponentsForPackage(packageInfo).size();
        return trackerCount;
    }

    private boolean isInstalled(@NonNull ApplicationInfo info) {
        return info.processName != null && Paths.exists(info.publicSourceDir);
    }
}
