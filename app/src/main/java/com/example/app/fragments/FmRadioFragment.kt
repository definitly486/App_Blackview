package com.example.app.fragments

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.app.R
import com.example.app.fmradio.FmRadioService
import java.util.Locale

class FmRadioFragment : Fragment() {
    private lateinit var frequencyView: TextView
    private lateinit var statusView: TextView
    private lateinit var powerButton: Button
    private lateinit var frequencySeek: SeekBar
    private var frequency = 8750
    private var powered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != FmRadioService.ACTION_STATE) return
            frequency = intent.getIntExtra(FmRadioService.EXTRA_FREQUENCY, frequency)
            powered = intent.getBooleanExtra("powered", powered)
            val status = intent.getStringExtra("status")
            updateUi(status)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_fm_radio, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        frequencyView = view.findViewById(R.id.fmFrequency)
        statusView = view.findViewById(R.id.fmStatus)
        powerButton = view.findViewById(R.id.fmPower)
        frequencySeek = view.findViewById(R.id.fmFrequencySeek)

        frequencySeek.max = 10800 - 8750
        frequencySeek.progress = frequency - 8750
        frequencySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    frequency = 8750 + progress
                    updateFrequency()
                }
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) { sendTune() }
        })

        powerButton.setOnClickListener { send(FmRadioService.ACTION_POWER) }
        view.findViewById<Button>(R.id.fmPrev).setOnClickListener { send(FmRadioService.ACTION_SEEK_DOWN) }
        view.findViewById<Button>(R.id.fmNext).setOnClickListener { send(FmRadioService.ACTION_SEEK_UP) }
        view.findViewById<Button>(R.id.fmScan).setOnClickListener { send(FmRadioService.ACTION_SCAN) }
        view.findViewById<Button>(R.id.fmMinus).setOnClickListener { setFrequency(frequency - 10) }
        view.findViewById<Button>(R.id.fmPlus).setOnClickListener { setFrequency(frequency + 10) }

        updateUi("Выключено")
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(FmRadioService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.registerReceiver(requireContext(), receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            requireContext().registerReceiver(receiver, filter)
        }
        startService()
    }

    override fun onStop() {
        try { requireContext().unregisterReceiver(receiver) } catch (_: IllegalArgumentException) {}
        super.onStop()
    }

    private fun startService() {
        val intent = Intent(requireContext(), FmRadioService::class.java)
        if (Build.VERSION.SDK_INT >= 26) requireContext().startForegroundService(intent)
        else requireContext().startService(intent)
    }

    private fun send(action: String) {
        val intent = Intent(requireContext(), FmRadioService::class.java).setAction(action)
        if (Build.VERSION.SDK_INT >= 26) requireContext().startForegroundService(intent)
        else requireContext().startService(intent)
    }

    private fun sendTune() {
        val intent = Intent(requireContext(), FmRadioService::class.java)
            .setAction(FmRadioService.ACTION_TUNE)
            .putExtra(FmRadioService.EXTRA_FREQUENCY, frequency)
        if (Build.VERSION.SDK_INT >= 26) requireContext().startForegroundService(intent)
        else requireContext().startService(intent)
    }

    private fun setFrequency(value: Int) {
        frequency = value.coerceIn(8750, 10800)
        updateFrequency()
        sendTune()
    }

    private fun updateFrequency() {
        frequencyView.text = String.format(Locale.US, "%.1f MHz", frequency / 100.0)
        frequencySeek.progress = frequency - 8750
    }

    private fun updateUi(status: String?) {
        updateFrequency()
        powerButton.text = if (powered) "ВЫКЛЮЧИТЬ FM" else "ВКЛЮЧИТЬ FM"
        statusView.text = status ?: if (powered) "FM работает в фоне" else "Выключено"
    }
}
