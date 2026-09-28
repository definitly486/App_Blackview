@file:Suppress("SpellCheckingInspection")

package com.example.app.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.app.BuildConfig
import com.example.app.R
import java.text.SimpleDateFormat
import java.util.*

class TenthFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val rootView = inflater.inflate(R.layout.fragment_tenth, container, false)

        // === Дата сборки ===
        val buildDate = Date(BuildConfig.BUILD_TIME)
        val formatter = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
        rootView.findViewById<TextView>(R.id.buildTimeText).text =
            "APK создан: ${formatter.format(buildDate)}"

        // === Версия приложения ===
        val packageInfo = requireContext().packageManager
            .getPackageInfo(requireContext().packageName, 0)
        rootView.findViewById<TextView>(R.id.versionNameText).text =
            "Версия приложения: ${packageInfo.versionName ?: "unknown"}"

        // === Ветка Git ===
        val branch = BuildConfig.GIT_BRANCH
        rootView.findViewById<TextView>(R.id.branchText).text =
            if (branch.isNotEmpty() && branch != "unknown") "Ветка Git: $branch" else "Ветка: release"

        // === НОВАЯ СТРОКА: Git-коммит (короткий хэш) ===
        val commitHash = BuildConfig.GIT_COMMIT_SHORT
        rootView.findViewById<TextView>(R.id.commitHashText).text = when {
            commitHash.isEmpty() || commitHash == "unknown" -> "Коммит: неизвестно"
            else -> "Коммит: $commitHash"
        }

        return rootView
    }
}
