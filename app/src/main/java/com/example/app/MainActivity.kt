@file:Suppress("SpellCheckingInspection")

package com.example.app

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import com.example.app.fragments.FirstFragment
import com.example.app.fragments.GpgDecryptFragment
import com.example.app.fragments.NinthFragment
import com.example.app.fragments.SecondFragment
import com.example.app.fragments.SetupFragment
import com.example.app.fragments.SeventhFragment
import com.example.app.fragments.SixthFragment
import com.example.app.fragments.TenthFragment
import com.example.app.fragments.TerminalFragment
import com.example.app.fragments.ThirdFragment

class MainActivity : AppCompatActivity() {

    private val fragmentList = listOf(
        FirstFragment(),
        SecondFragment(),
        ThirdFragment(),
        SixthFragment(),
        SeventhFragment(),
        NinthFragment(),
        GpgDecryptFragment(),
        TenthFragment(),
        TerminalFragment(),
        SetupFragment()
    )

    private val buttonTitles = listOf(
        "Первая", "Вторая", "Третья", "Git Clone", "Седьмая",
        "OpenSSL Decryptor", "GPG Decryptor", "Десятая", "Terminal", "Настройка"
    )

    private var selectedButton: Button? = null
    private lateinit var buttonsContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 15 (targetSdk 35): edge-to-edge включён принудительно — обрабатываем insets сами
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
            openFragment(fragmentList[0], buttonTitles[0])
        }
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
                openFragment(fragmentList[index], title)
            }

            buttonsContainer.addView(button)

            if (index == savedIndex) {
                button.isSelected = true
                selectedButton = button
            }
        }

        if (savedIndex in fragmentList.indices) {
            openFragment(fragmentList[savedIndex], buttonTitles[savedIndex])
        }
    }

    private fun openFragment(fragment: Fragment, title: String) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .setReorderingAllowed(true)
            .addToBackStack(title)
            .commit()
    }

    private fun Int.dpToPx(): Int =
        (this * resources.displayMetrics.density).toInt()
}
