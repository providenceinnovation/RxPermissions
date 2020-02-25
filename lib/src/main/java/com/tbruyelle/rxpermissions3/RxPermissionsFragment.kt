package com.tbruyelle.rxpermissions3

import android.annotation.TargetApi
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import io.reactivex.rxjava3.subjects.PublishSubject
import java.util.*

class RxPermissionsFragment : Fragment() {

    companion object {
        private const val PERMISSIONS_REQUEST_CODE = 42
    }

    // Contains all the current permission requests.
    // Once granted or denied, they are removed from it.
    private val subjects: MutableMap<String, PublishSubject<Permission>?> = HashMap()
    private var logging = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        retainInstance = true
    }

    @TargetApi(Build.VERSION_CODES.M)
    fun requestPermissions(vararg permissions: String) {
        requestPermissions(permissions, PERMISSIONS_REQUEST_CODE)
    }

    @TargetApi(Build.VERSION_CODES.M)
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode != PERMISSIONS_REQUEST_CODE) return

        val shouldShowRequestPermissionRationale = BooleanArray(permissions.size)
        for (index in permissions.indices) {
            shouldShowRequestPermissionRationale[index] = shouldShowRequestPermissionRationale(permissions[index])
        }

        onRequestPermissionsResult(grantResults, shouldShowRequestPermissionRationale, *permissions)
    }

    fun onRequestPermissionsResult(grantResults: IntArray, shouldShowRequestPermissionRationale: BooleanArray, vararg permissions: String) {
        permissions.forEachIndexed { index, permission ->
            log("onRequestPermissionsResult  $permission")

            // Find the corresponding subject
            val subject = subjects[permission]
            if (subject == null) { // No subject found
                Log.e(RxPermissions.TAG, "RxPermissions.onRequestPermissionsResult invoked but didn't find the corresponding permission request.")
                return
            }

            subjects.remove(permission)

            val granted = grantResults[index] == PackageManager.PERMISSION_GRANTED
            subject.onNext(Permission(permission, granted, shouldShowRequestPermissionRationale[index]))
            subject.onComplete()
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    fun isGranted(permission: String): Boolean =
            requireActivity().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    @TargetApi(Build.VERSION_CODES.M)
    fun isRevoked(permission: String): Boolean =
            requireActivity().packageManager.isPermissionRevokedByPolicy(permission, requireActivity().packageName)

    fun setLogging(logging: Boolean) {
        this.logging = logging
    }

    fun getSubjectByPermission(permission: String): PublishSubject<Permission>? = subjects[permission]

    fun containsByPermission(permission: String): Boolean = subjects.containsKey(permission)

    fun setSubjectForPermission(permission: String, subject: PublishSubject<Permission>) {
        subjects[permission] = subject
    }

    fun log(message: String?) {
        if (logging) {
            Log.d(RxPermissions.TAG, message)
        }
    }
}