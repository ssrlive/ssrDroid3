/*******************************************************************************
 *                                                                             *
 *  Copyright (C) 2017 by Max Lv <max.c.lv@gmail.com>                          *
 *  Copyright (C) 2017 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
 *                                                                             *
 *  This program is free software: you can redistribute it and/or modify       *
 *  it under the terms of the GNU General Public License as published by       *
 *  the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                        *
 *                                                                             *
 *  This program is distributed in the hope that it will be useful,            *
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 *  GNU General Public License for more details.                               *
 *                                                                             *
 *  You should have received a copy of the GNU General Public License          *
 *  along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                             *
 *******************************************************************************/

package com.github.shadowsocks.bg

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import com.github.shadowsocks.Core
import com.github.shadowsocks.VpnRequestActivity
import com.github.shadowsocks.acl.Acl
import com.github.shadowsocks.core.BuildConfig
import com.github.shadowsocks.core.R
import com.github.shadowsocks.net.DefaultNetworkListener
import com.github.shadowsocks.net.HostsFile
import com.github.shadowsocks.net.Subnet
import com.github.shadowsocks.preference.DataStore
import com.github.shadowsocks.utils.Key
import com.github.shadowsocks.utils.printLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileDescriptor
import java.io.IOException

class SsrVpnService : VpnService(), BaseService.Interface {
    companion object {
        private const val VPN_MTU = 1500
        private const val PRIVATE_VLAN4_CLIENT = "172.19.0.2"
        private const val PRIVATE_VLAN4_PREFIX_LENGTH = 30
        private const val PRIVATE_VLAN6_CLIENT = "fdfe:dcba:9876::1"
        private const val PRIVATE_VLAN6_ROUTER = "fdfe:dcba:9876::2"
        private const val PRIVATE_VLAN6_PREFIX_LENGTH = 126
    }

    inner class NullConnectionException : NullPointerException(), BaseService.ExpectedException {
        override fun getLocalizedMessage() = getString(R.string.reboot_required)
    }

    override val data = BaseService.Data(this)
    override val tag: String get() = "ShadowsocksVpnService"
    override fun createNotification(profileName: String): ServiceNotification =
        ServiceNotification(this, profileName, "service-vpn")

    private var conn: ParcelFileDescriptor? = null
    private var active = false
    private var metered = false

    @Volatile
    private var underlyingNetwork: Network? = null
        set(value) {
            field = value
            if (active && Build.VERSION.SDK_INT >= 22) setUnderlyingNetworks(underlyingNetworks)
        }

    // clearing underlyingNetworks makes Android 9 consider the network to be metered
    private val underlyingNetworks
        get() =
            if (Build.VERSION.SDK_INT == 28 && metered) null else underlyingNetwork?.let { arrayOf(it) }

    override fun onBind(intent: Intent) = when (intent.action) {
        SERVICE_INTERFACE -> super<VpnService>.onBind(intent)
        else -> super<BaseService.Interface>.onBind(intent)
    }

    override fun onRevoke() = stopRunner()

