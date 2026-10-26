package com.poringpet

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class PetQuickTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        syncTile()
    }
    override fun onClick() {
        super.onClick()
        if (!Settings.canDrawOverlays(this)) {
            startActivityAndCollapse(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if (PetOverlayService.isEnabled(this)) PetOverlayService.stop(this)
        else {
            val intent = Intent(this, PetOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        }
        syncTile()
    }
    private fun syncTile() {
        qsTile?.apply {
            state = if (PetOverlayService.isEnabled(this@PetQuickTileService)) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Poring Pet"
            subtitle = if (PetOverlayService.isEnabled(this@PetQuickTileService)) "Visible" else "Hidden"
            updateTile()
        }
    }
}
