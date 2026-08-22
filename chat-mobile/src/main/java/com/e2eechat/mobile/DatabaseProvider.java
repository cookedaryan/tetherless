package com.e2eechat.mobile;

import android.content.Context;

public class DatabaseProvider {
    public static AppDatabase getDatabase(Context context) {
        return AppDatabase.getInstance(context);
    }
}