    override fun killProcesses(scope: CoroutineScope) {
        dns2socksThread?.terminate()
        dns2socksThread?.join()
        dns2socksThread = null

        tunThread?.terminate()
        tunThread?.join()
        tunThread = null

        super.killProcesses(scope)
        active = false
        scope.launch { DefaultNetworkListener.stop(this) }
        conn?.close()
        conn = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (DataStore.serviceMode == Key.modeVpn) {
            if (prepare(this) != null) {
                startActivity(Intent(this, VpnRequestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                return super<BaseService.Interface>.onStartCommand(intent, flags, startId)
            }
        }
        stopRunner()
        return Service.START_NOT_STICKY
    }

    override suspend fun preInit() = DefaultNetworkListener.start(this) { underlyingNetwork = it }
    override suspend fun getActiveNetwork() = DefaultNetworkListener.get()
    override suspend fun resolver(host: String) = DnsResolverCompat.resolve(DefaultNetworkListener.get(), host)

    override suspend fun startProcesses(hosts: HostsFile) {
        super.startProcesses(hosts)
        startVpn()
    }

    private suspend fun startVpn() {
        val profile = data.proxy!!.profile
        val localDnsSvrAddr = DataStore.localDnsSvrAddr
        val builder = Builder()
            .setConfigureIntent(Core.configureIntent(this))
            .setSession(profile.formattedName)
            .setMtu(VPN_MTU)
            .addAddress(PRIVATE_VLAN4_CLIENT, PRIVATE_VLAN4_PREFIX_LENGTH)
            .addDnsServer(localDnsSvrAddr)

        if (profile.ipv6) builder.addAddress(PRIVATE_VLAN6_CLIENT, PRIVATE_VLAN6_PREFIX_LENGTH)

        if (profile.proxyApps) {
            val me = packageName
            profile.individual.split('\n')
                .filter { it != me }
                .forEach {
                    try {
                        if (profile.bypass) builder.addDisallowedApplication(it)
                        else builder.addAllowedApplication(it)
                    } catch (ex: PackageManager.NameNotFoundException) {
                        printLog(ex)
                    }
                }
            if (!profile.bypass) builder.addAllowedApplication(me)
        }

        when (profile.route) {
            Acl.ALL, Acl.BYPASS_CHN, Acl.CUSTOM_RULES -> {
                builder.addRoute("0.0.0.0", 0)
                if (profile.ipv6) builder.addRoute("::", 0)
            }

            else -> {
                resources.getStringArray(R.array.bypass_private_route).forEach {
                    val subnet = Subnet.fromString(it)!!
                    builder.addRoute(subnet.address.hostAddress!!, subnet.prefixSize)
                }
                builder.addRoute(localDnsSvrAddr, 32)
                // https://issuetracker.google.com/issues/149636790
                if (profile.ipv6) builder.addRoute("2000::", 3)
            }
        }

        metered = profile.metered
        active = true   // possible race condition here?
        if (Build.VERSION.SDK_INT >= 22) {
            builder.setUnderlyingNetworks(underlyingNetworks)
            if (Build.VERSION.SDK_INT >= 29) builder.setMetered(metered)
        }

        val conn = builder.establish() ?: throw NullConnectionException()
        this.conn = conn

        val proxyUrl = "socks5://${DataStore.listenAddress}:${DataStore.portProxy}"
        val tunFd = conn.fd
        val tunMtu = VPN_MTU
        val verbose = BuildConfig.DEBUG
        val dnsOverTcp = !profile.isOverTLS()

        tunThread = Tun2proxyThread(proxyUrl, tunFd, tunMtu, verbose, dnsOverTcp)
        tunThread?.isDaemon = true
        tunThread?.start()

        // FIXME: here can NOT use "${localDnsSvrAddr}:53", I think Android NOT support it yet, and "127.0.0.1:5353" is useless.
        // https://stackoverflow.com/questions/79217146/how-do-i-implement-an-android-vpn-app-with-local-dns-resolver
        // https://github.com/shadowsocks/shadowsocks-android/discussions/2823
        // https://github.com/shadowsocks/shadowsocks-android/issues/3122
        //
        // val listenAddr = "${localDnsSvrAddr}:53"
        //
        val listenAddr = "127.0.0.1:5353"
        val dnsRemoteServer = profile.remoteDns
        val socks5Server = "${DataStore.listenAddress}:${DataStore.portProxy}"
        val username = null
        val password = null
        val forceTcp = true
        val cacheRecords = false
        val verbosity = if (verbose) 5 else 3
        val timeout = 10

        dns2socksThread = Dns2socksThread(
            listenAddr,
            dnsRemoteServer,
            socks5Server,
            username,
            password,
            forceTcp,
            cacheRecords,
            verbosity,
            timeout
        )
        dns2socksThread?.isDaemon = true
        dns2socksThread?.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        data.binder.close()
    }

    private var tunThread: Tun2proxyThread? = null

    internal class Tun2proxyThread(
        private val proxyUrl: String,
        private val tunFd: Int,
        private val tunMtu: Int,
        private val verbose: Boolean,
        private val dnsOverTcp: Boolean
    ) : Thread() {
        override fun run() {
            val verbosity = if (verbose) 5 else 3
            val dnsStrategy = if (dnsOverTcp) 1 else 2
            Tun2proxy.run(proxyUrl, tunFd, false, tunMtu.toChar(), verbosity, dnsStrategy)
        }

        fun terminate() {
            Tun2proxy.stop()
        }
    }

    private var dns2socksThread: Dns2socksThread? = null

    internal class Dns2socksThread(
        private val listenAddr: String?,
        private val dnsRemoteServer: String?,
        private val socks5Server: String?,
        private val username: String?,
        private val password: String?,
        private val forceTcp: Boolean,
        private val cacheRecords: Boolean,
        private val verbosity: Int,
        private val timeout: Int
    ) : Thread() {
        override fun run() {
            Dns2socks.start(
                listenAddr,
                dnsRemoteServer,
                socks5Server,
                username,
                password,
                forceTcp,
                cacheRecords,
                verbosity,
                timeout
            )
        }

        fun terminate() {
            Dns2socks.stop()
        }
    }
}
