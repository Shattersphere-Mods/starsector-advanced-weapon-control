package com.dp.advancedgunnerycontrol.gui.suggesttaggui

import com.dp.advancedgunnerycontrol.settings.Settings
import com.dp.advancedgunnerycontrol.typesandvalues.Values
import com.dp.advancedgunnerycontrol.utils.clearJsonMapFile
import com.dp.advancedgunnerycontrol.utils.readJsonMapFromFile
import com.dp.advancedgunnerycontrol.utils.saveJsonMapAsFile

fun backupSuggestedTagsToJson(){
    saveJsonMapAsFile(Values.CUSTOM_SUGGESTED_TAG_JSON_FILE_NAME, Settings.getCurrentSuggestedTags())
}

fun restoreSuggestedTagsFromJson(){
    Settings.customSuggestedTags = emptyMap()
    Settings.customSuggestedTags = readJsonMapFromFile(Values.CUSTOM_SUGGESTED_TAG_JSON_FILE_NAME)
}

fun clearSuggestedTagsToJson(){
    clearJsonMapFile(Values.CUSTOM_SUGGESTED_TAG_JSON_FILE_NAME)
}
