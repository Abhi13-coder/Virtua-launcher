package com.virtua.launcher

import android.app.Application
import com.virtua.launcher.core.BackendSelector
import com.virtua.launcher.features.FeatureRegistry
import com.virtua.launcher.ml.DepthEstimator
import com.virtua.launcher.ml.SuperResEstimator
import com.virtua.launcher.update.UpdateManager

class VirtuaApp : Application() {
    lateinit var featureRegistry: FeatureRegistry
        private set
    lateinit var updateManager: UpdateManager
        private set
    lateinit var backendSelector: BackendSelector
        private set
    lateinit var depthEstimator: DepthEstimator
        private set
    lateinit var superResEstimator: SuperResEstimator
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        backendSelector = BackendSelector(this)
        featureRegistry = FeatureRegistry(this)
        updateManager = UpdateManager(this, featureRegistry)
        depthEstimator = DepthEstimator(this)
        superResEstimator = SuperResEstimator(this)
        featureRegistry.bootstrapBundled()
    }

    companion object {
        @Volatile
        lateinit var instance: VirtuaApp
            private set
    }
}
