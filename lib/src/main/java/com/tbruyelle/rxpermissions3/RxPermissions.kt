/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tbruyelle.rxpermissions3

import android.annotation.TargetApi
import android.app.Activity
import android.os.Build
import android.text.TextUtils
import androidx.annotation.VisibleForTesting
import androidx.fragment.app.FragmentManager
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.ObservableSource
import io.reactivex.rxjava3.core.ObservableTransformer
import io.reactivex.rxjava3.functions.Function
import io.reactivex.rxjava3.subjects.PublishSubject

class RxPermissions(fragmentManager: FragmentManager) {

    @FunctionalInterface
    interface Lazy<V> {
        fun get(): V
    }

    companion object {
        @JvmField
        val TAG = RxPermissions::class.java.simpleName
        @JvmField
        val TRIGGER = Any()
    }

    @VisibleForTesting
    var rxPermissionsFragment: Lazy<RxPermissionsFragment> = object : Lazy<RxPermissionsFragment> {
        private var rxPermissionsFragment: RxPermissionsFragment? = null

        @Synchronized
        override fun get(): RxPermissionsFragment {
            if (rxPermissionsFragment == null) {
                rxPermissionsFragment = getRxPermissionsFragment(fragmentManager)
            }

            rxPermissionsFragment?.let { return it } ?: throw Throwable("missing fragment")
        }
    }


    private fun getRxPermissionsFragment(fragmentManager: FragmentManager): RxPermissionsFragment? {
        var rxPermissionsFragment = findRxPermissionsFragment(fragmentManager)

        if (rxPermissionsFragment == null) {
            rxPermissionsFragment = RxPermissionsFragment()
            fragmentManager
                    .beginTransaction()
                    .add(rxPermissionsFragment, TAG)
                    .commitNow()
        }

        return rxPermissionsFragment
    }

    private fun findRxPermissionsFragment(fragmentManager: FragmentManager): RxPermissionsFragment? =
            fragmentManager.findFragmentByTag(TAG) as RxPermissionsFragment?

    fun setLogging(logging: Boolean) {
        rxPermissionsFragment.get().setLogging(logging)
    }

    /**
     * Map emitted items from the source observable into `true` if permissions in parameters
     * are granted, or `false` if not.
     *
     *
     * If one or several permissions have never been requested, invoke the related framework method
     * to ask the user if he allows the permissions.
     */
    fun <T> ensure(vararg permissions: String): ObservableTransformer<T, Boolean> {
        return ObservableTransformer { o ->
            request(o, *permissions) // Transform Observable<Permission> to Observable<Boolean>
                    .buffer(permissions.size)
                    .flatMap(Function<List<Permission>, ObservableSource<Boolean>> { permissions ->
                        if (permissions.isEmpty()) {
                            // Occurs during orientation change, when the subject receives onComplete.
                            // In that case we don't want to propagate that empty list to the
                            // subscriber, only the onComplete.
                            return@Function Observable.empty<Boolean>()
                        }

                        // Return true if all permissions are granted.
                        permissions.firstOrNull { !it.granted }?.let { return@Function Observable.just(false) }
                        Observable.just(true)
                    })
        }
    }

    /**
     * Map emitted items from the source observable into [Permission] objects for each
     * permission in parameters.
     *
     *
     * If one or several permissions have never been requested, invoke the related framework method
     * to ask the user if he allows the permissions.
     */
    fun <T> ensureEach(vararg permissions: String): ObservableTransformer<T, Permission> {
        return ObservableTransformer { o -> request(o, *permissions) }
    }

    /**
     * Map emitted items from the source observable into one combined [Permission] object. Only if all permissions are granted,
     * permission also will be granted. If any permission has `shouldShowRationale` checked, than result also has it checked.
     *
     *
     * If one or several permissions have never been requested, invoke the related framework method
     * to ask the user if he allows the permissions.
     */
    fun <T> ensureEachCombined(vararg permissions: String): ObservableTransformer<T, Permission> {
        return ObservableTransformer { o ->
            request(o, *permissions)
                    .buffer(permissions.size)
                    .flatMap(Function<List<Permission>, ObservableSource<Permission>> { permissions ->
                        if (permissions.isEmpty()) {
                            Observable.empty()
                        } else Observable.just(Permission(permissions))
                    })
        }
    }

    /**
     * Request permissions immediately, **must be invoked during initialization phase
     * of your application**.
     */
    fun request(vararg permissions: String): Observable<Boolean> {
        return Observable.just(TRIGGER).compose(ensure(*permissions))
    }

    /**
     * Request permissions immediately, **must be invoked during initialization phase
     * of your application**.
     */
    fun requestEach(vararg permissions: String): Observable<Permission> {
        return Observable.just(TRIGGER).compose(ensureEach(*permissions))
    }

