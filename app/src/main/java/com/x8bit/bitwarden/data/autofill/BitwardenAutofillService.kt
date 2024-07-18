package com.x8bit.bitwarden.data.autofill

import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.service.autofill.SavedDatasetsInfoCallback
import android.util.Log
import androidx.annotation.Keep
import com.x8bit.bitwarden.data.autofill.model.AutofillAppInfo
import com.x8bit.bitwarden.data.autofill.processor.AutofillProcessor
import com.x8bit.bitwarden.data.platform.annotation.OmitFromCoverage
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * The [AutofillService] implementation for the app. This fulfills autofill requests from other
 * applications.
 */
@Suppress("TooManyFunctions")
@OmitFromCoverage
@Keep
@AndroidEntryPoint
class BitwardenAutofillService : AutofillService() {

    /**
     * A processor to handle the autofill fulfillment. We want to keep this service light because
     * it isn't easily tested.
     */
    @Inject
    lateinit var processor: AutofillProcessor

    /**
     * App information for the autofill feature.
     */
    private val autofillAppInfo: AutofillAppInfo
        get() = AutofillAppInfo(
            context = applicationContext,
            packageName = packageName,
            sdkInt = Build.VERSION.SDK_INT,
        )

    override fun onCreate() {
        Log.d("BitwardenAutofillService", "onCreate")
        super.onCreate()
    }

    override fun onConnected() {
        Log.d("BitwardenAutofillService", "onConnected")
        super.onConnected()
    }

    override fun onRebind(intent: Intent?) {
        Log.d("BitwardenAutofillService", "onRebind")
        super.onRebind(intent)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d("BitwardenAutofillService", "onUnbind")
        return super.onUnbind(intent)
    }

    override fun onDisconnected() {
        Log.d("BitwardenAutofillService", "onDisconnected")
        super.onDisconnected()
    }

    override fun onDestroy() {
        Log.d("BitwardenAutofillService", "onDestroy")
        super.onDestroy()
    }

    override fun bindService(
        service: Intent,
        flags: BindServiceFlags,
        executor: Executor,
        conn: ServiceConnection,
    ): Boolean {
        Log.d("BitwardenAutofillService", "bindService (1) flags=$flags")
        return super.bindService(service, flags, executor, conn)
    }

    override fun bindService(
        service: Intent,
        conn: ServiceConnection,
        flags: BindServiceFlags,
    ): Boolean {
        Log.d("BitwardenAutofillService", "bindService (2) flags=$flags")
        return super.bindService(service, conn, flags)
    }

    override fun bindService(
        service: Intent,
        flags: Int,
        executor: Executor,
        conn: ServiceConnection,
    ): Boolean {
        Log.d("BitwardenAutofillService", "bindService (3) flags=$flags")
        return super.bindService(service, flags, executor, conn)
    }

    override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
        Log.d("BitwardenAutofillService", "bindService (4) flags=$flags")
        return super.bindService(service, conn, flags)
    }

    override fun unbindService(conn: ServiceConnection) {
        Log.d("BitwardenAutofillService", "unbindService")
        super.unbindService(conn)
    }

    override fun onSavedDatasetsInfoRequest(callback: SavedDatasetsInfoCallback) {
        Log.d("BitwardenAutofillService", "onSavedDatasetsInfoRequest")
        super.onSavedDatasetsInfoRequest(callback)
    }

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        fillCallback: FillCallback,
    ) {
        Log.d("BitwardenAutofillService", "onFillRequest")
        processor.processFillRequest(
            autofillAppInfo = autofillAppInfo,
            cancellationSignal = cancellationSignal,
            fillCallback = fillCallback,
            request = request,
        )
    }

    override fun onSaveRequest(
        saverRequest: SaveRequest,
        saveCallback: SaveCallback,
    ) {
        Log.d("BitwardenAutofillService", "onSaveRequest")
        processor.processSaveRequest(
            autofillAppInfo = autofillAppInfo,
            request = saverRequest,
            saveCallback = saveCallback,
        )
    }
}
