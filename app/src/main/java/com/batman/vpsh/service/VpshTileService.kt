package com.batman.vpsh.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.batman.vpsh.R
import com.batman.vpsh.data.RunState
import com.batman.vpsh.util.LocaleHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class VpshTileService : TileService() {

    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override fun onStartListening() {
        super.onStartListening()
        job = scope.launch {
            VpshBridge.runState.collect { state -> updateTile(state) }
        }
    }

    override fun onStopListening() {
        job?.cancel()
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, VpshService::class.java).setAction(VpshService.ACTION_TOGGLE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun updateTile(state: RunState) {
        val tile = qsTile ?: return
        val running = state == RunState.RUNNING || state == RunState.STARTING || state == RunState.PAUSED
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "VPSH"
        tile.subtitle = when (state) {
            RunState.RUNNING -> getString(R.string.tile_active)
            RunState.STARTING -> getString(R.string.tile_starting)
            RunState.PAUSED -> getString(R.string.tile_paused)
            RunState.ERROR -> getString(R.string.tile_error)
            RunState.STOPPED -> getString(R.string.tile_stopped)
        }
        tile.updateTile()
    }
}
