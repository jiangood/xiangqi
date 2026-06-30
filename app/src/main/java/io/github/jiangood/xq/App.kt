package io.github.jiangood.xq

import android.app.Application
import io.github.jiangood.xq.util.GlobalExceptionHandler

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        GlobalExceptionHandler.init(this)
    }
}
