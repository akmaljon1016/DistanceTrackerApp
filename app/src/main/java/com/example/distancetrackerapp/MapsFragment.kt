package com.example.distancetrackerapp

import android.annotation.SuppressLint
import android.content.Context.LOCATION_SERVICE
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.location.LocationManager
import androidx.fragment.app.Fragment

import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getSystemService
import androidx.core.content.getSystemService
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.distancetrackerapp.ExtensionFunctions.disable
import com.example.distancetrackerapp.ExtensionFunctions.enable
import com.example.distancetrackerapp.ExtensionFunctions.hide
import com.example.distancetrackerapp.ExtensionFunctions.show
import com.example.distancetrackerapp.databinding.FragmentMapsBinding
import com.example.distancetrackerapp.maps.MapUtils
import com.example.distancetrackerapp.maps.MapUtils.calculateElapsedTime
import com.example.distancetrackerapp.maps.MapUtils.calculateTheDistance
import com.example.distancetrackerapp.model.Result
import com.example.distancetrackerapp.service.TrackerService
import com.example.distancetrackerapp.utils.Constants.ACTION_SERVICE_START
import com.example.distancetrackerapp.utils.Constants.ACTION_SERVICE_STOP
import com.example.distancetrackerapp.utils.Permissions.hasBackgroundLocationPermission
import com.example.distancetrackerapp.utils.Permissions.requestBackgroundLocationPermission
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices

