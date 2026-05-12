package com.connect.medium.ui.main

import com.connect.medium.R
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavController
import com.connect.medium.databinding.ActivityMainBinding
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import com.connect.medium.ui.main.fragments.notifications.NotificationsViewModel
import com.connect.medium.ui.main.fragments.notifications.NotificationsViewModelFactory

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private lateinit var notificationsViewModel: NotificationsViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.main_nav_host) as NavHostFragment
        navController = navHostFragment.navController

        binding.bottomNav.setupWithNavController(navController)
        binding.bottomNav.menu.findItem(R.id.placeholder)?.apply {
            isEnabled = false
        }
        binding.fabCreate.setOnClickListener {
            navController.navigate(R.id.createPostFragment)
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            when (destination.id) {
                R.id.createPostFragment,
                R.id.settingsFragment,
                R.id.mediaPickerFragment,
                R.id.cameraFragment -> {
                    binding.fabCreate.hide()
                    binding.bottomNav.visibility = View.GONE
                }
                else -> {
                    binding.fabCreate.show()
                    binding.bottomNav.visibility = View.VISIBLE
                }
            }
        }
        setupNotificationBadge()
    }
    private fun setupNotificationBadge(){
        notificationsViewModel = ViewModelProvider(
            this,
            NotificationsViewModelFactory(application)
        )[NotificationsViewModel::class.java]

        notificationsViewModel.unreadCount.observe(this) { count ->
            val badge = binding.bottomNav.getOrCreateBadge(R.id.notificationsFragment)
            if (count > 0) {
                badge.isVisible = true
                badge.number = count
            } else {
                badge.isVisible = false
            }
        }
    }
}