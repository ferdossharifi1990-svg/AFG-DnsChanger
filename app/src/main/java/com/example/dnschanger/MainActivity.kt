package com.example.dnschanger

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var spinner: Spinner
    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button
    private var isRunning = false

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpn()
        } else {
            statusText.text = "دسترسی VPN رد شد"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spinner = findViewById(R.id.providerSpinner)
        statusText = findViewById(R.id.statusText)
        toggleButton = findViewById(R.id.toggleButton)

        val names = DnsProviders.ALL.map { "${it.name} — ${it.primary}" }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)

        toggleButton.setOnClickListener {
            if (isRunning) {
                stopVpn()
            } else {
                requestVpnPermission()
            }
        }
    }

    private fun requestVpnPermission() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            startVpn()
        }
    }

    private fun startVpn() {
        val selected = DnsProviders.ALL[spinner.selectedItemPosition]
        val intent = Intent(this, DnsVpnService::class.java).apply {
            action = DnsVpnService.ACTION_START
            putExtra(DnsVpnService.EXTRA_PRIMARY_DNS, selected.primary)
            putExtra(DnsVpnService.EXTRA_SECONDARY_DNS, selected.secondary)
        }
        startService(intent)
        isRunning = true
        statusText.text = getString(R.string.status_running) + " (${selected.name})"
        toggleButton.text = getString(R.string.btn_stop)
    }

    private fun stopVpn() {
        val intent = Intent(this, DnsVpnService::class.java).apply {
            action = DnsVpnService.ACTION_STOP
        }
        startService(intent)
        isRunning = false
        statusText.text = getString(R.string.status_stopped)
        toggleButton.text = getString(R.string.btn_start)
    }
}
