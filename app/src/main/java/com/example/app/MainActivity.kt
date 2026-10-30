@file:Suppress("SpellCheckingInspection")

package com.example.app

import android.os.Bundle
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.setPadding
import com.example.app.fragments.FirstFragment
import com.example.app.fragments.AmneziaVpnFragment
import com.example.app.fragments.GpgDecryptFragment
import com.example.app.fragments.NinthFragment
import com.example.app.fragments.SecondFragment
import com.example.app.fragments.SetupFragment
import com.example.app.fragments.SeventhFragment
import com.example.app.fragments.SixthFragment
import com.example.app.fragments.TenthFragment
import com.example.app.fragments.TerminalFragment
import com.example.app.fragments.ThirdFragment
import com.example.app.fragments.VideoPlayerFragment

class MainActivity : AppCompatActivity() {

    private val fragmentFactories = listOf(
        ::FirstFragment,
        ::AmneziaVpnFragment,
        ::SecondFragment,
        ::ThirdFragment,
        ::SixthFragment,
        ::SeventhFragment,
        ::NinthFragment,
        ::GpgDecryptFragment,
        ::TenthFragment,
        ::TerminalFragment,
        ::VideoPlayerFragment,
        ::SetupFragment
    )

    private val buttonTitles = listOf(
        "Первая", "Amnezia VPN", "Вторая", "Третья", "Git Clone", "Седьмая",
        "OpenSSL Decryptor", "GPG Decryptor", "Десятая", "Terminal", "Видео", "Настройка"
    )

    private var selectedButton: Button? = null
    private lateinit var buttonsContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }

        buttonsContainer = findViewById(R.id.buttonsContainer)

        savePackagesToFile("packages.txt")
        savePackagesGMSToFile("GMSpackages")

        setupActionButtons(savedInstanceState)

        if (savedInstanceState == null) {
            openFragment(0)
        }
    }

    // Обрабатываем жест на уровне Activity: ScrollView может перехватить MOVE
    // для прокрутки, но подсветка при этом всё равно должна следовать за пальцем.
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (::buttonsContainer.isInitialized) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                    updateSwipeHighlight(event.rawX, event.rawY)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    clearSwipeHighlight()
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val selectedIndex = selectedButton?.let { button ->
            buttonsContainer.indexOfChild(button)
        } ?: 0
        outState.putInt("SELECTED_BUTTON_INDEX", selectedIndex)
    }

    private fun setupActionButtons(savedInstanceState: Bundle?) {
        buttonsContainer.removeAllViews()

        val savedIndex = savedInstanceState?.getInt("SELECTED_BUTTON_INDEX", 0) ?: 0

        buttonTitles.forEachIndexed { index, title ->
            val button = Button(this).apply {
                text = title
                textSize = 13f
                minHeight = 0
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    36.dpToPx()
                ).apply {
                    setMargins(0, 0, 0, 4.dpToPx())
                }
                setPadding(10.dpToPx())
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.button_selector)
            }

            button.setOnClickListener {
                selectedButton?.isSelected = false
                button.isSelected = true
                selectedButton = button
                openFragment(index)
            }

            buttonsContainer.addView(button)

            if (index == savedIndex) {
                button.isSelected = true
                selectedButton = button
            }
        }

        if (savedInstanceState != null && savedIndex in fragmentFactories.indices) {
            openFragment(savedIndex)
        }
    }

    private fun updateSwipeHighlight(rawX: Float, rawY: Float) {
        for (i in 0 until buttonsContainer.childCount) {
            val child = buttonsContainer.getChildAt(i) as? Button ?: continue
            val location = IntArray(2)
            child.getLocationOnScreen(location)
            val isUnderFinger = rawX >= location[0] &&
                rawX < location[0] + child.width &&
                rawY >= location[1] &&
                rawY < location[1] + child.height
            child.isPressed = isUnderFinger
        }
    }

    private fun clearSwipeHighlight() {
        for (i in 0 until buttonsContainer.childCount) {
            buttonsContainer.getChildAt(i).isPressed = false
        }
    }

    private fun openFragment(index: Int) {
        if (index !in fragmentFactories.indices) return

        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragmentFactories[index].invoke())
            .setReorderingAllowed(true)
            .commit()
    }

    private fun Int.dpToPx(): Int =
        (this * resources.displayMetrics.density).toInt()
}