package com.aure.clustertune.root

import android.os.Binder
import android.os.Parcel
import android.os.RemoteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PServerProtocolInstrumentationTest {
    @Test
    fun launchEncodesCountAndTwoStringsWithoutInterfaceToken() {
        val command = "cd '/data/é' && sh 'launch-host.sh'"
        var received = false
        val service = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(0, code)
                assertEquals(0, flags)
                assertEquals(2, data.readInt())
                assertEquals(command, data.readString())
                assertEquals("0", data.readString())
                assertEquals(0, data.dataAvail())
                received = true
                // Output-disabled launches have no application-level reply to decode.
                return true
            }
        }
        assertTrue(BinderPServerConnection(service).sendHostLaunch(command))
        assertTrue(received)
    }

    @Test
    fun unhandledTransactionIsReportedAsFailure() {
        val service = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int) = false
        }
        val launcher = PServerHostLauncher { BinderPServerConnection(service) }
        assertTrue(launcher.launchHost("sh launch-host.sh").isFailure)
    }

    @Test
    fun probeDoesNotSendATransaction() {
        var received = false
        val service = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                received = true
                throw RemoteException("must not transact during probe")
            }
        }
        assertTrue(PServerHostLauncher { BinderPServerConnection(service) }.pServerAvailable)
        assertFalse(received)
    }
}