import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.vmadalin.easypermissions.EasyPermissions
import com.vmadalin.easypermissions.dialogs.SettingsDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MapsFragment : Fragment(), OnMapReadyCallback, GoogleMap.OnMyLocationButtonClickListener,
    GoogleMap.OnMarkerClickListener,
    EasyPermissions.PermissionCallbacks {

    private var _binding: FragmentMapsBinding? = null
    private val binding get() = _binding!!
    private lateinit var map: GoogleMap
    var locationmanager: LocationManager? = null

    val started = MutableLiveData(false)

    private var startTime = 0L
    private var stopTime = 0L

    private var locationList = mutableListOf<LatLng>()
    private var polyLineList = mutableListOf<Polyline>()
    private var markerList = mutableListOf<Marker>()
    private lateinit var fusedLocationProviderClient: FusedLocationProviderClient

    private val mapUtils by lazy { MapUtils }
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentMapsBinding.inflate(inflater, container, false)
        binding.lifecycleOwner = this
        binding.tracking = this
        locationmanager = requireActivity().getSystemService(LOCATION_SERVICE) as LocationManager

        binding.startButton.setOnClickListener { onStartButtonClick() }
        binding.stopButton.setOnClickListener {
            onStopButtonClicked()
            binding.startButton.hide()
            binding.startButton.show()
        }
        binding.resetButton.setOnClickListener {
            onResetButtonClicked()
        }
        fusedLocationProviderClient =
            LocationServices.getFusedLocationProviderClient(requireActivity())
        return binding.root
    }

    private fun onResetButtonClicked() {
        mapReset()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val mapFragment = childFragmentManager.findFragmentById(R.id.map) as SupportMapFragment?
        mapFragment?.getMapAsync(this)
    }

    @SuppressLint("MissingPermission")
    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap!!
        map.isMyLocationEnabled = true
        map.uiSettings.apply {
            isZoomGesturesEnabled = false
            isZoomControlsEnabled = false
            isRotateGesturesEnabled = false
            isTiltGesturesEnabled = false
            isCompassEnabled = false
            isScrollGesturesEnabled = false
        }
        map.setOnMyLocationButtonClickListener(this)
        map.setOnMarkerClickListener(this)
        observeTrackerService()
    }

    private fun observeTrackerService() {
        TrackerService.locationList.observe(viewLifecycleOwner, {
            if (it != null) {
                locationList = it
                drawPolyline()
                followPolyline()
                Log.d("LocationList", locationList.toString())
                if (locationList.size > 1) {
                    binding.stopButton.enable()
                }
            }
        })
        TrackerService.started.observe(viewLifecycleOwner, {
            started.value = it
        })
        TrackerService.startTime.observe(viewLifecycleOwner, {
            startTime = it
        })
        TrackerService.stopTime.observe(viewLifecycleOwner, {
            stopTime = it
            Log.d("points", stopTime.toString())
            if (stopTime != 0L) {
                showBiggerPicture()
                displayResult()
            }
        })
    }

    private fun showBiggerPicture() {
        val bounds = LatLngBounds.Builder()
        for (location in locationList) {
            bounds.include(location)
            Log.d("points", location.toString())
        }
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 100), 2000, null)
        addMarker(locationList.first())
        addMarker(locationList.last())
    }

    private fun addMarker(position: LatLng) {
        val marker = map.addMarker(MarkerOptions().position(position))
        markerList.add(marker)
    }


    private fun drawPolyline() {
        val polyline = map.addPolyline(
            PolylineOptions().apply {
                width(10f)
                color(Color.BLUE)
                jointType(JointType.ROUND)
                startCap(ButtCap())
                endCap(ButtCap())
                addAll(locationList)
            }
        )
        polyLineList.add(polyline)
    }

    fun followPolyline() {
        if (locationList.isNotEmpty()) {
            map.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    mapUtils.setCameraPosition(
                        locationList.last()
                    )
                ), 1000, null
            )
        }
    }

    private fun onStartButtonClick() {
        if (hasBackgroundLocationPermission(requireContext())) {
            startCountDown()
            binding.startButton.disable()
            binding.startButton.hide()
            binding.stopButton.show()

        } else {
            requestBackgroundLocationPermission(this)
        }
    }

    private fun onStopButtonClicked() {
        stopForeGroundService()
    }

    private fun stopForeGroundService() {
        binding.startButton.disable()
        sendActionCommandToService(ACTION_SERVICE_STOP)
    }

    private fun startCountDown() {
        binding.timerTextview.show()
        binding.stopButton.disable()
        val timer: CountDownTimer = object : CountDownTimer(4000, 1000) {
            override fun onTick(millisUntillFinished: Long) {
                val currentSeconds = millisUntillFinished / 1000
                if (currentSeconds.toString() == "0") {
                    binding.timerTextview.text = "GO"
                    binding.timerTextview.setTextColor(
                        ContextCompat.getColor(
                            requireContext(),
                            R.color.black
                        )
                    )
                } else {
                    binding.timerTextview.text = currentSeconds.toString()
                    binding.timerTextview.setTextColor(
                        ContextCompat.getColor(
                            requireContext(),
                            R.color.red
                        )
                    )
                }
            }

            override fun onFinish() {
                sendActionCommandToService(ACTION_SERVICE_START)
                binding.timerTextview.hide()
            }
        }
        timer.start()
    }

    private fun sendActionCommandToService(action: String) {
        Intent(requireContext(), TrackerService::class.java).apply {
            this.action = action
            requireContext().startService(this)
        }
    }

    private fun displayResult() {
        val result = Result(
            calculateTheDistance(locationList),
            calculateElapsedTime(startTime, stopTime)
        )
        lifecycleScope.launchWhenCreated {
            delay(2500)
            val directions = MapsFragmentDirections.actionMapsFragmentToResultFragment(result)
            findNavController().navigate(directions)
            binding.startButton.apply {
                hide()
                enable()
            }
            binding.stopButton.hide()
            binding.resetButton.show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun mapReset() {
        fusedLocationProviderClient.lastLocation.addOnCompleteListener {
            val lastKnownLocation = LatLng(
                it.result!!.latitude,
                it.result!!.longitude
            )
            for (polyLine in polyLineList) {
                polyLine.remove()
            }
            map.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    mapUtils.setCameraPosition(
                        lastKnownLocation
                    )
                )
            )
            locationList.clear()
            for (marker in markerList) {
                marker.remove()
            }
            binding.resetButton.hide()
            binding.startButton.show()
        }
    }

    override fun onMyLocationButtonClick(): Boolean {
        if (locationmanager!!.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            binding.hintTextview.animate().alpha(0f).duration = 1500
            lifecycleScope.launch {
                delay(2500)
                binding.hintTextview.hide()
                binding.startButton.show()
            }

        } else {
            showGPSDisabledAlertToUser()
        }
        return false
    }

    override fun onPermissionsDenied(requestCode: Int, perms: List<String>) {
        if (EasyPermissions.somePermissionPermanentlyDenied(this, perms[0])) {
            SettingsDialog.Builder(requireActivity()).build().show()
        } else {
            onStartButtonClick()
        }
    }

    override fun onPermissionsGranted(requestCode: Int, perms: List<String>) {

    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onMarkerClick(p0: Marker): Boolean {
        return true
    }

    private fun showGPSDisabledAlertToUser() {
        val alertDialog: AlertDialog.Builder = AlertDialog.Builder(requireActivity())
        alertDialog.setMessage("GPS is disabled in your device.Would you like to enable it")
            .setCancelable(false)
            .setPositiveButton("Goto settings Page to Enable GPS",
                object : DialogInterface.OnClickListener {
                    override fun onClick(p0: DialogInterface?, p1: Int) {
                        val intent =
                            Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                        startActivity(intent)
                    }
                })
            .setNegativeButton("Cancel", object : DialogInterface.OnClickListener {
                override fun onClick(p0: DialogInterface?, p1: Int) {
                    p0?.cancel()
                }
            })
        val alert: AlertDialog = alertDialog.create()
        alert.show()
    }
}


