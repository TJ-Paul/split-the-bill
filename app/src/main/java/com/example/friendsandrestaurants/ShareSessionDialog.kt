package com.example.friendsandrestaurants

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.DialogInterface
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Observer
import com.example.friendsandrestaurants.databinding.DialogShareSessionBinding
import com.example.friendsandrestaurants.share.NetworkAddresses
import com.example.friendsandrestaurants.share.QrCode
import com.example.friendsandrestaurants.share.ShareSession
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Shows the QR code and link for the shared bill, keeps them current as the phone's networks change
 * (e.g. the host turns on their hotspot), and lets the host stop sharing.
 */
class ShareSessionDialog(private val fragment: Fragment, private val onStopped: () -> Unit) {

    private val ctx = fragment.requireContext()
    // Captured now: the fragment's view (and its lifecycle) may be gone by the time the dialog closes.
    private val viewOwner = fragment.viewLifecycleOwner
    private val d = DialogShareSessionBinding.inflate(fragment.layoutInflater)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var dialog: AlertDialog

    private var addresses: List<NetworkAddresses.Address> = emptyList()
    private var selectedIp: String? = null
    private var shownQrUrl: String? = null

    private val stateObserver = Observer<ShareSession.State> { render() }

    private val addressRefresher = object : Runnable {
        override fun run() {
            refreshAddresses()
            handler.postDelayed(this, ADDRESS_REFRESH_MS)
        }
    }

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_DESTROY && dialog.isShowing) dialog.dismiss()
    }

    fun show() {
        dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.share_title)
            .setView(d.root)
            .setPositiveButton(R.string.share_done, null)
            .setNegativeButton(R.string.share_stop) { _, _ ->
                ShareSession.stop(ctx)
                onStopped()
            }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.setTextColor(ContextCompat.getColor(ctx, R.color.status_due))
        }
        dialog.setOnDismissListener {
            handler.removeCallbacks(addressRefresher)
            ShareSession.state.removeObserver(stateObserver)
            viewOwner.lifecycle.removeObserver(lifecycleObserver)
        }

        d.btnCopyLink.setOnClickListener { copyLink() }
        d.btnSendLink.setOnClickListener { sendLink() }
        d.btnHotspotSettings.setOnClickListener { openHotspotSettings() }
        d.btnRetry.setOnClickListener { ShareSession.start(ctx) }
        d.chipGroupNetworks.setOnCheckedStateChangeListener { group, checkedIds ->
            val chip = checkedIds.firstOrNull()?.let { group.findViewById<Chip>(it) } ?: return@setOnCheckedStateChangeListener
            selectedIp = chip.tag as? String
            render()
        }

        viewOwner.lifecycle.addObserver(lifecycleObserver)
        ShareSession.state.observe(viewOwner, stateObserver)
        handler.post(addressRefresher)
        dialog.show()
    }

    private fun currentUrl(): String? {
        val state = ShareSession.current
        if (state.status != ShareSession.Status.RUNNING) return null
        val ip = selectedIp ?: return null
        return NetworkAddresses.url(ip, state.port)
    }

    private fun refreshAddresses() {
        val found = NetworkAddresses.find()
        if (found == addresses) return
        addresses = found
        if (found.none { it.ip == selectedIp }) selectedIp = found.firstOrNull()?.ip
        rebuildChips()
        render()
    }

    private fun rebuildChips() {
        val group = d.chipGroupNetworks
        group.removeAllViews()
        group.isVisible = addresses.size > 1
        if (addresses.size <= 1) return
        addresses.forEach { address ->
            val chip = Chip(ctx).apply {
                id = View.generateViewId()
                tag = address.ip
                text = ctx.getString(R.string.share_chip_label, kindLabel(address), address.ip)
                isCheckable = true
                isChecked = address.ip == selectedIp
            }
            group.addView(chip)
        }
    }

    private fun kindLabel(address: NetworkAddresses.Address): String = when (address.kind) {
        NetworkAddresses.Kind.HOTSPOT -> ctx.getString(R.string.share_chip_hotspot)
        NetworkAddresses.Kind.WIFI -> ctx.getString(R.string.share_chip_wifi)
        NetworkAddresses.Kind.OTHER -> address.interfaceName
    }

    private fun render() {
        if (!::dialog.isInitialized) return
        val state = ShareSession.current
        if (state.status == ShareSession.Status.STOPPED) {
            // Stopped elsewhere, e.g. from the notification.
            if (dialog.isShowing) dialog.dismiss()
            return
        }

        val (statusText, textColor, background) = when (state.status) {
            ShareSession.Status.RUNNING -> Triple(
                if (state.viewers > 0) {
                    ctx.resources.getQuantityString(R.plurals.share_status_viewers, state.viewers, state.viewers)
                } else ctx.getString(R.string.share_status_live),
                R.color.status_refund, R.drawable.bg_pill_success
            )
            ShareSession.Status.FAILED -> Triple(ctx.getString(R.string.share_status_failed), R.color.status_due, R.drawable.bg_pill_due)
            else -> Triple(ctx.getString(R.string.share_status_starting), R.color.status_settled, R.drawable.bg_pill_settled)
        }
        d.tvShareStatus.text = statusText
        d.tvShareStatus.setTextColor(ContextCompat.getColor(ctx, textColor))
        (d.tvShareStatus.parent as View).setBackgroundResource(background)
        d.vStatusDot.isVisible = state.status == ShareSession.Status.RUNNING

        val running = state.status == ShareSession.Status.RUNNING
        val hasNetwork = addresses.isNotEmpty()
        d.groupFailed.isVisible = state.status == ShareSession.Status.FAILED
        d.groupNoNetwork.isVisible = running && !hasNetwork
        d.groupReady.isVisible = running && hasNetwork
        d.tvSteps.isVisible = state.status != ShareSession.Status.FAILED

        val url = currentUrl() ?: return
        d.tvUrl.text = url
        val address = addresses.firstOrNull { it.ip == selectedIp }
        d.tvNetworkLabel.text = when (address?.kind) {
            NetworkAddresses.Kind.HOTSPOT -> ctx.getString(R.string.share_network_hotspot)
            NetworkAddresses.Kind.WIFI -> ctx.getString(R.string.share_network_wifi)
            else -> ctx.getString(R.string.share_network_other, address?.interfaceName.orEmpty())
        }
        if (url != shownQrUrl) {
            shownQrUrl = url
            val bitmap = QrCode.bitmap(url, QR_SIZE_PX)
            // No smoothing: QR modules stay crisp when scaled.
            d.ivQr.setImageDrawable(BitmapDrawable(ctx.resources, bitmap).apply { isFilterBitmap = false })
        }
    }

    private fun copyLink() {
        val url = currentUrl() ?: return
        val clipboard = ctx.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(ctx.getString(R.string.share_link_clipboard_label), url))
        // Android 13+ shows its own confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(ctx, R.string.share_link_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendLink() {
        val url = currentUrl() ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, ctx.getString(R.string.share_link_message, url))
        }
        fragment.startActivity(Intent.createChooser(send, ctx.getString(R.string.share_send_link)))
    }

    private fun openHotspotSettings() {
        val candidates = listOf(
            Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in candidates) {
            try {
                fragment.startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                // try the next one
            } catch (e: SecurityException) {
                // not exported on this phone; try the next one
            }
        }
    }

    companion object {
        private const val ADDRESS_REFRESH_MS = 3_000L
        private const val QR_SIZE_PX = 720
    }
}
