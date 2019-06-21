package com.oF2pks.applicationsinfo;

import android.content.pm.ApplicationInfo;

import com.oF2pks.applicationsinfo.utils.Tuple;

public class ApplicationItem {
    ApplicationInfo applicationInfo;
    String label;
    Long date;
    Long size = -1L;
    Tuple sha;
}
