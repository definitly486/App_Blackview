@file:Suppress("SpellCheckingInspection")

package com.example.app

import android.content.Context
import android.os.Environment
import java.io.FileWriter
import java.io.IOException

/**
 * Функция для записи списка пакетов в указанный файл.
 *
 * @param org.bouncycastle.crypto.params.Blake3Parameters.context Контекст приложения
 * @param filename Название файла куда записать пакеты.
 */
fun Context.savePackagesToFile(filename: String): Boolean {
    val packages = listOf(
      "com.blackview.dokegamecenter",
"com.blackview.ai.imagex",
"com.blackview.ai.doki",
"com.blackview.surfline",
"com.blackview.ai.vidgen",
"com.blackview.ai.soundle",
"com.blackview.artorial.client",
"cn.wps.moffice_eng",
"com.sparktube",
"com.android.deskclock",
"com.google.android.youtube",
"com.google.android.apps.youtube.music",
"com.google.android.videos",
"com.google.android.apps.docs",
"com.google.android.apps.photos",
"com.google.android.apps.wellbeing",
"com.google.android.apps.tachyon",
"com.google.android.gm",
"com.google.android.apps.maps",
"com.google.android.apps.adm",
"com.google.android.apps.safetyhub",
"com.google.android.apps.bard",
"com.google.android.projection.gearhead",
"com.android.vending",
"com.android.soundrecorder",
"com.blackview.frozenapp",
"com.blackview.call.recorder",
"com.blackview.easytrans",
"com.blackview.searchcenter",
"com.blackview.appsedge",
"com.blackview.weather",
"com.blackview.call.recorder",
"com.blackview.note",
"com.blackview.useguide",
"com.blackview.userfeedback",
"com.blackview.gamemode",
"com.blackview.bvworkspace",
"com.blackview.filetrans",
"com.blackview.commuservice",
"com.blackview.cplog",
"com.blackview.dokegamecenter",
"com.blackview.helper",
"com.blackview.tool",
"com.google.android.googlequicksearchbox",
"com.google.android.overlay.gmsconfig.searchlauncherqs",
"com.google.android.overlay.gmsconfig.Velvet",
"com.google.ambient.streaming",
"com.google.android.printservice.recommendation",
"com.google.android.hotspot2.osulogin",
"com.google.android.configupdater",
"com.google.android.gms.supervision",
"com.google.android.as",
"com.google.android.as.oss",
"com.sprd.logmanager",
"com.sprd.validationtools",
"com.android.devicediagnostics",
"com.sprd.engineermode",
"com.android.bips",
"com.android.printspooler",
"com.google.android.marvin.talkback",
"com.google.android.accessibility.switchaccess",
"com.android.systemui.accessibility.accessibilitymenu",
"com.google.android.tts",
"com.android.DeviceAsWebcam",
"com.google.android.feedback",
"com.google.android.partnersetup",
"com.google.android.onetimeinitializer",
"com.google.android.apps.turbo",
"com.google.android.gms.location.history",
"com.google.android.ondevicepersonalization.services",
"com.google.android.federatedcompute",
"com.google.android.adservices.api",
"com.google.mainline.adservices",
"com.google.mainline.telemetry",
"com.google.android.apps.restore",
"com.android.egg",
"com.android.dreams.basic",
"com.android.dreams.phototable",
"com.android.musicfx",
"com.android.traceur",
"com.android.bookmarkprovider",
"com.android.providers.partnerbookmarks",
"com.android.partnerbrowsercustomizations.example",
"com.android.wallpaper.livepicker",
"com.incar.update",
"com.incar.agingmode",
"com.sprd.cameracalibration",
"com.sprd.camta",
"com.sprd.uasetting",
"com.sprd.omacp",
"com.sprd.linkturbo",
"com.tencent.soter.soterserver",
"org.ifaa.aidl.manager",
"com.blackview.applock",
"com.blackview.app.cloudfolder",
"com.blackview.smscode",
"com.blackview.themepicker",
"com.android.microdroid.empty_payload",
"com.android.compos.payload",
"com.android.virtualmachine.res",
"com.android.wallpaperbackup",
"com.android.sharedstoragebackup",
"com.android.localtransport",
"com.android.backupconfirm",
"com.android.stk",
"com.android.bluetoothmidiservice",
"com.android.dynsystem",
"com.android.soundpicker",
"com.blackview.apkupgrade",
"com.android.gallery3d",
"com.google.android.healthconnect.controller",
"com.google.android.gsf",
"com.google.android.health.connect.backuprestore",
"com.google.android.gmsintegration",
"com.android.credentialmanager",
"com.google.android.apps.messaging",
"com.google.android.dialer",
"com.android.chrome",
"com.google.android.setupwizard",
"com.google.android.calendar",
"com.google.android.apps.nbu.files",
"com.blackview.systemmanager",
"com.blackview.radioservice",
"com.unisoc.tui",
"com.google.android.tag",
"com.blackview.powersavemode",
"com.google.android.inputmethod.latin",
"com.google.android.contacts",
"com.android.nfc",
"com.blackview.wallpaperpicker",
"com.google.android.apps.carrier.carrierwifi",
"com.google.android.cellbroadcastreceiver",
"com.android.cellbroadcastreceiver",
"com.android.cellbroadcast.overlay",
"com.android.launcher3.overlay.wallpaper_picker",
"com.blackview.theme.config",
"com.blackview.wallpaperpicker.overlay",
"com.blackview.launcher.overlay.wallpaper_picker",
"com.blackview.framework.overlay.wallpaper_picker",
"com.blackview.framework.overlay.common",
"com.blackview.framework.overlay.dislpay_count",
"com.android.customization.themes",
"com.android.avatarpicker",
"com.android.wallpapercropper",
"com.google.android.appsearch.apk",
"com.blackview.theme.icon.oil",
"com.blackview.theme.icon.koi",
"com.blackview.theme.icon.vortex",
"com.blackview.theme.icon.clearwave",
"com.blackview.theme.icon.blance"

    ).joinToString("\n")

    val downloadFolder = this.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
    if (downloadFolder != null && !downloadFolder.exists()) {
        downloadFolder.mkdirs()
    }

    val fullPath = "${downloadFolder?.absolutePath}/$filename"

    return try {
        FileWriter(fullPath).use { it.write(packages) }
        true
    } catch (_: IOException) {
        false
    }
}

fun Context.savePackagesGMSToFile(filename: String): Boolean {
    val packages = listOf(
        "com.google.android.overlay.gmsconfig.go",
        "com.google.android.overlay.gmsconfig.geotz",
        "com.google.android.overlay.gmsconfig.common",
        "com.google.android.overlay.gmsconfig.gallerygo",
     //   "com.google.android.gms",
        "com.google.android.overlay.gmsconfig.personalsafety",
        "com.google.android.gsf"

    ).joinToString("\n")

    val downloadFolder = this.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
    if (downloadFolder != null && !downloadFolder.exists()) {
        downloadFolder.mkdirs()
    }

    val fullPath = "${downloadFolder?.absolutePath}/$filename"

    return try {
        FileWriter(fullPath).use { it.write(packages) }
        true
    } catch (_: IOException) {
        false
    }
}