    /**
     * Request permissions immediately, **must be invoked during initialization phase
     * of your application**.
     */
    fun requestEachCombined(vararg permissions: String): Observable<Permission> {
        return Observable.just(TRIGGER).compose(ensureEachCombined(*permissions))
    }

    private fun request(trigger: Observable<*>?, vararg permissions: String): Observable<Permission> {
        require(permissions.isNotEmpty()) { "RxPermissions.request/requestEach requires at least one input permission" }
        return oneOf(trigger, pending(*permissions))
                .flatMap { requestImplementation(*permissions) }
    }

    private fun pending(vararg permissions: String): Observable<*> {
        permissions.firstOrNull { !rxPermissionsFragment.get().containsByPermission(it) }?.let {
            return Observable.empty<Any>()
        }

        return Observable.just(TRIGGER)
    }

    private fun oneOf(trigger: Observable<*>?, pending: Observable<*>): Observable<*> =
            if (trigger == null) {
                Observable.just(TRIGGER)
            } else Observable.merge(trigger, pending)

    @TargetApi(Build.VERSION_CODES.M)
    private fun requestImplementation(vararg permissions: String): Observable<Permission> {
        val list: MutableList<Observable<Permission>> = mutableListOf()
        val unrequestedPermissions: MutableList<String> = mutableListOf()

        // In case of multiple permissions, we create an Observable for each of them.
        // At the end, the observables are combined to have a unique response.
        for (permission in permissions) {
            rxPermissionsFragment.get().log("Requesting permission $permission")

            if (isGranted(permission)) { // Already granted, or not Android M
                // Return a granted Permission object.
                list.add(Observable.just(Permission(permission, granted = true, shouldShowRequestPermissionRationale = false)))
                continue
            }

            if (isRevoked(permission)) { // Revoked by a policy, return a denied Permission object.
                list.add(Observable.just(Permission(permission, granted = false, shouldShowRequestPermissionRationale = false)))
                continue
            }

            var subject = rxPermissionsFragment.get().getSubjectByPermission(permission)

            // Create a new subject if not exists
            if (subject == null) {
                unrequestedPermissions.add(permission)
                subject = PublishSubject.create()
                rxPermissionsFragment.get().setSubjectForPermission(permission, subject)
                list.add(subject)
            } else list.add(subject)
        }

        if (unrequestedPermissions.isNotEmpty()) {
            val unrequestedPermissionsArray = unrequestedPermissions.toTypedArray()
            requestPermissionsFromFragment(*unrequestedPermissionsArray)
        }

        return Observable.concat(Observable.fromIterable(list))
    }

    /**
     * Invokes Activity.shouldShowRequestPermissionRationale and wraps
     * the returned value in an observable.
     *
     *
     * In case of multiple permissions, only emits true if
     * Activity.shouldShowRequestPermissionRationale returned true for
     * all revoked permissions.
     *
     *
     * You shouldn't call this method if all permissions have been granted.
     *
     *
     * For SDK &lt; 23, the observable will always emit false.
     */
    fun shouldShowRequestPermissionRationale(activity: Activity, vararg permissions: String): Observable<Boolean> {
        return if (!isMarshmallow) {
            Observable.just(false)
        } else Observable.just(shouldShowRequestPermissionRationaleImplementation(activity, *permissions))
    }

    @TargetApi(Build.VERSION_CODES.M)
    private fun shouldShowRequestPermissionRationaleImplementation(activity: Activity, vararg permissions: String): Boolean =
            permissions.firstOrNull { !isGranted(it) && !activity.shouldShowRequestPermissionRationale(it) } == null


    @TargetApi(Build.VERSION_CODES.M)
    fun requestPermissionsFromFragment(vararg permissions: String) {
        rxPermissionsFragment.get().log("requestPermissionsFromFragment " + TextUtils.join(", ", permissions))
        rxPermissionsFragment.get().requestPermissions(*permissions)
    }

    /**
     * Returns true if the permission is already granted.
     *
     *
     * Always true if SDK &lt; 23.
     */
    fun isGranted(permission: String): Boolean {
        return !isMarshmallow || rxPermissionsFragment.get().isGranted(permission)
    }

    /**
     * Returns true if the permission has been revoked by a policy.
     *
     *
     * Always false if SDK &lt; 23.
     */
    fun isRevoked(permission: String): Boolean {
        return isMarshmallow && rxPermissionsFragment.get().isRevoked(permission)
    }

    val isMarshmallow: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

    fun onRequestPermissionsResult(vararg permissions: String, grantResults: IntArray) {
        rxPermissionsFragment.get().onRequestPermissionsResult(grantResults, BooleanArray(permissions.size), *permissions)
    }
}