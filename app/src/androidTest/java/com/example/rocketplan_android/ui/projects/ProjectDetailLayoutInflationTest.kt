package com.example.rocketplan_android.ui.projects

import android.view.LayoutInflater
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.rocketplan_android.R
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectDetailLayoutInflationTest {

    @Test
    fun inflateProjectDetailLayout_doesNotCrash() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        LayoutInflater.from(context).inflate(R.layout.fragment_project_detail, null, false)
    }

    @Test
    fun inflateRoomDetailLayout_doesNotCrash() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        LayoutInflater.from(context).inflate(R.layout.fragment_room_detail, null, false)
    }
}
