package com.sanora.giltune

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.Gear
import ai.pleos.playground.vehicle.api.Wiper
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * `pleos` flavor: direct calls to the official SDKs (FusedLocation 1.1.3, NaviHelper 3.0.5,
 * Vehicle 2.3.2). Only classes/methods present in the downloaded AARs and the docs are used:
 *  - FusedLocation: isSupported/initialize/registerFusedLocationCallback/unregister/release
 *    https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/initialization-and-release
 *  - NaviHelper: initialize/addListener/removeListener/release, onCurrentLocationInfo, onCurrentRoadSpeedLimit,
 *    onRouteStarted/onRouteEnded/onRouteCancelled, onTBTInfo (+ getTBTInfo poll while a route is active)
 *    https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/event-listeners
 *  - Vehicle: initialize/getGear/getWiper/release (permissions CAR_DRIVE / CAR_WINDOWS per AAR @RequiresPermission)
 *    https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/obtain-domain-object
 * Runtime behaviour on a Pleos image is NOT verified yet.
 */
class PleosSdkBridge(context: Context, private val cb: PleosCallbacks) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var fused: FusedLocation? = null
    private var fusedListener: FusedLocationListener? = null
    private var navi: NaviHelper? = null
    private var naviListener: NaviHelperEventListener? = null
    private var vehicle: Vehicle? = null
    private var gear: Gear? = null
    private var gearListener: GearSelectionListener? = null
    private var wiper: Wiper? = null
    private var wiperListener: WiperStateListener? = null
    private val wiperArea = WindowArea(WindowValue.FRONT_WINDSHIELD)

    private fun status(group: String, message: String) = main.post { cb.onStatus(group, message) }
    private fun fail(group: String, e: Throwable) {
        Log.w("GILTUNE_PLEOS", "$group: ${e.message}")
        status(group, "$group 오류: ${e.javaClass.simpleName}")
    }

    fun start() { startFused(); startNavi(); startVehicle() }

    private fun startFused() {
        try {
            val f = FusedLocation(app)
            if (!f.isSupported()) { status("Fused", "FusedLocation 미지원 · Android GPS 대체"); return }
            f.initialize()
            val l = object : FusedLocationListener {
                override fun onChanged(data: LocationData?) {
                    if (data == null) return  // docs: handle null when location is blocked
                    // LocationData has 'confidence', not meters accuracy -> accuracyKnown=false.
                    val s = PositionSample(data.latitude, data.longitude, 35f, accuracyKnown = false)
                    main.post { cb.onLocation(s) }
                }
                override fun onError(e: Exception) { fail("Fused", e) }
            }
            f.registerFusedLocationCallback(l)
            fused = f; fusedListener = l
            status("Fused", "FusedLocation 수신 대기")
        } catch (e: Throwable) { fail("Fused", e); stopFused() }
    }

    private fun stopFused() {
        val f = fused ?: return
        try { fusedListener?.let { f.unregisterFusedLocationCallback(it) }; f.release() }
        catch (e: Throwable) { Log.w("GILTUNE_PLEOS", "fused release: ${e.message}") }
        fused = null; fusedListener = null
    }

    private fun startNavi() {
        try {
            val n = NaviHelper(app)
            val l = object : NaviHelperEventListener {
                override fun onCurrentLocationInfo(info: CurrentLocationInfo) {
                    val address = info.address
                    main.post { cb.onRoadAddress(address) }
                }
                override fun onCurrentRoadSpeedLimit(speedLimit: Int) { main.post { cb.onSpeedLimit(speedLimit) } }
                override fun onRouteStarted(info: RouteStartInfo) {
                    routeActive = true; status("Navi", "경로 안내 시작 · 앞 구간 예측 중")
                    main.post { pollTbt() }
                }
                override fun onRouteEnded() { routeEnded() }
                override fun onRouteCancelled() { routeEnded() }
                override fun onTBTInfo(list: List<TBTInfo>) {
                    val next = RoutePredictor.next(list.map { RoutePredictor.Tbt(it.type.name, it.distance, it.description) })
                    main.post { cb.onUpcoming(next) }
                    if (list.isNotEmpty() && !routeActive) { routeActive = true; main.post { pollTbt() } }
                }
                override fun onError(errorCode: NaviErrorCode) { status("Navi", "NaviHelper 오류: $errorCode") }
            }
            navi = n; naviListener = l
            n.initialize({
                n.addListener(l)
                try { n.getTBTInfo() } catch (e: Throwable) { fail("Navi", e) } // a route may already be running
                status("Navi", "NaviHelper 연결 · 도로 정보 대기")
            }, { e -> fail("Navi", e) })
        } catch (e: Throwable) { fail("Navi", e) }
    }

    @Volatile private var routeActive = false
    private val tbtPoll = Runnable { pollTbt() }
    /** Ask for fresh TBT while a route is active (result arrives in onTBTInfo). */
    private fun pollTbt() {
        main.removeCallbacks(tbtPoll)
        if (!routeActive) return
        try { navi?.getTBTInfo() } catch (e: Throwable) { fail("Navi", e) }
        main.postDelayed(tbtPoll, 5_000L)
    }
    private fun routeEnded() {
        routeActive = false; main.removeCallbacks(tbtPoll); main.post { cb.onUpcoming(null) }
        status("Navi", "NaviHelper 연결 · 경로 없음")
    }

    private fun startVehicle() {
        try {
            val v = Vehicle(app)
            v.initialize()
            vehicle = v
            val g = v.getGear()
            val gl = object : GearSelectionListener {
                override fun onSelected(gear: VehicleGear?) { main.post { cb.onGear(map(gear)) } }
                override fun onFailed(e: Exception) { fail("Gear", e) }
            }
            g.getCurrentGearSelectionState({ value -> main.post { cb.onGear(map(value)) } }, { e -> fail("Gear", e) })
            g.registerGearSelection(gl)
            gear = g; gearListener = gl
            val w = v.getWiper()
            val wl = object : WiperStateListener {
                override fun onWiperStateUpdated(state: WiperState?) { main.post { cb.onWiper(map(state)) } }
                override fun onFailed(e: Exception) { fail("Wiper", e) }
            }
            w.getCurrentWiperState(wiperArea, { s -> main.post { cb.onWiper(map(s)) } }, { e -> fail("Wiper", e) })
            w.registerWiperState(wiperArea, wl)
            wiper = w; wiperListener = wl
            status("Vehicle", "Vehicle 연결 · 기어/와이퍼 구독")
        } catch (e: Throwable) { fail("Vehicle", e) }
    }

    private fun map(g: VehicleGear?): GearState = when (g) {
        null -> GearState.UNKNOWN
        VehicleGear.GEAR_PARK -> GearState.PARK
        VehicleGear.GEAR_UNKNOWN -> GearState.UNKNOWN
        else -> GearState.NOT_PARK
    }
    private fun map(s: WiperState?): Boolean? = when (s) {
        WiperState.ON -> true
        WiperState.OFF -> false
        else -> null
    }

    fun release() {
        stopFused()
        routeActive = false; main.removeCallbacks(tbtPoll)
        try { naviListener?.let { navi?.removeListener(it) }; navi?.release() }
        catch (e: Throwable) { Log.w("GILTUNE_PLEOS", "navi release: ${e.message}") }
        navi = null; naviListener = null
        try {
            gearListener?.let { gear?.unregisterGearSelection(it) }
            wiperListener?.let { wiper?.unregisterWiperState(wiperArea, it) }
            vehicle?.release()
        } catch (e: Throwable) { Log.w("GILTUNE_PLEOS", "vehicle release: ${e.message}") }
        vehicle = null; gear = null; gearListener = null; wiper = null; wiperListener = null
    }
}
