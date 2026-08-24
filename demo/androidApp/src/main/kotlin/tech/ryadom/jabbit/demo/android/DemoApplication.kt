package tech.ryadom.jabbit.demo.android

import android.app.Application
import tech.ryadom.jabbit.createJabbit
import tech.ryadom.jabbit.demo.JabbitDemo
import tech.ryadom.jabbit.demo.demoConfiguration

class DemoApplication : Application() {

    /**
     * Demo client
     */
    val jabbitDemo by lazy {
        JabbitDemo(
            jabbit = createJabbit(
                context = this,
                configuration = demoConfiguration()
            )
        )
    }
}
