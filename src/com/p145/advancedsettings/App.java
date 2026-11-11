package com.p145.advancedsettings;

import android.app.Application;
import android.content.Context;

public class App extends Application {

    private static Context ctx;

    @Override
    public void onCreate() {
        super.onCreate();
        ctx = this;
    }

    public static Context get() {
        return ctx;
    }
}
