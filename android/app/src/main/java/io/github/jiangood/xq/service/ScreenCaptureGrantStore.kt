package io.github.jiangood.xq.service

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

class ScreenCaptureGrantStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("screen_capture", Context.MODE_PRIVATE)

    fun saveGrant(resultCode: Int, data: Intent) {
        prefs.edit()
            .putInt(KEY_RESULT_CODE, resultCode)
            .putString(KEY_DATA_URI, data.toUri(0))
            .apply()
    }

    fun loadGrant(): Pair<Int, Intent>? {
        val resultCode = prefs.getInt(KEY_RESULT_CODE, -1)
        val dataUri = prefs.getString(KEY_DATA_URI, null) ?: return null
        if (resultCode == -1) return null
        return try {
            val intent = Intent.parseUri(dataUri, 0)
            Pair(resultCode, intent)
        } catch (e: Exception) {
            clearGrant()
            null
        }
    }

    fun clearGrant() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_RESULT_CODE = "capture_result_code"
        private const val KEY_DATA_URI = "capture_data_uri"
    }
}
