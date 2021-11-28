package com.example.camera

import android.os.Bundle
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        val imageWidthPreference: EditTextPreference? = findPreference("image_width")
        imageWidthPreference?.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_NUMBER
        }
        val imageHeightPreference: EditTextPreference? = findPreference("image_height")
        imageHeightPreference?.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_NUMBER
        }
        val exposureTimePreference: EditTextPreference? = findPreference("exposure_time")
        exposureTimePreference?.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_NUMBER
        }
        val frameDurationPreference: EditTextPreference? = findPreference("frame_duration")
        frameDurationPreference?.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_NUMBER
        }
        val sensitivityPreference: EditTextPreference? = findPreference("sensitivity")
        sensitivityPreference?.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_NUMBER
        }
        val timerPreference: EditTextPreference? = findPreference("timer_time")
        timerPreference?.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_NUMBER
        }
    }
}