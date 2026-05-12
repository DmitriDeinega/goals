package com.goals.app.widget

import android.content.Intent
import android.widget.RemoteViewsService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class WidgetHeaderRemoteViewsService : RemoteViewsService() {

    @Inject lateinit var cache: WidgetCache

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return WidgetHeaderRemoteViewsFactory(applicationContext, cache)
    }
}
